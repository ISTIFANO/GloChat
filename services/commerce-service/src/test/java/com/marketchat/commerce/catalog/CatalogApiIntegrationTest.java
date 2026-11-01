package com.marketchat.commerce.catalog;

import com.marketchat.commerce.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inc 1 exit criteria: real SQL search on the demo seed (Flyway on a real PostgreSQL), with a controllable clock.
 * Public endpoints — no token needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, CatalogApiIntegrationTest.TestClock.class})
class CatalogApiIntegrationTest {

    static final String AGDAL = "{\"lat\":33.9981,\"lng\":-6.8497}";
    static final String CASABLANCA = "{\"lat\":33.5731,\"lng\":-7.5898}";

    /** Mutable clock: each test sets the local time it needs. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-04T19:00:00Z"); // Wednesday 20:00 in Casablanca (UTC+1)

        void set(String localDateTime) {
            now = ZonedDateTime.parse(localDateTime + "+01:00[Africa/Casablanca]").toInstant();
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    ResultActions search(String json) throws Exception {
        return mvc.perform(post("/api/catalog/search").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void findsBurgersNearAgdal() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"je veux un burger\",\"location\":" + AGDAL + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].productId", hasItem("p-double-smash")))
                .andExpect(jsonPath("$.results[0].merchant.name").value("Smash Lab Agdal"))
                .andExpect(jsonPath("$.results[0].deliveryFee").exists())
                .andExpect(jsonPath("$.results[0].etaText").exists())
                .andExpect(jsonPath("$.results[*].merchantOpen", everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    void respectsBudget() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"burger\",\"maxPrice\":50,\"location\":" + AGDAL + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].price", everyItem(lessThanOrEqualTo(50.0))))
                .andExpect(jsonPath("$.results[*].productId", not(hasItem("p-double-smash"))));
    }

    @Test
    void toleratesTypos() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"burgr\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[*].productId", hasItem("p-classic-cheese")));
    }

    @Test
    void understandsArabic() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"بغيت برغر\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[*].productId", hasItem("p-double-smash")));
    }

    @Test
    void partySizeBoostsSharingPlates() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"sushi\",\"partySize\":2,\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[0].productId").value("p-sushi-duo"));
    }

    @Test
    void closedMerchantsAreExcluded() throws Exception {
        clock.set("2026-11-04T21:00:00"); // Café Atlas closes at 20:00
        search("{\"query\":\"cafe latte\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[*].productId", not(hasItem("p-latte"))));
        clock.set("2026-11-05T08:00:00");
        search("{\"query\":\"cafe latte\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[*].productId", hasItem("p-latte")));
    }

    @Test
    void lateNightOpeningPastMidnight() throws Exception {
        clock.set("2026-11-05T01:00:00"); // Thursday 01:00: Smash (11:00-02:00) open, Sakura (until 23:30) closed
        search("{\"query\":\"burger\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results[*].productId", hasItem("p-double-smash")));
        search("{\"query\":\"sushi\",\"location\":" + AGDAL + "}")
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void outOfDeliveryRangeReturnsNothing() throws Exception {
        clock.set("2026-11-04T20:00:00");
        search("{\"query\":\"burger\",\"location\":" + CASABLANCA + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void missingLocationIsRejected() throws Exception {
        search("{\"query\":\"burger\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void productDetailHasRealOptions() throws Exception {
        clock.set("2026-11-04T20:00:00");
        mvc.perform(get("/api/catalog/products/p-double-smash").param("lat", "33.9981").param("lng", "-6.8497"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.needsConfiguration").value(true))
                .andExpect(jsonPath("$.optionGroups", hasSize(4)))
                .andExpect(jsonPath("$.optionGroups[0].name").value("Taille"))
                .andExpect(jsonPath("$.optionGroups[0].required").value(true))
                .andExpect(jsonPath("$.optionGroups[2].type").value("MULTI"))
                .andExpect(jsonPath("$.optionGroups[2].maxSelect").value(3));
    }

    @Test
    void unknownProductIs404WithCode() throws Exception {
        mvc.perform(get("/api/catalog/products/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }
}
