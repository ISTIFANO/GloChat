"""
Inc 2-4 end-to-end check on the REAL running stack:
gateway :8080 -> commerce :8081 (PostgreSQL, Kafka) -> payment :8084 -> Stripe test mode, with a real Keycloak login.

Usage: python infra/e2e-checkout.py      (stack + services running; secrets read from infra/.env, never printed)
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
with open(os.path.join(ROOT, "infra", ".env"), encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            ENV[k] = v

GW = ENV["GATEWAY_URL"]
KC = ENV["KEYCLOAK_TOKEN_URI"]
AGDAL = {"label": "Maison", "line": "12 avenue de France", "city": "Rabat", "lat": 33.9981, "lng": -6.8497}
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  -> {detail}"))


def http(method, path, body=None, token=None, headers=None, base=GW):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def login():
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "marketchat-dev-cli",
                                   "username": "demo@marketchat.local", "password": ENV["DEMO_USER_PASSWORD"]}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=form), timeout=30) as r:
        return json.loads(r.read())["access_token"]


def ops(token, operations):
    return http("POST", "/api/cart/operations", {"operations": operations}, token)


def ready_cart(token, method):
    ops(token, [{"op": "CLEAR"},
                {"op": "ADD", "productId": "p-double-smash", "quantity": 2,
                 "optionValueIds": ["p-double-smash-size-m", "p-ds-side-fries"]}])
    http("PUT", "/api/cart/delivery", {"address": AGDAL}, token)
    return http("PUT", "/api/cart/payment-method", {"method": method}, token)


def confirm(token, quote, key):
    return http("POST", "/api/checkout/confirm", {"quoteId": quote["quoteId"],
                                                  "confirmationToken": quote["confirmationToken"],
                                                  "idempotencyKey": key}, token)


# ── security ──
code, _ = http("GET", "/api/cart")
check("cart without a token is 401", code == 401, code)
token = login()
check("real Keycloak login (demo user)", bool(token))

# ── cart (Inc 2) ──
code, cart = ops(token, [{"op": "CLEAR"},
                         {"op": "ADD", "productId": "p-double-smash", "quantity": 1,
                          "optionValueIds": ["p-double-smash-size-l", "p-ds-extra-bacon", "p-ds-side-fries"]}])
check("add configured product: 65+12+10+15 = 102 MAD", code == 200 and cart["lines"][0]["unitPrice"] == 102.0, cart)
code, cart = ops(token, [{"op": "ADD", "productId": "p-double-smash", "quantity": 1,
                          "optionValueIds": ["p-ds-side-fries", "p-ds-extra-bacon", "p-double-smash-size-l"]}])
check("identical line merged (quantity 2)", code == 200 and len(cart["lines"]) == 1 and cart["lines"][0]["quantity"] == 2, cart)
code, err = ops(token, [{"op": "ADD", "productId": "p-double-smash", "quantity": 1, "optionValueIds": []}])
check("missing required option -> 422 OPTION_REQUIRED (Taille)",
      code == 422 and err.get("code") == "OPTION_REQUIRED" and err["violations"][0]["group"] == "Taille", err)
code, err = ops(token, [{"op": "ADD", "productId": "p-california", "quantity": 1}])
check("other merchant -> 409 CART_MERCHANT_CONFLICT", code == 409 and err.get("code") == "CART_MERCHANT_CONFLICT", err)
code, err = http("POST", "/api/cart/operations", {"expectedVersion": 0, "operations": [{"op": "CLEAR"}]}, token)
check("stale version -> 409 CART_VERSION_CONFLICT", code == 409 and err.get("code") == "CART_VERSION_CONFLICT", err)

code, cart = ready_cart(token, "CASH")
check("ready cart: no issues, fee and total computed",
      code == 200 and cart["issues"] == [] and cart["subtotal"] == 160.0 and cart["delivery"]["fee"] is not None,
      cart.get("issues") if isinstance(cart, dict) else cart)

# ── checkout + orders (Inc 3) ──
code, quote = http("POST", "/api/checkout/quote", None, token)
check("quote with confirmation token", code == 200 and len(quote.get("confirmationToken", "")) == 22, quote)
key = str(uuid.uuid4())
code, order = confirm(token, quote, key)
check("cash order PLACED", code == 201 and order["status"] == "PLACED" and order["paymentStatus"] == "CASH_ON_DELIVERY", order)
number = order.get("number")
code, again = confirm(token, quote, key)
check("same idempotency key -> same order (200)", code == 200 and again["number"] == number, again)
code, err = confirm(token, quote, str(uuid.uuid4()))
check("quote reused with another key -> 409 QUOTE_ALREADY_USED", code == 409 and err.get("code") == "QUOTE_ALREADY_USED", err)
code, cart = http("GET", "/api/cart", None, token)
check("cart emptied after checkout", code == 200 and cart["lines"] == [], cart)
code, mine = http("GET", "/api/orders/" + number, None, token)
check("order readable by its owner", code == 200 and mine["total"] == order["total"], mine)

# Kafka: the outbox relay published OrderPlaced on order.events
try:
    out = subprocess.run(["docker", "exec", "marketchat-kafka-1", "/opt/kafka/bin/kafka-console-consumer.sh",
                          "--bootstrap-server", "localhost:9092", "--topic", "order.events", "--from-beginning",
                          "--timeout-ms", "8000", "--property", "print.key=true"],
                         capture_output=True, text=True, timeout=60).stdout
except Exception as e:  # noqa: BLE001
    out = str(e)
check("Kafka order.events has OrderPlaced for " + str(number),
      any(number in l and "OrderPlaced" in l for l in out.splitlines()), out[-300:])

# ── card payment (Inc 4) ──
ready_cart(token, "CARD")
code, quote = http("POST", "/api/checkout/quote", None, token)
code, card = confirm(token, quote, str(uuid.uuid4()))
check("card order AWAITING_PAYMENT with a real Stripe Checkout URL",
      code == 201 and card["status"] == "AWAITING_PAYMENT"
      and str(card.get("paymentUrl", "")).startswith("https://checkout.stripe.com/"), card)
code, refreshed = http("POST", f"/api/orders/{card.get('number')}/payment/refresh", None, token)
check("unpaid session verified with Stripe: still AWAITING_PAYMENT",
      code == 200 and refreshed["status"] == "AWAITING_PAYMENT", refreshed)
code, retry = http("POST", f"/api/orders/{card.get('number')}/pay", None, token)
check("retry payment returns the same open Stripe session",
      code == 200 and retry.get("paymentUrl") == card.get("paymentUrl"), retry)
code, canceled = http("POST", f"/api/orders/{card.get('number')}/cancel", None, token)
check("customer cancels the unpaid card order", code == 200 and canceled["status"] == "CANCELED", canceled)

code, body = http("POST", "/api/payments/stripe/webhook", {"id": "evt_forged"},
                  headers={"Stripe-Signature": "t=1,v1=forged"})
expected = "INVALID_SIGNATURE" if ENV.get("STRIPE_WEBHOOK_SECRET") else "WEBHOOK_NOT_CONFIGURED"
check(f"webhook via gateway without a valid signature is refused ({expected})",
      code in (400, 503) and isinstance(body, dict) and body.get("code") == expected, body)
code, _ = http("POST", "/internal/payments/sessions", {}, token)
check("internal payment API not reachable through the gateway", code in (403, 404), code)

code, orders = http("GET", "/api/orders", None, token)
check("order history lists both orders", code == 200 and {number, card.get("number")} <= {o["number"] for o in orders}, code)

failed = results.count(False)
print(f"\n{len(results) - failed}/{len(results)} checks passed")
sys.exit(1 if failed else 0)
