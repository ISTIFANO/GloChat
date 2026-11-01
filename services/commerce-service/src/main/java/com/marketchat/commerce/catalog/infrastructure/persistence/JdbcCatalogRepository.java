package com.marketchat.commerce.catalog.infrastructure.persistence;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.MerchantType;
import com.marketchat.commerce.catalog.domain.model.OpeningHours;
import com.marketchat.commerce.catalog.domain.model.OptionGroup;
import com.marketchat.commerce.catalog.domain.model.OptionValue;
import com.marketchat.commerce.catalog.domain.model.Product;
import com.marketchat.commerce.catalog.domain.model.ProductVisual;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Catalog persistence adapter (plain SQL via JdbcClient; pg_trgm + unaccent for fuzzy search). */
@Repository
class JdbcCatalogRepository implements CatalogRepository {

    private static final double MIN_TEXT_SCORE = 0.3;

    private static final String SEARCH_SQL = """
            WITH scored AS (
              SELECT p.id AS product_id,
                     greatest(
                       similarity(unaccent(lower(p.name)), :q),
                       word_similarity(:q, unaccent(lower(p.name))),
                       CASE WHEN p.tags && CAST(:terms AS text[]) THEN 0.9 ELSE 0 END,
                       CASE WHEN m.cuisine_tags && CAST(:terms AS text[]) THEN 0.75 ELSE 0 END,
                       word_similarity(:q, unaccent(lower(p.description))) * 0.6
                     ) AS text_score
                FROM products p
                JOIN merchants m ON m.id = p.merchant_id
               WHERE p.active AND m.active AND p.in_stock
                 AND (p.stock_qty IS NULL OR p.stock_qty > 0)
                 AND (CAST(:maxPrice AS numeric) IS NULL OR p.base_price <= CAST(:maxPrice AS numeric))
            )
            SELECT product_id, text_score FROM scored
             WHERE text_score >= :minScore
             ORDER BY text_score DESC, product_id
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    JdbcCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candidate> searchCandidates(String normalizedQuery, List<String> terms, BigDecimal maxPrice, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        jdbc.sql(SEARCH_SQL)
                .param("q", normalizedQuery)
                .param("terms", terms.toArray(String[]::new))
                .param("maxPrice", maxPrice)
                .param("minScore", MIN_TEXT_SCORE)
                .param("limit", limit)
                .query((rs, i) -> scores.put(rs.getString("product_id"), rs.getDouble("text_score")))
                .list();
        if (scores.isEmpty()) {
            return List.of();
        }
        Map<String, Product> products = loadProducts(scores.keySet());
        Map<String, Merchant> merchants = loadMerchants(products.values().stream().map(Product::merchantId).distinct().toList());
        return scores.entrySet().stream()
                .filter(e -> products.containsKey(e.getKey()))
                .map(e -> {
                    Product p = products.get(e.getKey());
                    return new Candidate(p, merchants.get(p.merchantId()), e.getValue());
                })
                .toList();
    }

    @Override
    public Optional<Product> findProduct(String productId) {
        return Optional.ofNullable(loadProducts(List.of(productId)).get(productId));
    }

    @Override
    public Optional<Merchant> findMerchant(String merchantId) {
        return Optional.ofNullable(loadMerchants(List.of(merchantId)).get(merchantId));
    }

    // ------------------------------------------------------------------ loading

    private Map<String, Product> loadProducts(Collection<String> ids) {
        String[] idArray = ids.toArray(String[]::new);
        record ValueRow(String groupId, OptionValue value) {
        }
        List<ValueRow> values = jdbc.sql("""
                        SELECT v.group_id, v.id, v.label, v.price_delta, v.available
                          FROM option_values v JOIN option_groups g ON g.id = v.group_id
                         WHERE g.product_id = ANY(:ids)
                         ORDER BY v.position, v.id""")
                .param("ids", idArray)
                .query((rs, i) -> new ValueRow(rs.getString("group_id"),
                        new OptionValue(rs.getString("id"), rs.getString("label"), rs.getBigDecimal("price_delta"), rs.getBoolean("available"))))
                .list();
        Map<String, List<OptionValue>> valuesByGroup = values.stream()
                .collect(Collectors.groupingBy(ValueRow::groupId, LinkedHashMap::new, Collectors.mapping(ValueRow::value, Collectors.toList())));

        record GroupRow(String productId, OptionGroup group) {
        }
        List<GroupRow> groups = jdbc.sql("""
                        SELECT product_id, id, name, type, min_select, max_select, required
                          FROM option_groups WHERE product_id = ANY(:ids) ORDER BY position, id""")
                .param("ids", idArray)
                .query((rs, i) -> new GroupRow(rs.getString("product_id"), new OptionGroup(
                        rs.getString("id"), rs.getString("name"), OptionGroup.Type.valueOf(rs.getString("type")),
                        rs.getInt("min_select"), rs.getInt("max_select"), rs.getBoolean("required"),
                        valuesByGroup.getOrDefault(rs.getString("id"), List.of()))))
                .list();
        Map<String, List<OptionGroup>> groupsByProduct = groups.stream()
                .collect(Collectors.groupingBy(GroupRow::productId, Collectors.mapping(GroupRow::group, Collectors.toList())));

        return jdbc.sql("""
                        SELECT id, merchant_id, name, description, base_price, currency, visual_emoji, visual_from, visual_to,
                               tags, in_stock, serves
                          FROM products WHERE id = ANY(:ids) AND active""")
                .param("ids", idArray)
                .query((rs, i) -> new Product(
                        rs.getString("id"), rs.getString("merchant_id"), rs.getString("name"), rs.getString("description"),
                        rs.getBigDecimal("base_price"), rs.getString("currency").trim(),
                        new ProductVisual(rs.getString("visual_emoji"), rs.getString("visual_from"), rs.getString("visual_to")),
                        strings(rs.getArray("tags")), rs.getBoolean("in_stock"), rs.getInt("serves"),
                        groupsByProduct.getOrDefault(rs.getString("id"), List.of())))
                .list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
    }

    private Map<String, Merchant> loadMerchants(Collection<String> ids) {
        String[] idArray = ids.toArray(String[]::new);
        Map<String, List<OpeningHours.Slot>> hours = new LinkedHashMap<>();
        jdbc.sql("SELECT merchant_id, day_of_week, opens_at, closes_at FROM merchant_hours WHERE merchant_id = ANY(:ids)")
                .param("ids", idArray)
                .query((rs, i) -> hours.computeIfAbsent(rs.getString("merchant_id"), k -> new ArrayList<>()).add(
                        new OpeningHours.Slot(DayOfWeek.of(rs.getInt("day_of_week")),
                                rs.getTime("opens_at").toLocalTime(), rs.getTime("closes_at").toLocalTime())))
                .list();
        return jdbc.sql("""
                        SELECT id, name, type, emoji, cuisine_tags, lat, lng, avg_prep_minutes, delivery_radius_km,
                               base_delivery_fee, fee_per_km, min_order_amount, accepts_cash, accepts_card
                          FROM merchants WHERE id = ANY(:ids) AND active""")
                .param("ids", idArray)
                .query((rs, i) -> new Merchant(
                        rs.getString("id"), rs.getString("name"), MerchantType.valueOf(rs.getString("type")), rs.getString("emoji"),
                        strings(rs.getArray("cuisine_tags")), new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")),
                        rs.getInt("avg_prep_minutes"), rs.getDouble("delivery_radius_km"),
                        rs.getBigDecimal("base_delivery_fee"), rs.getBigDecimal("fee_per_km"), rs.getBigDecimal("min_order_amount"),
                        rs.getBoolean("accepts_cash"), rs.getBoolean("accepts_card"),
                        new OpeningHours(hours.getOrDefault(rs.getString("id"), List.of()))))
                .list().stream()
                .collect(Collectors.toMap(Merchant::id, Function.identity()));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
