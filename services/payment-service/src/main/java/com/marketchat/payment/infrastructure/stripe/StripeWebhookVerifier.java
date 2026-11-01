package com.marketchat.payment.infrastructure.stripe;

import com.marketchat.payment.domain.model.PaymentException;
import com.marketchat.payment.domain.port.WebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

/**
 * Verifies the Stripe-Signature header (HMAC-SHA256 with the endpoint secret, 5-minute tolerance against replay)
 * with the SDK's {@code Webhook.constructEvent}. A forged or replayed request never reaches the business logic.
 */
public class StripeWebhookVerifier implements WebhookVerifier {

    private final String endpointSecret;

    public StripeWebhookVerifier(String endpointSecret) {
        this.endpointSecret = endpointSecret;
    }

    @Override
    public VerifiedEvent verify(String payload, String signatureHeader) {
        if (endpointSecret == null || endpointSecret.isBlank()) {
            throw new PaymentException("WEBHOOK_NOT_CONFIGURED", 503, "STRIPE_WEBHOOK_SECRET is not configured");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new PaymentException("INVALID_SIGNATURE", 400, "Missing Stripe-Signature header");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            throw new PaymentException("INVALID_SIGNATURE", 400, "Invalid Stripe signature");
        } catch (RuntimeException e) {
            throw new PaymentException("INVALID_PAYLOAD", 400, "Unreadable Stripe event");
        }
        if (!event.getType().startsWith("checkout.session.")) {
            return new VerifiedEvent(event.getId(), event.getType(), null);
        }
        StripeObject object;
        try {
            // the account's API version may differ from the SDK's: fall back to lenient deserialization
            object = event.getDataObjectDeserializer().getObject()
                    .orElseGet(() -> unsafe(event));
        } catch (RuntimeException e) {
            throw new PaymentException("INVALID_PAYLOAD", 400, "Unreadable Stripe checkout session");
        }
        if (!(object instanceof Session session)) {
            return new VerifiedEvent(event.getId(), event.getType(), null);
        }
        return new VerifiedEvent(event.getId(), event.getType(), StripeSessions.state(session));
    }

    private static StripeObject unsafe(Event event) {
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
