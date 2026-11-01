package com.marketchat.payment;

import com.marketchat.payment.domain.port.PaymentProvider;
import com.marketchat.payment.infrastructure.stripe.StripePaymentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in check against the REAL Stripe test-mode API (RUN_LIVE_STRIPE_TESTS=true + STRIPE_SECRET_KEY=sk_test_...).
 * Proves the key works and that Stripe accepts a MAD checkout session. Never runs in CI by default.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_STRIPE_TESTS", matches = "true")
class StripeLiveTest {

    @Test
    void stripeAcceptsAMadCheckoutSession() {
        String key = System.getenv("STRIPE_SECRET_KEY");
        assertThat(key).as("a TEST key, never a live one").startsWith("sk_test_");
        StripePaymentProvider stripe = new StripePaymentProvider(key);
        UUID orderId = UUID.randomUUID();

        PaymentProvider.CheckoutSession session = stripe.createCheckout(new PaymentProvider.CheckoutRequest(
                orderId, "MC-TEST-LIVE", 17100, "MAD", "http://localhost:4200/orders/x?payment=success",
                "http://localhost:4200/orders/x?payment=cancel", Instant.now().plusSeconds(31 * 60),
                "live-test-" + orderId));

        assertThat(session.sessionId()).startsWith("cs_test_");
        assertThat(session.redirectUrl()).startsWith("https://checkout.stripe.com/");
        PaymentProvider.SessionState state = stripe.retrieve(session.sessionId());
        assertThat(state.status()).isEqualTo("open");
        assertThat(state.amountTotalMinor()).isEqualTo(17100);
        assertThat(state.currency()).isEqualTo("mad");
        assertThat(state.paid()).isFalse();
    }
}
