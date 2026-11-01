package com.marketchat.commerce.catalog.domain.port;

import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Output port: implemented by the persistence adapter. */
public interface CatalogRepository {

    /** A product matching the text search, with its merchant and a 0..1 relevance score. */
    record Candidate(Product product, Merchant merchant, double textScore) {
    }

    /**
     * Active, in-stock products of active merchants matching the normalized search terms,
     * optionally under a maximum base price, best text matches first.
     */
    List<Candidate> searchCandidates(String normalizedQuery, List<String> terms, BigDecimal maxPrice, int limit);

    Optional<Product> findProduct(String productId);

    Optional<Merchant> findMerchant(String merchantId);
}
