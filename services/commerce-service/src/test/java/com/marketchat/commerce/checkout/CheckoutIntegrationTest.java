package com.marketchat.commerce.checkout;

import com.jayway.jsonpath.JsonPath;
import com.marketchat.commerce.orders.application.OrderService;
import com.marketchat.commerce.support.ApiTestBase;
import com.marketchat.commerce.support.CommerceTestConfig.FakePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inc 3 + commerce side of Inc 4 (spec §6, §7, §12): quotes, the only way to create an order, payment states. */
class CheckoutIntegrationTest extends ApiTestBase {

    @Autowired
    OrderService orderService;

    record QuoteRef(String quoteId, String token, double total) {
    }

    QuoteRef quote(String customer) throws Exception {
        ResultActions r = postAs(customer, "/api/checkout/quote", null).andExpect(status().isOk());
        return new QuoteRef(read(r, "$.quoteId"), read(r, "$.confirmationToken"), ((Number) read(r, "$.total")).doubleValue());
    }

    ResultActions confirm(String customer, QuoteRef q, String idempotencyKey) throws Exception {
        return postAs(customer, "/api/checkout/confirm", "{\"quoteId\":\"" + q.quoteId() + "\",\"confirmationToken\":\""
                + q.token() + "\",\"idempotencyKey\":\"" + idempotencyKey + "\"}");
    }

    long ordersForQuote(String quoteId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE quote_id = CAST(:q AS uuid)").param("q", quoteId).query(Long.class).single();
    }

    @Test
    void cartWithIssuesCannotBeQuoted() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "]");
        postAs(me, "/api/checkout/quote", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHECKOUT_NOT_READY"))
                .andExpect(jsonPath("$.issues[*].code", hasItem("BELOW_MINIMUM_ORDER")));
        postAs(newCustomer(), "/api/checkout/quote", null)
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
    }

    @Test
    void quoteFreezesPricesAndExpiresIn10Minutes() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        postAs(me, "/api/checkout/quote", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationToken", matchesPattern("[A-Za-z0-9_-]{22}")))
                .andExpect(jsonPath("$.subtotal").value(160.0))
                .andExpect(jsonPath("$.deliveryFee").value(11.0))
                .andExpect(jsonPath("$.total").value(171.0))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].selections[*].label", contains("Normal", "Frites")))
                .andExpect(jsonPath("$.delivery.etaText").value("13–23 min"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-04T19:10:00Z"));
        // the token itself is never stored, only its hash
        assertThat(jdbc.sql("SELECT count(*) FROM quotes WHERE customer_id = :c AND length(token_hash) = 32")
                .param("c", me).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cashOrderIsPlacedAndTheCartIsCheckedOut() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        ResultActions r = confirm(me, q, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number", matchesPattern("MC-26-\\d{6}")))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.paymentStatus").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.paymentUrl").value(nullValue()))
                .andExpect(jsonPath("$.total").value(171.0))
                .andExpect(jsonPath("$.merchant.name").value("Smash Lab Agdal"))
                .andExpect(jsonPath("$.lines[0].name").value("Double Smash Burger"))
                .andExpect(jsonPath("$.lines[0].selections[1].label").value("Frites"))
                .andExpect(jsonPath("$.delivery.line").value("12 avenue de France"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.history[*].status", contains("PLACED")));
        String number = read(r, "$.number");

        getAs(me, "/api/cart").andExpect(jsonPath("$.lines", empty()));
        getAs(me, "/api/orders").andExpect(jsonPath("$[*].number", contains(number)));
        getAs(me, "/api/orders/" + number).andExpect(jsonPath("$.status").value("PLACED"));
        assertThat(paymentGateway.sessions).isEmpty();

        String orderId = read(r, "$.id");
        assertThat(jdbc.sql("SELECT type FROM outbox_events WHERE aggregate_id = :id").param("id", orderId)
                .query(String.class).list()).containsExactly("OrderPlaced");
        String envelope = jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = :id").param("id", orderId)
                .query(String.class).single();
        assertThat((String) JsonPath.read(envelope, "$.eventId")).isNotBlank();
        assertThat((String) JsonPath.read(envelope, "$.type")).isEqualTo("OrderPlaced");
        assertThat((Integer) JsonPath.read(envelope, "$.version")).isEqualTo(1);
        assertThat((String) JsonPath.read(envelope, "$.payload.number")).isEqualTo(number);
    }

    @Test
    void doubleConfirmWithTheSameKeyCreatesOneOrder() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        String key = UUID.randomUUID().toString();
        String first = read(confirm(me, q, key).andExpect(status().isCreated()), "$.number");
        String second = read(confirm(me, q, key).andExpect(status().isOk()), "$.number");
        assertThat(second).isEqualTo(first);
        assertThat(ordersForQuote(q.quoteId())).isEqualTo(1);

        // a different key cannot reuse the quote
        confirm(me, q, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_USED"));
        assertThat(ordersForQuote(q.quoteId())).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsStillCreateOneOrder() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        String key = UUID.randomUUID().toString();
        List<Thread> threads = new java.util.ArrayList<>();
        List<Integer> statuses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    statuses.add(confirm(me, q, key).andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    statuses.add(-1);
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(statuses).containsOnly(200, 201).containsOnlyOnce(201);
        assertThat(ordersForQuote(q.quoteId())).isEqualTo(1);
    }

    @Test
    void wrongTokenOrSomeoneElsesQuoteIsRejected() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        confirm(me, new QuoteRef(q.quoteId(), "AAAAAAAAAAAAAAAAAAAAAA", q.total()), UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIRMATION_TOKEN"));
        confirm(newCustomer(), q, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        assertThat(ordersForQuote(q.quoteId())).isZero();
    }

    @Test
    void quoteIsStaleOnceTheCartChanges() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        ops(me, "[" + add("p-coke", 1) + "]");
        confirm(me, q, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_STALE"));
    }

    @Test
    void onlyTheLatestQuoteCanBeConfirmed() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef first = quote(me);
        QuoteRef second = quote(me);
        confirm(me, first, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_INVALIDATED"));
        confirm(me, second, UUID.randomUUID().toString()).andExpect(status().isCreated());
    }

    @Test
    void expiredQuoteIsRejected() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        confirm(me, q, UUID.randomUUID().toString())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void priceChangeReturnsANewQuoteInsteadOfOrdering() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        QuoteRef q = quote(me);
        try {
            setPrice("p-double-smash", "70.00");
            ResultActions r = confirm(me, q, UUID.randomUUID().toString())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                    .andExpect(jsonPath("$.quote.total").value(181.0))
                    .andExpect(jsonPath("$.quote.confirmationToken").isString());
            assertThat(ordersForQuote(q.quoteId())).isZero();
            QuoteRef fresh = new QuoteRef(read(r, "$.quote.quoteId"), read(r, "$.quote.confirmationToken"), 181.0);
            confirm(me, fresh, UUID.randomUUID().toString())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.total").value(181.0));
        } finally {
            setPrice("p-double-smash", "65.00");
        }
    }

    @Test
    void cardOrderWaitsForPaymentThenIsPlacedOnceVerified() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        ResultActions r = confirm(me, quote(me), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.paymentUrl", startsWith("https://checkout.stripe.test/")));
        String number = read(r, "$.number");
        // the amount sent to payment-service is the order total from the database
        assertThat(paymentGateway.sessions).singleElement().satisfies(s -> {
            assertThat(s.amount()).isEqualByComparingTo("171.00");
            assertThat(s.currency()).isEqualTo("MAD");
            assertThat(s.successUrl()).isEqualTo("http://localhost:4200/orders/" + number + "?payment=success");
        });

        // still pending at the provider: nothing changes
        postAs(me, "/api/orders/" + number + "/payment/refresh", null)
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));

        paymentGateway.nextState = FakePaymentGateway.succeeded(new BigDecimal("171.00"));
        postAs(me, "/api/orders/" + number + "/payment/refresh", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentUrl").value(nullValue()))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.history[*].status", contains("AWAITING_PAYMENT", "PLACED")));
    }

    @Test
    void wrongPaidAmountNeverPlacesTheOrder() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        String number = read(confirm(me, quote(me), UUID.randomUUID().toString()), "$.number");
        paymentGateway.nextState = FakePaymentGateway.succeeded(new BigDecimal("1.00"));
        postAs(me, "/api/orders/" + number + "/payment/refresh", null)
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
    }

    @Test
    void paymentEventsAreAppliedOnce() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        ResultActions r = confirm(me, quote(me), UUID.randomUUID().toString());
        UUID orderId = UUID.fromString(read(r, "$.id"));
        String number = read(r, "$.number");
        String eventId = UUID.randomUUID().toString();
        orderService.onPaymentEvent(eventId, "PaymentSucceeded", orderId, new BigDecimal("171.00"), "MAD", "pi_1", null);
        orderService.onPaymentEvent(eventId, "PaymentSucceeded", orderId, new BigDecimal("171.00"), "MAD", "pi_1", null);
        getAs(me, "/api/orders/" + number)
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.history", hasSize(2)));
        assertThat(jdbc.sql("SELECT payment_ref FROM orders WHERE id = :id").param("id", orderId).query(String.class).single())
                .isEqualTo("pi_1");
    }

    @Test
    void paymentServiceDownKeepsTheOrderAndPaymentCanBeRetried() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        paymentGateway.down = true;
        String number = read(confirm(me, quote(me), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentUrl").value(nullValue())), "$.number");
        postAs(me, "/api/orders/" + number + "/pay", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_UNAVAILABLE"));
        paymentGateway.down = false;
        postAs(me, "/api/orders/" + number + "/pay", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentUrl", startsWith("https://checkout.stripe.test/")));
    }

    @Test
    void unpaidCardOrdersAreCanceledAfter30Minutes() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        String number = read(confirm(me, quote(me), UUID.randomUUID().toString()), "$.number");
        clock.advance(Duration.ofMinutes(31));
        assertThat(orderService.expireUnpaidOrders()).isGreaterThanOrEqualTo(1);
        getAs(me, "/api/orders/" + number)
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.paymentStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.history[1].actor").value("SYSTEM"));
    }

    @Test
    void cancelRules() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        String cash = read(confirm(me, quote(me), UUID.randomUUID().toString()), "$.number");
        postAs(newCustomer(), "/api/orders/" + cash + "/cancel", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        postAs(me, "/api/orders/" + cash + "/cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancellable").value(false));
        postAs(me, "/api/orders/" + cash + "/cancel", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));

        // a paid card order needs a refund: not self-service
        readyCart(me, "CARD");
        ResultActions r = confirm(me, quote(me), UUID.randomUUID().toString());
        String card = read(r, "$.number");
        orderService.onPaymentEvent(UUID.randomUUID().toString(), "PaymentSucceeded", UUID.fromString(read(r, "$.id")),
                new BigDecimal("171.00"), "MAD", "pi_2", null);
        postAs(me, "/api/orders/" + card + "/cancel", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
    }

    @Test
    void ordersAreOnlyVisibleToTheirOwner() throws Exception {
        String alice = newCustomer();
        readyCart(alice, "CASH");
        String number = read(confirm(alice, quote(alice), UUID.randomUUID().toString()), "$.number");
        String bob = newCustomer();
        getAs(bob, "/api/orders/" + number)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        getAs(bob, "/api/orders/MC-26-999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        getAs(bob, "/api/orders").andExpect(jsonPath("$", empty()));
    }
}
