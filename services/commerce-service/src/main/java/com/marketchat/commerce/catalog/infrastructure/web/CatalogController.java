package com.marketchat.commerce.catalog.infrastructure.web;

import com.marketchat.commerce.catalog.application.GetProductUseCase;
import com.marketchat.commerce.catalog.application.ProductCardResult;
import com.marketchat.commerce.catalog.application.ProductDetailResult;
import com.marketchat.commerce.catalog.application.SearchCatalogQuery;
import com.marketchat.commerce.catalog.application.SearchCatalogUseCase;
import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Public catalog endpoints (no login needed), routed by the gateway under /api/catalog/**. */
@RestController
@RequestMapping("/api/catalog")
class CatalogController {

    private final SearchCatalogUseCase search;
    private final GetProductUseCase getProduct;

    CatalogController(SearchCatalogUseCase search, GetProductUseCase getProduct) {
        this.search = search;
        this.getProduct = getProduct;
    }

    record Location(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    record SearchRequest(@NotBlank @Size(max = 120) String query,
                         @NotNull @Valid Location location,
                         @DecimalMin("0") BigDecimal maxPrice,
                         @Min(1) @Max(20) Integer partySize,
                         @Min(1) @Max(20) Integer limit) {
    }

    record SearchResponse(List<ProductCardResult> results) {
    }

    @PostMapping("/search")
    SearchResponse search(@Valid @RequestBody SearchRequest r) {
        return new SearchResponse(search.search(new SearchCatalogQuery(
                r.query(), new GeoPoint(r.location().lat(), r.location().lng()), r.maxPrice(), r.partySize(),
                r.limit() == null ? 6 : r.limit())));
    }

    @GetMapping("/products/{productId}")
    ProductDetailResult product(@PathVariable @Size(max = 64) String productId,
                                @RequestParam(required = false) Double lat,
                                @RequestParam(required = false) Double lng) {
        GeoPoint location = lat != null && lng != null ? new GeoPoint(lat, lng) : null;
        return getProduct.get(productId, location);
    }
}
