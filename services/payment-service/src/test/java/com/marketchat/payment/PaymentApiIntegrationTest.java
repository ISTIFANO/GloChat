package com.marketchat.payment;

import com.marketchat.payment.domain.port.PaymentProvider;
import com.marketchat.payment.support.TestcontainersConfiguration;
import com.stripe.Stripe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inc 4 exit criteria (spec §12): forged signature rejected, amount mismatch rejected, duplicate webhook processed
 * once, expiry handled, server-to-server sync, internal API only with scope "internal". Stripe itself is faked here;
 * StripeLiveTest exercises the real Stripe test-mode API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentApiIntegrationTest.FakeStripe.class})
class PaymentApiIntegrationTest {

    static final String WEBHOOK_SECRET = "whsec_test_marketchat_only_for_tests";

    /** Fake hosted checkout: remembers sessions and what "Stripe" reports for them. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStripe {
        @Bean
        @Primary
        FakeProvider fakeProvider() {
            return new FakeProvider();
        }
    }

    static class FakeProvider implements PaymentProvider {
        final Map<String, SessionState> states = new ConcurrentHashMap<>();
        final List<CheckoutRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public CheckoutSession createCheckout(CheckoutRequest r) {
            requests.add(r);
            String id = "cs_test_" + UUID.randomUUID().toString().replace("-", "");
            states.put(id, new SessionState(id, "open", "unpaid", r.amountMinor(), r.currency().toLowerCase(), null));
            return new CheckoutSession(id, "https://checkout.stripe.com/c/pay/" + id);
        }

        @Override
        public SessionState retrieve(String sessionId) {
            return states.get(sessionId);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeProvider stripe;

    UUID orderId;

    @BeforeEach
    void newOrder() {
        orderId = UUID.randomUUID();
    }

    ResultActions createSession(UUID order, String amount) throws Exception {
        return mvc.perform(post("/internal/payments/sessions")
                .with(jwt().jwt(j -> j.claim("scope", "internal").claim("azp", "commerce-service")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\",\"orderNumber\":\"MC-26-000042\",\"amount\":" + amount
                        + ",\"currency\":\"MAD\",\"successUrl\":\"http://localhost:4200/orders/MC-26-000042?payment=success\","
                        + "\"cancelUrl\":\"http://localhost:4200/orders/MC-26-000042?payment=cancel\"}"));
    }

    String sessionOf(UUID order) {
        return jdbc.sql("SELECT session_id FROM payments WHERE order_id = :o").param("o", order).query(String.class).single();
    }

    String paymentStatus(UUID order) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = :o").param("o", order).query(String.class).single();
    }

    List<String> events(UUID order) {
        return jdbc.sql("SELECT type FROM outbox_events WHERE aggregate_id = :o ORDER BY occurred_at")
                .param("o", order.toString()).query(String.class).list();
    }

    static String event(String eventId, String type, String sessionId, long amountMinor, String currency,
                        String paymentStatus, String status) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION
                + "\",\"created\":" + Instant.now().getEpochSecond() + ",\"livemode\":false,\"type\":\"" + type
                + "\",\"data\":{\"object\":{\"id\":\"" + sessionId + "\",\"object\":\"checkout.session\",\"amount_total\":"
                + amountMinor + ",\"currency\":\"" + currency + "\",\"payment_status\":\"" + paymentStatus
                + "\",\"status\":\"" + status + "\",\"payment_intent\":\"pi_test_42\"}}}";
    }

    static String sign(String payload, String secret, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }

    ResultActions webhook(String payload, String signature) throws Exception {
        return mvc.perform(post("/api/payments/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", signature).content(payload));
    }

    ResultActions signedWebhook(String payload) throws Exception {
        return webhook(payload, sign(payload, WEBHOOK_SECRET, Instant.now().getEpochSecond()));
    }

    @Test
    void internalApiNeedsTheInternalScope() throws Exception {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        mvc.perform(post("/internal/payments/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/payments/sync").with(jwt().jwt(j -> j.subject("user-1").claim("scope", "profile email")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsAHostedCheckoutForTheOrderTotal() throws Exception {
        createSession(orderId, "171.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl", startsWith("https://checkout.stripe.com/")))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(stripe.requests).anySatisfy(r -> {
            assertThat(r.orderId()).isEqualTo(orderId);
            assertThat(r.amountMinor()).isEqualTo(17100);
            assertThat(r.currency()).isEqualTo("MAD");
        });
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
    }

    @Test
    void sessionCreationIsIdempotentPerOrder() throws Exception {
        String first = (String) com.jayway.jsonpath.JsonPath.read(
                createSession(orderId, "171.00").andReturn().getResponse().getContentAsString(), "$.sessionId");
        String second = (String) com.jayway.jsonpath.JsonPath.read(
                createSession(orderId, "171.00").andReturn().getResponse().getContentAsString(), "$.sessionId");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void invalidAmountsAreRejected() throws Exception {
        createSession(orderId, "0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        createSession(orderId, "10.001").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void paidWebhookSucceedsAndPublishesPaymentSucceeded() throws Exception {
        createSession(orderId, "171.00");
        String session = sessionOf(orderId);
        signedWebhook(event("evt_" + UUID.randomUUID(), "checkout.session.completed", session, 17100, "mad", "paid", "complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("processed"));
        assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
        assertThat(events(orderId)).containsExactly("PaymentSucceeded");
        String envelope = jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = :o")
                .param("o", orderId.toString()).query(String.class).single();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(envelope, "$.payload.amount")).isEqualTo("171.00");
        assertThat((String) com.jayway.jsonpath.JsonPath.read(envelope, "$.payload.providerRef")).isEqualTo("pi_test_42");
        // a paid order cannot get a new session
        createSession(orderId, "171.00").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
    }

    @Test
    void duplicateWebhookIsProcessedOnce() throws Exception {
        createSession(orderId, "171.00");
        String payload = event("evt_" + UUID.randomUUID(), "checkout.session.completed", sessionOf(orderId), 17100,
                "mad", "paid", "complete");
        signedWebhook(payload).andExpect(jsonPath("$.result").value("processed"));
        signedWebhook(payload).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("duplicate"));
        assertThat(events(orderId)).containsExactly("PaymentSucceeded");
    }

    @Test
    void forgedOrReplayedSignaturesAreRejected() throws Exception {
        createSession(orderId, "171.00");
        String payload = event("evt_" + UUID.randomUUID(), "checkout.session.completed", sessionOf(orderId), 17100,
                "mad", "paid", "complete");
        webhook(payload, sign(payload, "whsec_attacker_secret", Instant.now().getEpochSecond()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        webhook(payload, sign(payload, WEBHOOK_SECRET, Instant.now().minusSeconds(3600).getEpochSecond()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        String tampered = payload.replace("17100", "100");
        webhook(tampered, sign(payload, WEBHOOK_SECRET, Instant.now().getEpochSecond()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        webhook(payload, null).andExpect(status().isBadRequest());
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(events(orderId)).isEmpty();
    }

    @Test
    void amountOrCurrencyMismatchNeverSucceeds() throws Exception {
        createSession(orderId, "171.00");
        signedWebhook(event("evt_" + UUID.randomUUID(), "checkout.session.completed", sessionOf(orderId), 100, "mad",
                "paid", "complete"));
        assertThat(paymentStatus(orderId)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(events(orderId)).containsExactly("PaymentFailed");

        UUID other = UUID.randomUUID();
        createSession(other, "171.00");
        signedWebhook(event("evt_" + UUID.randomUUID(), "checkout.session.completed", sessionOf(other), 17100, "usd",
                "paid", "complete"));
        assertThat(paymentStatus(other)).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void expiredSessionPublishesPaymentExpired() throws Exception {
        createSession(orderId, "171.00");
        signedWebhook(event("evt_" + UUID.randomUUID(), "checkout.session.expired", sessionOf(orderId), 17100, "mad",
                "unpaid", "expired"));
        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(events(orderId)).containsExactly("PaymentExpired");
    }

    @Test
    void unrelatedEventsAreAcknowledgedAndIgnored() throws Exception {
        String payload = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION
                + "\",\"type\":\"customer.created\",\"data\":{\"object\":{\"id\":\"cus_1\",\"object\":\"customer\"}}}";
        signedWebhook(payload).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ignored"));
    }

    @Test
    void syncAsksTheProviderServerToServer() throws Exception {
        createSession(orderId, "171.00");
        String session = sessionOf(orderId);
        ResultActions pending = mvc.perform(post("/internal/payments/sync")
                .with(jwt().jwt(j -> j.claim("scope", "internal"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}"));
        pending.andExpect(jsonPath("$.status").value("PENDING"));

        stripe.states.put(session, new PaymentProvider.SessionState(session, "complete", "paid", 17100L, "mad", "pi_sync"));
        for (int i = 0; i < 2; i++) {   // the second sync must not publish twice
            mvc.perform(post("/internal/payments/sync").with(jwt().jwt(j -> j.claim("scope", "internal")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + orderId + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.amount").value(171.0))
                    .andExpect(jsonPath("$.providerRef").value("pi_sync"));
        }
        assertThat(events(orderId)).containsExactly("PaymentSucceeded");

        mvc.perform(post("/internal/payments/sync").with(jwt().jwt(j -> j.claim("scope", "internal")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
