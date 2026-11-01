package com.marketchat.commerce.checkout.application;

import com.marketchat.commerce.cart.application.CartService;
import com.marketchat.commerce.cart.application.CartView;
import com.marketchat.commerce.checkout.domain.model.Quote;
import com.marketchat.commerce.checkout.domain.port.CheckoutRequests;
import com.marketchat.commerce.checkout.domain.port.QuoteRepository;
import com.marketchat.commerce.checkout.domain.service.ConfirmationTokens;
import com.marketchat.commerce.orders.application.OrderService;
import com.marketchat.commerce.orders.application.OrderView;
import com.marketchat.commerce.orders.application.PlaceOrderCommand;
import com.marketchat.commerce.shared.application.Transactions;
import com.marketchat.commerce.shared.domain.BusinessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Checkout (spec §6.1-6.2). {@link #confirm} is the ONLY way an order is created: it needs the quote's token,
 * which only the app has (the user tapped "Confirm"), and is idempotent by key.
 */
public class CheckoutService {

    static final Duration QUOTE_TTL = Duration.ofMinutes(10);

    private final CartService carts;
    private final OrderService orders;
    private final QuoteRepository quotes;
    private final CheckoutRequests requests;
    private final Transactions tx;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public CheckoutService(CartService carts, OrderService orders, QuoteRepository quotes, CheckoutRequests requests,
                           Transactions tx, Clock clock, Supplier<UUID> ids) {
        this.carts = carts;
        this.orders = orders;
        this.quotes = quotes;
        this.requests = requests;
        this.tx = tx;
        this.clock = clock;
        this.ids = ids;
    }

    public QuoteView createQuote(String customerId) {
        return tx.inTransaction(() -> {
            CartView cart = carts.lockAndPrice(customerId);
            if (!cart.ready()) {
                throw notReady(cart);
            }
            return newQuote(customerId, cart);
        });
    }

    public ConfirmResult confirm(String customerId, UUID quoteId, String confirmationToken, UUID idempotencyKey) {
        if (quoteId == null || idempotencyKey == null) {
            throw new BusinessException("INVALID_ARGUMENT", 400, "quoteId and idempotencyKey are required");
        }
        ConfirmResult result = tx.inTransaction(() -> {
            Optional<ConfirmResult> replay = replay(customerId, idempotencyKey);
            if (replay.isPresent()) {
                return replay.get();
            }
            Quote quote = quotes.lock(quoteId).filter(q -> q.customerId().equals(customerId))
                    .orElseThrow(() -> new BusinessException("QUOTE_NOT_FOUND", 404, "Quote not found"));
            // a concurrent request with the same key may have committed while we waited for the lock
            replay = replay(customerId, idempotencyKey);
            if (replay.isPresent()) {
                return replay.get();
            }
            switch (quote.status()) {
                case CONSUMED -> throw new BusinessException("QUOTE_ALREADY_USED", 409, "This quote was already confirmed");
                case INVALIDATED, EXPIRED -> throw new BusinessException("QUOTE_INVALIDATED", 409,
                        "This quote is no longer valid, request a new one");
                default -> { }
            }
            if (quote.expiredAt(clock.instant())) {
                throw new BusinessException("QUOTE_EXPIRED", 410, "The quote expired, request a new one");
            }
            if (!ConfirmationTokens.matches(confirmationToken, quote.tokenHash())) {
                throw new BusinessException("INVALID_CONFIRMATION_TOKEN", 403, "Invalid confirmation token");
            }

            CartView cart = carts.lockAndPrice(customerId);
            if (!quote.cartId().equals(cart.cartId()) || quote.cartVersion() != cart.version()) {
                throw new BusinessException("QUOTE_STALE", 409, "The cart changed since this quote, request a new one");
            }
            if (!cart.ready()) {
                throw notReady(cart);
            }
            if (cart.total().compareTo(quote.total()) != 0 || cart.subtotal().compareTo(quote.subtotal()) != 0) {
                return new ConfirmResult.PriceChanged(newQuote(customerId, cart));
            }

            OrderView order = orders.place(toCommand(quote));
            quotes.consume(quote.id(), order.id());
            carts.markCheckedOut(cart.cartId());
            requests.record(new CheckoutRequests.Done(idempotencyKey, customerId, quote.id(), order.id()));
            return new ConfirmResult.Placed(order, false);
        });

        // card: open the payment session after the commit (remote call, never inside the DB transaction)
        if (result instanceof ConfirmResult.Placed placed && !placed.replayed() && placed.order().awaitingPayment()) {
            return new ConfirmResult.Placed(orders.startPaymentQuietly(placed.order().id()), false);
        }
        return result;
    }

    private Optional<ConfirmResult> replay(String customerId, UUID idempotencyKey) {
        return requests.find(idempotencyKey).map(done -> {
            if (!done.customerId().equals(customerId)) {
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED", 409, "This idempotency key was already used");
            }
            return new ConfirmResult.Placed(orders.getById(done.orderId()), true);
        });
    }

    private QuoteView newQuote(String customerId, CartView cart) {
        quotes.invalidateOpen(cart.cartId());
        String token = ConfirmationTokens.generate();
        Instant now = clock.instant();
        CartView.Delivery d = cart.delivery();
        Quote quote = new Quote(ids.get(), cart.cartId(), cart.version(), customerId, cart.merchant().id(),
                new Quote.Snapshot(
                        new Quote.Merchant(cart.merchant().id(), cart.merchant().name(), cart.merchant().emoji(),
                                cart.merchant().logoUrl()),
                        cart.lines().stream().map(l -> new Quote.Line(l.productId(), l.name(), l.imageUrl(), l.emoji(),
                                l.unitPrice(), l.quantity(), l.lineTotal(), l.note(),
                                l.selections().stream().map(s -> new Quote.Selection(s.group(), s.label(), s.priceDelta()))
                                        .toList())).toList(),
                        new Quote.Delivery(d.label(), d.line(), d.city(), d.lat(), d.lng(), d.distanceKm(), d.etaText())),
                cart.subtotal(), d.fee(), BigDecimal.ZERO.setScale(2), cart.total(), cart.currency(), cart.paymentMethod(),
                ConfirmationTokens.hash(token), Quote.Status.OPEN, now.plus(QUOTE_TTL), now);
        quotes.insert(quote);
        return QuoteView.of(quote, token);
    }

    private static PlaceOrderCommand toCommand(Quote q) {
        Quote.Snapshot s = q.snapshot();
        Quote.Delivery d = s.delivery();
        return new PlaceOrderCommand(q.id(), q.customerId(), s.merchant().id(), s.merchant().name(), s.merchant().emoji(),
                s.merchant().logoUrl(), q.paymentMethod(), q.subtotal(), q.deliveryFee(), q.discount(), q.total(),
                q.currency(), d.label(), d.line(), d.city(), d.lat(), d.lng(), d.etaText(),
                s.lines().stream().map(l -> new PlaceOrderCommand.Line(l.productId(), l.name(), l.imageUrl(), l.emoji(),
                        l.unitPrice(), l.quantity(), l.lineTotal(), l.note(),
                        l.selections().stream().map(x -> new PlaceOrderCommand.Selection(x.group(), x.label(), x.priceDelta()))
                                .toList())).toList());
    }

    private static BusinessException notReady(CartView cart) {
        return new BusinessException("CHECKOUT_NOT_READY", 422, "The cart can't be ordered yet",
                Map.of("issues", cart.issues()));
    }
}
