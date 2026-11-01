package com.marketchat.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** The card payment of one order, through a hosted provider checkout session. */
public record Payment(
        UUID id,
        UUID orderId,
        String orderNumber,
        String provider,
        String sessionId,
        String redirectUrl,
        BigDecimal amount,
        String currency,
        Status status,
        String providerRef,
        Instant expiresAt,
        Instant createdAt) {

    public enum Status { PENDING, SUCCEEDED, FAILED, EXPIRED, AMOUNT_MISMATCH }

    /** Amount in the currency's minor unit (MAD, EUR, USD: cents), as providers expect. */
    public long amountMinor() {
        return toMinor(amount);
    }

    public static long toMinor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /** True only when the provider charged exactly this payment's amount in its currency. */
    public boolean matches(Long paidMinor, String paidCurrency) {
        return paidMinor != null && paidMinor == amountMinor() && currency.equalsIgnoreCase(paidCurrency);
    }
}
