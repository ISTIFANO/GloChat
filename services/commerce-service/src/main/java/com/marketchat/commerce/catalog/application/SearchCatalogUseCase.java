package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;
import com.marketchat.commerce.catalog.domain.service.DeliveryTerms;
import com.marketchat.commerce.catalog.domain.service.SearchRanking;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * search_catalog (spec §3.2 / §8.1): text match in the database, then delivery feasibility, opening hours
 * and deterministic ranking. Only open merchants that deliver to the location are returned.
 */
public class SearchCatalogUseCase {

    static final ZoneId MERCHANT_ZONE = ZoneId.of("Africa/Casablanca");
    private static final int CANDIDATES = 60;

    private final CatalogRepository repository;
    private final Clock clock;

    public SearchCatalogUseCase(CatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<ProductCardResult> search(SearchCatalogQuery q) {
        if (q.location() == null) {
            throw new CatalogException("LOCATION_REQUIRED", 400, "A delivery location is required to search");
        }
        List<String> terms = TextNormalizer.terms(q.text());
        if (terms.isEmpty()) {
            throw new CatalogException("INVALID_ARGUMENT", 400, "The search text has no meaningful words");
        }
        int limit = Math.max(1, Math.min(20, q.limit()));
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(MERCHANT_ZONE);
        Double maxPrice = q.maxPrice() == null ? null : q.maxPrice().doubleValue();

        record Ranked(ProductCardResult card, double score) {
        }

        return repository.searchCandidates(String.join(" ", terms), terms, q.maxPrice(), CANDIDATES).stream()
                .filter(c -> {
                    Merchant m = c.merchant();
                    double d = m.location().distanceKmTo(q.location());
                    return DeliveryTerms.delivers(m, d) && m.hours().isOpenAt(now);
                })
                .map(c -> {
                    ProductCardResult card = ProductCards.of(c.product(), c.merchant(), q.location(), now);
                    double text = c.textScore();
                    if (q.partySize() != null && q.partySize() >= 2 && c.product().serves() >= q.partySize()) {
                        text = Math.min(1, text + 0.3);
                    }
                    double s = SearchRanking.score(text, card.price().doubleValue(), maxPrice,
                            DeliveryTerms.etaMinutes(c.merchant(), card.distanceKm()));
                    return new Ranked(card, s);
                })
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .limit(limit)
                .map(Ranked::card)
                .toList();
    }
}
