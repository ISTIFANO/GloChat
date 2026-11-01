package com.marketchat.commerce.checkout.application;

import com.marketchat.commerce.checkout.domain.model.Quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the app shows on the "Confirm & pay" screen. Carries the confirmation token (app only, never the LLM). */
public record QuoteView(
        UUID quoteId,
        String confirmationToken,
        Instant expiresAt,
        Quote.Merchant merchant,
        List<Quote.Line> lines,
        Quote.Delivery delivery,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        String paymentMethod) {

    static QuoteView of(Quote q, String token) {
        return new QuoteView(q.id(), token, q.expiresAt(), q.snapshot().merchant(), q.snapshot().lines(),
                q.snapshot().delivery(), q.subtotal(), q.deliveryFee(), q.discount(), q.total(), q.currency(),
                q.paymentMethod());
    }
}
