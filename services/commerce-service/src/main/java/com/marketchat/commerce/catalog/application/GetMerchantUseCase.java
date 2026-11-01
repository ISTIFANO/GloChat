package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;
import com.marketchat.commerce.catalog.domain.service.DeliveryTerms;

import java.time.ZonedDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Merchant page: branding, opening status, delivery terms and the menu grouped by category. */
public class GetMerchantUseCase {

    static final String UNCATEGORIZED = "Autres";

    private final CatalogRepository repository;
    private final Clock clock;

    public GetMerchantUseCase(CatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public MerchantPageResult get(String merchantId, GeoPoint location) {
        Merchant m = repository.findMerchant(merchantId)
                .orElseThrow(() -> new CatalogException("MERCHANT_NOT_FOUND", 404, "Unknown merchant " + merchantId));
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(SearchCatalogUseCase.MERCHANT_ZONE);
        GeoPoint from = location != null ? location : m.location();
        double distance = ProductCards.round1(m.location().distanceKmTo(from));

        String todayHours = m.hours().slotsOn(now.getDayOfWeek()).stream()
                .map(s -> s.opens() + "–" + s.closes())
                .collect(Collectors.joining(", "));

        MerchantPageResult.Info info = new MerchantPageResult.Info(
                m.id(), m.name(), m.emoji(), m.description(), m.logoUrl(), m.coverUrl(), m.cuisineTags(),
                m.hours().isOpenAt(now), todayHours.isEmpty() ? null : todayHours,
                distance, DeliveryTerms.etaText(m, distance), DeliveryTerms.fee(m, distance), m.minOrderAmount(),
                DeliveryTerms.delivers(m, distance), m.acceptsCash(), m.acceptsCard());

        Map<String, List<ProductCardResult>> byCategory = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (CatalogRepository.MenuEntry e : repository.findMenu(merchantId)) {
            String key = e.categoryId() == null ? "_uncategorized" : e.categoryId();
            names.putIfAbsent(key, e.categoryName() == null ? UNCATEGORIZED : e.categoryName());
            byCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(ProductCards.of(e.product(), m, from, now));
        }
        List<MerchantPageResult.Section> sections = byCategory.entrySet().stream()
                .map(en -> new MerchantPageResult.Section(en.getKey(), names.get(en.getKey()), en.getValue()))
                .toList();
        return new MerchantPageResult(info, sections);
    }
}
