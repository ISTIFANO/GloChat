package com.marketchat.payment.domain.port;

/** Verifies a provider webhook's signature and extracts the checkout-session event it carries. */
public interface WebhookVerifier {

    /** A verified event. {@code session} is null for event types we don't handle. */
    record VerifiedEvent(String eventId, String type, PaymentProvider.SessionState session) {
    }

    /** @throws com.marketchat.payment.domain.model.PaymentException INVALID_SIGNATURE when the signature is wrong */
    VerifiedEvent verify(String payload, String signatureHeader);
}
