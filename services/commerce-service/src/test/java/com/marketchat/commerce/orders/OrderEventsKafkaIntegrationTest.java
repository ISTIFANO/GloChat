package com.marketchat.commerce.orders;

import com.jayway.jsonpath.JsonPath;
import com.marketchat.commerce.support.ApiTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Kafka (same image as docker-compose): the outbox relay publishes order events, and a PaymentSucceeded
 * event on payment.events moves a card order to PLACED. The consumer is idempotent.
 */
@TestPropertySource(properties = {
        "marketchat.outbox.relay.enabled=true",
        "marketchat.outbox.relay.interval-ms=200",
        "marketchat.kafka.listeners.enabled=true"})
class OrderEventsKafkaIntegrationTest extends ApiTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConfig {
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.2");
        }
    }

    @Autowired
    KafkaContainer kafka;

    @Autowired
    KafkaTemplate<String, String> template;

    @Test
    void orderEventsArePublishedThroughTheOutbox() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        ResultActions r = postAs(me, "/api/checkout/quote", null);
        String number = read(postAs(me, "/api/checkout/confirm", "{\"quoteId\":\"" + read(r, "$.quoteId")
                + "\",\"confirmationToken\":\"" + read(r, "$.confirmationToken") + "\",\"idempotencyKey\":\""
                + UUID.randomUUID() + "\"}"), "$.number");
        String orderId = jdbc.sql("SELECT id::text FROM orders WHERE number = :n").param("n", number).query(String.class).single();

        await(() -> jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id AND published_at IS NOT NULL")
                .param("id", orderId).query(Long.class).single() == 1);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("order.events"));
            List<ConsumerRecord<String, String>> mine = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(20);
            while (mine.isEmpty() && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
                    if (orderId.equals(rec.key())) {
                        mine.add(rec);
                    }
                });
            }
            assertThat(mine).singleElement().satisfies(rec -> {
                assertThat((String) JsonPath.read(rec.value(), "$.type")).isEqualTo("OrderPlaced");
                assertThat((String) JsonPath.read(rec.value(), "$.payload.number")).isEqualTo(number);
                assertThat((String) JsonPath.read(rec.value(), "$.payload.status")).isEqualTo("PLACED");
            });
        }
    }

    @Test
    void paymentSucceededEventPlacesTheCardOrderOnce() throws Exception {
        String me = newCustomer();
        readyCart(me, "CARD");
        ResultActions q = postAs(me, "/api/checkout/quote", null);
        ResultActions order = postAs(me, "/api/checkout/confirm", "{\"quoteId\":\"" + read(q, "$.quoteId")
                + "\",\"confirmationToken\":\"" + read(q, "$.confirmationToken") + "\",\"idempotencyKey\":\""
                + UUID.randomUUID() + "\"}");
        String orderId = read(order, "$.id");
        String eventId = UUID.randomUUID().toString();
        String event = "{\"eventId\":\"" + eventId + "\",\"type\":\"PaymentSucceeded\",\"occurredAt\":\"" + Instant.now()
                + "\",\"aggregateId\":\"" + orderId + "\",\"version\":1,\"payload\":{\"orderId\":\"" + orderId
                + "\",\"amount\":\"171.00\",\"currency\":\"MAD\",\"provider\":\"STRIPE\",\"providerRef\":\"pi_kafka\"}}";
        template.send("payment.events", orderId, event).get();
        template.send("payment.events", orderId, event).get();   // redelivery

        await(() -> "PLACED".equals(jdbc.sql("SELECT status FROM orders WHERE id = CAST(:id AS uuid)")
                .param("id", orderId).query(String.class).single()));
        Thread.sleep(1000);   // give the duplicate time to be (not) applied
        assertThat(jdbc.sql("SELECT count(*) FROM order_status_history WHERE order_id = CAST(:id AS uuid)")
                .param("id", orderId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT payment_status FROM orders WHERE id = CAST(:id AS uuid)")
                .param("id", orderId).query(String.class).single()).isEqualTo("PAID");
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 30 s");
            }
            Thread.sleep(200);
        }
    }
}
