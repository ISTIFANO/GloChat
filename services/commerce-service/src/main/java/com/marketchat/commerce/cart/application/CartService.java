package com.marketchat.commerce.cart.application;

import com.marketchat.commerce.cart.domain.model.Cart;
import com.marketchat.commerce.cart.domain.model.CartLine;
import com.marketchat.commerce.cart.domain.model.DeliveryAddress;
import com.marketchat.commerce.cart.domain.model.PaymentMethod;
import com.marketchat.commerce.cart.domain.model.SavedAddress;
import com.marketchat.commerce.cart.domain.port.AddressRepository;
import com.marketchat.commerce.cart.domain.port.CartRepository;
import com.marketchat.commerce.catalog.application.CatalogPricing;
import com.marketchat.commerce.shared.application.Transactions;
import com.marketchat.commerce.shared.domain.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cart use cases (spec §5). The REST API and (later) the agent tools call this same service.
 * Every change runs in one transaction on the row-locked cart, increments its version and returns a fresh view.
 */
public class CartService {

    static final int MAX_SAVED_ADDRESSES = 10;

    private final CartRepository carts;
    private final AddressRepository addresses;
    private final CatalogPricing catalog;
    private final Transactions tx;
    private final Supplier<UUID> ids;

    public CartService(CartRepository carts, AddressRepository addresses, CatalogPricing catalog, Transactions tx,
                       Supplier<UUID> ids) {
        this.carts = carts;
        this.addresses = addresses;
        this.catalog = catalog;
        this.tx = tx;
        this.ids = ids;
    }

    public CartView view(String customerId) {
        return carts.findActive(customerId).map(this::price).orElseGet(CartService::emptyView);
    }

    /** Applies a batch of operations atomically: either all succeed or the cart is unchanged. */
    public CartView apply(String customerId, Long expectedVersion, List<CartOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new BusinessException("INVALID_ARGUMENT", 400, "At least one operation is required");
        }
        return change(customerId, expectedVersion, cart -> {
            for (CartOperation op : operations) {
                if (op instanceof CartOperation.Add add) {
                    CatalogPricing.PricedLine priced = catalog.priceForAdd(add.productId(), add.optionValueIds());
                    cart.add(ids.get(), add.productId(), priced.merchantId(), add.quantity(), add.optionValueIds(),
                            add.note());
                } else if (op instanceof CartOperation.Update u) {
                    cart.updateLine(u.lineId(), u.quantity(), u.note());
                } else if (op instanceof CartOperation.Remove r) {
                    cart.removeLine(r.lineId());
                } else {
                    cart.clear();
                }
            }
        });
    }

    public CartView setDelivery(String customerId, DeliveryAddress address) {
        return change(customerId, null, cart -> cart.deliverTo(address));
    }

    public CartView setDeliveryFromSaved(String customerId, UUID addressId) {
        SavedAddress saved = addresses.find(customerId, addressId)
                .orElseThrow(() -> new BusinessException("ADDRESS_NOT_FOUND", 404, "Unknown address"));
        return setDelivery(customerId, saved.address());
    }

    public CartView setPaymentMethod(String customerId, PaymentMethod method) {
        if (method == null) {
            throw new BusinessException("INVALID_ARGUMENT", 400, "Payment method is required (CASH or CARD)");
        }
        return change(customerId, null, cart -> cart.payWith(method));
    }

    public List<SavedAddress> addresses(String customerId) {
        return addresses.list(customerId);
    }

    public SavedAddress addAddress(String customerId, DeliveryAddress address) {
        return tx.inTransaction(() -> {
            if (addresses.list(customerId).size() >= MAX_SAVED_ADDRESSES) {
                throw new BusinessException("TOO_MANY_ADDRESSES", 422, "At most " + MAX_SAVED_ADDRESSES + " saved addresses");
            }
            SavedAddress saved = new SavedAddress(ids.get(), customerId, address);
            addresses.add(saved);
            return saved;
        });
    }

    public void deleteAddress(String customerId, UUID addressId) {
        if (!addresses.delete(customerId, addressId)) {
            throw new BusinessException("ADDRESS_NOT_FOUND", 404, "Unknown address");
        }
    }

    // ── used by the checkout context, inside its own transaction ──

    /** Locks the customer's active cart and prices it; fails with CART_EMPTY when there is nothing to buy. */
    public CartView lockAndPrice(String customerId) {
        Cart cart = carts.lockActive(customerId)
                .orElseThrow(() -> new BusinessException("CART_EMPTY", 422, "The cart is empty"));
        if (cart.isEmpty()) {
            throw new BusinessException("CART_EMPTY", 422, "The cart is empty");
        }
        return price(cart);
    }

    public void markCheckedOut(UUID cartId) {
        carts.markCheckedOut(cartId);
    }

    // ── internals ──

    private CartView change(String customerId, Long expectedVersion, java.util.function.Consumer<Cart> mutation) {
        return tx.inTransaction(() -> {
            Cart cart = carts.lockActive(customerId).orElseGet(() -> Cart.start(ids.get(), customerId));
            if (expectedVersion != null && expectedVersion != cart.version()) {
                throw new BusinessException("CART_VERSION_CONFLICT", 409,
                        "The cart changed in the meantime (expected version " + expectedVersion + ", current "
                                + cart.version() + ")", Map.of("currentVersion", cart.version()));
            }
            mutation.accept(cart);
            carts.save(cart);
            return price(cart);
        });
    }

    CartView price(Cart cart) {
        DeliveryAddress d = cart.delivery();
        CatalogPricing.MerchantTerms terms = cart.merchantId() == null ? null
                : catalog.terms(cart.merchantId(), d == null ? null : d.lat(), d == null ? null : d.lng());

        List<CartView.Line> lines = new ArrayList<>();
        List<CartView.Issue> issues = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        String currency = "MAD";
        int itemCount = 0;
        for (CartLine l : cart.lines()) {
            CatalogPricing.PricedLine p = catalog.reprice(l.productId(), l.optionValueIds());
            boolean available = p.available() && cart.merchantId() != null && cart.merchantId().equals(p.merchantId());
            String reason = available ? null : (p.unavailableReason() != null ? p.unavailableReason() : "OTHER_MERCHANT");
            BigDecimal lineTotal = p.unitPrice().multiply(BigDecimal.valueOf(l.quantity())).setScale(2, RoundingMode.HALF_UP);
            lines.add(new CartView.Line(l.id(), l.productId(), p.name(), p.imageUrl(), p.emoji(), l.quantity(), l.note(),
                    p.selections().stream().map(s -> new CartView.Selection(s.groupId(), s.groupName(), s.valueId(),
                            s.label(), s.priceDelta())).toList(),
                    p.unitPrice(), lineTotal, available, reason));
            if (available) {
                subtotal = subtotal.add(lineTotal);
                itemCount += l.quantity();
                currency = p.currency();
            } else {
                issues.add(new CartView.Issue("ITEM_UNAVAILABLE", null, l.id()));
            }
        }

        CartView.Delivery delivery = null;
        BigDecimal fee = BigDecimal.ZERO.setScale(2);
        if (d != null) {
            boolean priced = terms != null && terms.distanceKm() != null;
            delivery = new CartView.Delivery(d.label(), d.line(), d.city(), d.lat(), d.lng(),
                    priced ? terms.distanceKm() : null, priced ? terms.delivers() : null,
                    priced && terms.delivers() ? terms.deliveryFee() : null, priced ? terms.etaText() : null);
            if (priced && terms.delivers()) {
                fee = terms.deliveryFee();
            }
        }

        if (cart.isEmpty()) {
            issues.add(0, CartView.Issue.of("CART_EMPTY"));
        }
        if (terms != null && !terms.open()) {
            issues.add(CartView.Issue.of("MERCHANT_CLOSED"));
        }
        if (d == null) {
            issues.add(CartView.Issue.of("NO_DELIVERY_ADDRESS"));
        } else if (terms != null && !terms.delivers()) {
            issues.add(CartView.Issue.of("OUT_OF_DELIVERY_ZONE"));
        }
        if (terms != null && subtotal.compareTo(terms.minOrderAmount()) < 0) {
            issues.add(new CartView.Issue("BELOW_MINIMUM_ORDER", terms.minOrderAmount().subtract(subtotal), null));
        }
        List<String> accepted = terms == null ? List.of("CASH", "CARD")
                : java.util.stream.Stream.of(terms.acceptsCash() ? "CASH" : null, terms.acceptsCard() ? "CARD" : null)
                .filter(java.util.Objects::nonNull).toList();
        if (cart.paymentMethod() == null) {
            issues.add(CartView.Issue.of("NO_PAYMENT_METHOD"));
        } else if (!accepted.contains(cart.paymentMethod().name())) {
            issues.add(CartView.Issue.of("PAYMENT_METHOD_NOT_ACCEPTED"));
        }

        CartView.Merchant merchant = terms == null ? null
                : new CartView.Merchant(terms.id(), terms.name(), terms.emoji(), terms.logoUrl(), terms.open());
        return new CartView(cart.persisted() || cart.version() > 0 ? cart.id() : null, cart.version(), currency, merchant,
                lines, itemCount, subtotal, delivery, terms == null ? null : terms.minOrderAmount(), subtotal.add(fee),
                cart.paymentMethod() == null ? null : cart.paymentMethod().name(), accepted, issues);
    }

    private static CartView emptyView() {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return new CartView(null, 0, "MAD", null, List.of(), 0, zero, null, null, zero, null, List.of("CASH", "CARD"),
                List.of(CartView.Issue.of("CART_EMPTY"), CartView.Issue.of("NO_DELIVERY_ADDRESS"),
                        CartView.Issue.of("NO_PAYMENT_METHOD")));
    }
}
