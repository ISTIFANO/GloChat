package com.marketchat.commerce.cart.domain.model;

import com.marketchat.commerce.shared.domain.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cart aggregate (spec §5). One merchant per cart (MVP); identical lines are merged; quantities 1..50.
 * Holds no prices: prices are always computed from the catalog when the cart is read.
 */
public final class Cart {

    public static final int MAX_LINES = 30;
    public static final int MAX_QUANTITY = 50;

    private final UUID id;
    private final String customerId;
    private String merchantId;
    private long version;
    private final boolean persisted;
    private final List<CartLine> lines;
    private DeliveryAddress delivery;
    private PaymentMethod paymentMethod;

    private Cart(UUID id, String customerId, String merchantId, long version, boolean persisted, List<CartLine> lines,
                 DeliveryAddress delivery, PaymentMethod paymentMethod) {
        this.id = id;
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.version = version;
        this.persisted = persisted;
        this.lines = new ArrayList<>(lines);
        this.delivery = delivery;
        this.paymentMethod = paymentMethod;
    }

    /** A new, not yet saved cart. */
    public static Cart start(UUID id, String customerId) {
        return new Cart(id, customerId, null, 0, false, List.of(), null, null);
    }

    /** Rebuilds a saved cart (persistence adapter only). */
    public static Cart restore(UUID id, String customerId, String merchantId, long version, List<CartLine> lines,
                               DeliveryAddress delivery, PaymentMethod paymentMethod) {
        return new Cart(id, customerId, merchantId, version, true, lines, delivery, paymentMethod);
    }

    /**
     * Adds a product line, merging it with an identical line (same product, options and note).
     *
     * @param productMerchantId merchant of the product, from the catalog
     */
    public CartLine add(UUID newLineId, String productId, String productMerchantId, int quantity,
                        List<String> optionValueIds, String note) {
        checkQuantity(quantity);
        if (merchantId != null && !lines.isEmpty() && !merchantId.equals(productMerchantId)) {
            throw new BusinessException("CART_MERCHANT_CONFLICT", 409,
                    "The cart already contains items from another merchant",
                    Map.of("cartMerchantId", merchantId, "productMerchantId", productMerchantId));
        }
        String cleanNote = CartLine.cleanNote(note);
        String key = CartLine.lineKey(productId, optionValueIds, cleanNote);
        Optional<CartLine> same = lines.stream().filter(l -> l.lineKey().equals(key)).findFirst();
        if (same.isPresent()) {
            CartLine line = same.get();
            int merged = line.quantity() + quantity;
            checkQuantity(merged);
            line.changeQuantity(merged);
            return line;
        }
        if (lines.size() >= MAX_LINES) {
            throw new BusinessException("CART_TOO_MANY_LINES", 422, "A cart holds at most " + MAX_LINES + " lines");
        }
        CartLine line = CartLine.create(newLineId, productId, optionValueIds, cleanNote, quantity);
        lines.add(line);
        merchantId = productMerchantId;
        return line;
    }

    public void updateLine(UUID lineId, Integer quantity, String note) {
        CartLine line = line(lineId);
        if (quantity != null) {
            checkQuantity(quantity);
            line.changeQuantity(quantity);
        }
        if (note != null) {
            String cleanNote = CartLine.cleanNote(note);
            String newKey = CartLine.lineKey(line.productId(), line.optionValueIds(), cleanNote);
            Optional<CartLine> twin = lines.stream().filter(l -> l != line && l.lineKey().equals(newKey)).findFirst();
            if (twin.isPresent()) {
                // same product/options/note as another line: merge them
                int merged = twin.get().quantity() + line.quantity();
                checkQuantity(merged);
                twin.get().changeQuantity(merged);
                lines.remove(line);
            } else {
                line.changeNote(cleanNote, newKey);
            }
        }
    }

    public void removeLine(UUID lineId) {
        lines.remove(line(lineId));
        if (lines.isEmpty()) {
            merchantId = null;
        }
    }

    public void clear() {
        lines.clear();
        merchantId = null;
    }

    public void deliverTo(DeliveryAddress address) {
        this.delivery = address;
    }

    public void payWith(PaymentMethod method) {
        this.paymentMethod = method;
    }

    /** Called by the persistence adapter after a successful save. */
    public void savedAs(long newVersion) {
        this.version = newVersion;
    }

    private CartLine line(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException("CART_LINE_NOT_FOUND", 404, "No line " + lineId + " in the cart"));
    }

    private static void checkQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException("INVALID_QUANTITY", 422, "Quantity must be between 1 and " + MAX_QUANTITY);
        }
    }

    public UUID id() { return id; }
    public String customerId() { return customerId; }
    public String merchantId() { return merchantId; }
    public long version() { return version; }
    public boolean persisted() { return persisted; }
    public List<CartLine> lines() { return List.copyOf(lines); }
    public DeliveryAddress delivery() { return delivery; }
    public PaymentMethod paymentMethod() { return paymentMethod; }
    public boolean isEmpty() { return lines.isEmpty(); }
}
