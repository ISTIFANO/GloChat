package com.marketchat.commerce.checkout.domain.port;

import com.marketchat.commerce.checkout.domain.model.Quote;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository {

    void insert(Quote quote);

    /** Row-locked read; must run inside a transaction. */
    Optional<Quote> lock(UUID quoteId);

    /** Earlier OPEN quotes of a cart become INVALIDATED (only the latest quote can be confirmed). */
    void invalidateOpen(UUID cartId);

    void consume(UUID quoteId, UUID orderId);
}
