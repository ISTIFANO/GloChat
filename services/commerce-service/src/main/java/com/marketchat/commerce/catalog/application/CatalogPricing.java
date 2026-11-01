package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.Product;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;
import com.marketchat.commerce.catalog.domain.service.DeliveryTerms;
import com.marketchat.commerce.catalog.domain.service.ProductConfiguration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public catalog API for the cart and checkout contexts: current prices, product configuration rules and
 * merchant delivery terms. Other contexts never read catalog tables directly (ArchUnit-enforced).
 */
public class CatalogPricing {

    public record PricedSelection(String groupId, String groupName, String valueId, String label, BigDecimal priceDelta) {
    }

    /** A product line priced right now. {@code available} is false when the product or an option can't be ordered. */
    public record PricedLine(String productId, String merchantId, String name, String imageUrl, String emoji,
                             BigDecimal unitPrice, String currency, List<PricedSelection> selections,
                             boolean available, String unavailableReason) {
    }

    /** Delivery terms for a merchant; distance-dependent fields are null when no location is given. */
    public record MerchantTerms(String id, String name, String emoji, String logoUrl, boolean open,
                                boolean acceptsCash, boolean acceptsCard, BigDecimal minOrderAmount,
                                Double distanceKm, Boolean delivers, BigDecimal deliveryFee, String etaText,
                                Integer etaMinutes) {
    }

    private final CatalogRepository repository;
    private final Clock clock;

    public CatalogPricing(CatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Strict pricing for adding a line: unknown product, invalid configuration or out of stock are errors. */
    public PricedLine priceForAdd(String productId, List<String> valueIds) {
        Product p = repository.findProduct(productId)
                .orElseThrow(() -> new CatalogException("PRODUCT_NOT_FOUND", 404, "Unknown product " + productId));
        List<ProductConfiguration.Violation> violations = ProductConfiguration.validate(p, valueIds);
        if (!violations.isEmpty()) {
            throw new ConfigurationException(violations);
        }
        if (!p.inStock()) {
            throw new CatalogException("PRODUCT_UNAVAILABLE", 409, p.name() + " is out of stock");
        }
        return toLine(p, valueIds, true, null);
    }

    /** Lenient re-pricing for a cart view: problems mark the line unavailable instead of failing. */
    public PricedLine reprice(String productId, List<String> valueIds) {
        Optional<Product> found = repository.findProduct(productId);
        if (found.isEmpty()) {
            return new PricedLine(productId, null, null, null, null, BigDecimal.ZERO.setScale(2), "MAD", List.of(),
                    false, "PRODUCT_REMOVED");
        }
        Product p = found.get();
        if (!p.inStock()) {
            return toLine(p, valueIds, false, "OUT_OF_STOCK");
        }
        List<ProductConfiguration.Violation> violations = ProductConfiguration.validate(p, valueIds);
        if (!violations.isEmpty()) {
            return toLine(p, valueIds, false, violations.get(0).code());
        }
        return toLine(p, valueIds, true, null);
    }

    public MerchantTerms terms(String merchantId, Double lat, Double lng) {
        Merchant m = repository.findMerchant(merchantId)
                .orElseThrow(() -> new CatalogException("MERCHANT_NOT_FOUND", 404, "Unknown merchant " + merchantId));
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(SearchCatalogUseCase.MERCHANT_ZONE);
        boolean open = m.hours().isOpenAt(now);
        if (lat == null || lng == null) {
            return new MerchantTerms(m.id(), m.name(), m.emoji(), m.logoUrl(), open, m.acceptsCash(), m.acceptsCard(),
                    m.minOrderAmount(), null, null, null, null, null);
        }
        double distance = ProductCards.round1(m.location().distanceKmTo(new GeoPoint(lat, lng)));
        return new MerchantTerms(m.id(), m.name(), m.emoji(), m.logoUrl(), open, m.acceptsCash(), m.acceptsCard(),
                m.minOrderAmount(), distance, DeliveryTerms.delivers(m, distance), DeliveryTerms.fee(m, distance),
                DeliveryTerms.etaText(m, distance), DeliveryTerms.etaMinutes(m, distance));
    }

    private static PricedLine toLine(Product p, List<String> valueIds, boolean available, String reason) {
        List<PricedSelection> selections = ProductConfiguration.resolve(p, valueIds).stream()
                .map(s -> new PricedSelection(s.group().id(), s.group().name(), s.value().id(), s.value().label(),
                        s.value().priceDelta()))
                .toList();
        return new PricedLine(p.id(), p.merchantId(), p.name(), p.imageUrl(), p.visual().emoji(),
                ProductConfiguration.unitPrice(p, valueIds), p.currency(), selections, available, reason);
    }

    /** Invalid product configuration (422), listing every violation with its group name. */
    public static class ConfigurationException extends CatalogException {
        private final List<ProductConfiguration.Violation> violations;

        ConfigurationException(List<ProductConfiguration.Violation> all) {
            super(all.get(0).code(), 422, all.get(0).groupName() == null
                    ? "Option " + all.get(0).valueId() + " is not valid for this product"
                    : all.get(0).code() + " for option group " + all.get(0).groupName());
            this.violations = List.copyOf(all);
        }

        @Override
        public Map<String, Object> details() {
            return Map.of("violations", violations.stream()
                    .map(v -> Map.of("code", v.code(), "group", v.groupName() == null ? "" : v.groupName()))
                    .toList());
        }
    }
}
