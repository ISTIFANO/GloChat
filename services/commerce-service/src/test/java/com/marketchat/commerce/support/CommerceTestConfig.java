package com.marketchat.commerce.support;

import com.marketchat.commerce.orders.domain.port.PaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared test doubles for the cart / checkout / orders integration tests (one Spring context, one PostgreSQL):
 * a controllable clock and a fake payment-service.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CommerceTestConfig {

    /** Wednesday 2026-11-04 20:00 in Casablanca: every demo merchant is open. */
    public static final String OPEN_TIME = "2026-11-04T20:00:00";

    public static class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock() {
            set(OPEN_TIME);
        }

        public void set(String localDateTime) {
            now = ZonedDateTime.parse(localDateTime + "+01:00[Africa/Casablanca]").toInstant();
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Stands in for payment-service (internal HTTP API). */
    public static class FakePaymentGateway implements PaymentGateway {
        public volatile boolean down;
        public volatile State nextState;
        public final List<SessionRequest> sessions = new ArrayList<>();

        public void reset() {
            down = false;
            nextState = null;
            sessions.clear();
        }

        @Override
        public synchronized Session createSession(SessionRequest request) {
            if (down) {
                throw new com.marketchat.commerce.shared.domain.BusinessException("PAYMENT_UNAVAILABLE", 503, "down");
            }
            sessions.add(request);
            String id = "cs_test_" + UUID.randomUUID().toString().replace("-", "");
            return new Session(id, "https://checkout.stripe.test/c/pay/" + id);
        }

        @Override
        public State sync(UUID orderId) {
            if (down) {
                throw new com.marketchat.commerce.shared.domain.BusinessException("PAYMENT_UNAVAILABLE", 503, "down");
            }
            return nextState != null ? nextState : new State("PENDING", null, null, null);
        }

        public static State succeeded(BigDecimal amount) {
            return new State("SUCCEEDED", amount, "MAD", "pi_test_123");
        }
    }

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}
