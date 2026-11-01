package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.Product;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;

import java.time.Clock;
import java.time.ZonedDateTime;

/** get_product (spec §8.2): the real options and current price of one product. */
public class GetProductUseCase {

    private final CatalogRepository repository;
    private final Clock clock;

    public GetProductUseCase(CatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ProductDetailResult get(String productId, GeoPoint location) {
        Product p = repository.findProduct(productId)
                .orElseThrow(() -> new CatalogException("PRODUCT_NOT_FOUND", 404, "Unknown product " + productId));
        Merchant m = repository.findMerchant(p.merchantId())
                .orElseThrow(() -> new CatalogException("PRODUCT_NOT_FOUND", 404, "Unknown product " + productId));
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(SearchCatalogUseCase.MERCHANT_ZONE);
        GeoPoint from = location != null ? location : m.location();
        return new ProductDetailResult(ProductCards.of(p, m, from, now), p.description(), p.optionGroups());
    }
}
