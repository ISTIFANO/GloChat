package com.marketchat.commerce.checkout.application;

import com.marketchat.commerce.orders.application.OrderView;

/** Outcome of a confirmation: an order (new or replayed), or a new quote because prices changed. */
public sealed interface ConfirmResult {

    record Placed(OrderView order, boolean replayed) implements ConfirmResult {
    }

    /** The total changed since the quote: nothing was ordered, the customer must confirm the new quote. */
    record PriceChanged(QuoteView newQuote) implements ConfirmResult {
    }
}
