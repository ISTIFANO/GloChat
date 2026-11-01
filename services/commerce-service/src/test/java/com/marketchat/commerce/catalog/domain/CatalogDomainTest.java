package com.marketchat.commerce.catalog.domain;

import com.marketchat.commerce.catalog.application.TextNormalizer;
import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.MerchantType;
import com.marketchat.commerce.catalog.domain.model.OpeningHours;
import com.marketchat.commerce.catalog.domain.service.DeliveryTerms;
import com.marketchat.commerce.catalog.domain.service.SearchRanking;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Pure domain rules of the catalog — no Spring, no database. */
class CatalogDomainTest {

    static final ZoneId CASA = ZoneId.of("Africa/Casablanca");

    static ZonedDateTime at(DayOfWeek day, String time) {
        // 2026-11-02 is a Monday
        return ZonedDateTime.of(2026, 11, 2 + day.getValue() - 1, LocalTime.parse(time).getHour(),
                LocalTime.parse(time).getMinute(), 0, 0, CASA);
    }

    @Nested
    class OpeningHoursRules {

        OpeningHours lunchToLate = new OpeningHours(List.of(
                new OpeningHours.Slot(DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), LocalTime.of(2, 0))));

        @Test
        void openDuringSlot() {
            assertThat(lunchToLate.isOpenAt(at(DayOfWeek.WEDNESDAY, "11:00"))).isTrue();
            assertThat(lunchToLate.isOpenAt(at(DayOfWeek.WEDNESDAY, "23:59"))).isTrue();
        }

        @Test
        void closedBeforeOpening() {
            assertThat(lunchToLate.isOpenAt(at(DayOfWeek.WEDNESDAY, "10:59"))).isFalse();
        }

        @Test
        void pastMidnightSlotCoversNextMorning() {
            assertThat(lunchToLate.isOpenAt(at(DayOfWeek.THURSDAY, "01:59"))).isTrue();
            assertThat(lunchToLate.isOpenAt(at(DayOfWeek.THURSDAY, "02:00"))).isFalse();
        }

        @Test
        void closingTimeIsExclusive() {
            OpeningHours cafe = new OpeningHours(List.of(new OpeningHours.Slot(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(20, 0))));
            assertThat(cafe.isOpenAt(at(DayOfWeek.MONDAY, "19:59"))).isTrue();
            assertThat(cafe.isOpenAt(at(DayOfWeek.MONDAY, "20:00"))).isFalse();
            assertThat(cafe.isOpenAt(at(DayOfWeek.TUESDAY, "08:00"))).isFalse(); // no slot on Tuesday
        }
    }

    @Nested
    class Delivery {

        Merchant merchant = new Merchant("m", "M", MerchantType.RESTAURANT, "🍔", List.of("burger"),
                new GeoPoint(34.0043, -6.8521), 15, 8, new BigDecimal("9"), new BigDecimal("3"), new BigDecimal("40"),
                true, true, new OpeningHours(List.of()), "", null, null);

        @Test
        void haversineDistanceWithinRabat() {
            // Agdal -> Hassan, roughly 3 km
            double d = new GeoPoint(33.9981, -6.8497).distanceKmTo(new GeoPoint(34.0222, -6.8326));
            assertThat(d).isCloseTo(3.1, within(0.3));
        }

        @Test
        void feeIsBasePlusPerKmRoundedToWholeMad() {
            assertThat(DeliveryTerms.fee(merchant, 2.4)).isEqualByComparingTo("16.00"); // 9 + 7.2 = 16.2 -> 16
            assertThat(DeliveryTerms.fee(merchant, 2.5)).isEqualByComparingTo("17.00"); // 16.5 -> 17 (half up)
        }

        @Test
        void etaIsPrepPlusFourMinutesPerKmAsRange() {
            assertThat(DeliveryTerms.etaText(merchant, 2.1)).isEqualTo("19–29 min"); // 15 + ceil(8.4)=9 -> 24
        }

        @Test
        void radiusIsInclusive() {
            assertThat(DeliveryTerms.delivers(merchant, 8.0)).isTrue();
            assertThat(DeliveryTerms.delivers(merchant, 8.01)).isFalse();
        }

        @Test
        void invalidCoordinatesRejected() {
            assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Ranking {

        @Test
        void relevanceDominates() {
            assertThat(SearchRanking.score(0.9, 80, null, 40)).isGreaterThan(SearchRanking.score(0.4, 20, null, 10));
        }

        @Test
        void cheaperWinsWhenEquallyRelevantUnderBudget() {
            assertThat(SearchRanking.score(0.8, 40, 70.0, 25)).isGreaterThan(SearchRanking.score(0.8, 65, 70.0, 25));
        }
    }

    @Nested
    class Normalization {

        @Test
        void lowercasesStripsAccentsAndFillerWords() {
            assertThat(TextNormalizer.terms("Je veux un Gâteau SUCRÉ pour 2 !")).containsExactly("gateau", "sucre");
        }

        @Test
        void keepsArabicWords() {
            assertThat(TextNormalizer.terms("بغيت برغر")).containsExactly("برغر");
        }
    }
}
