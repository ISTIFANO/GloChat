package com.marketchat.payment.infrastructure.stripe;

import com.marketchat.payment.domain.model.PaymentException;
import com.marketchat.payment.domain.port.PaymentProvider;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Stripe Checkout (hosted page). The amount is the order total received from commerce, never from the browser.
 * Test mode only in dev: keys come from infra/.env.
 */
public class StripePaymentProvider implements PaymentProvider {

    private final StripeClient stripe;

    public StripePaymentProvider(String secretKey) {
        this.stripe = secretKey == null || secretKey.isBlank() ? null : new StripeClient(secretKey);
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest r) {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl())
                .setClientReferenceId(r.orderId().toString())
                .setExpiresAt(r.expiresAt().getEpochSecond())
                .putMetadata("orderId", r.orderId().toString())
                .putMetadata("orderNumber", r.orderNumber())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("orderId", r.orderId().toString())
                        .putMetadata("orderNumber", r.orderNumber())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(r.currency().toLowerCase())
                                .setUnitAmount(r.amountMinor())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("GloChat · commande " + r.orderNumber())
                                        .build())
                                .build())
                        .build())
                .build();
        try {
            Session s = client().v1().checkout().sessions().create(params,
                    RequestOptions.builder().setIdempotencyKey(r.idempotencyKey()).build());
            return new CheckoutSession(s.getId(), s.getUrl());
        } catch (StripeException e) {
            String reason = e.getStripeError() == null ? e.getMessage() : e.getStripeError().getMessage();
            throw new PaymentException("PAYMENT_PROVIDER_ERROR", 502,
                    "Stripe refused the checkout session (" + e.getCode() + "): " + reason);
        }
    }

    @Override
    public SessionState retrieve(String sessionId) {
        try {
            Session s = client().v1().checkout().sessions().retrieve(sessionId);
            return StripeSessions.state(s);
        } catch (StripeException e) {
            throw new PaymentException("PAYMENT_PROVIDER_ERROR", 502, "Stripe error: " + e.getMessage());
        }
    }

    private StripeClient client() {
        if (stripe == null) {
            throw new PaymentException("PAYMENT_PROVIDER_NOT_CONFIGURED", 503, "STRIPE_SECRET_KEY is not configured");
        }
        return stripe;
    }
}
