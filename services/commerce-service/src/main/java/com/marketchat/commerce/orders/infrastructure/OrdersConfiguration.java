package com.marketchat.commerce.orders.infrastructure;

import com.marketchat.commerce.orders.application.OrderService;
import com.marketchat.commerce.orders.domain.port.EventOutbox;
import com.marketchat.commerce.orders.domain.port.OrderRepository;
import com.marketchat.commerce.orders.domain.port.PaymentGateway;
import com.marketchat.commerce.orders.domain.port.ProcessedEvents;
import com.marketchat.commerce.shared.application.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.UUID;

@Configuration
@EnableScheduling
class OrdersConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersConfiguration.class);

    @Bean
    OrderService orderService(OrderRepository orders, EventOutbox outbox, PaymentGateway payments,
                              ProcessedEvents processed, Transactions tx, Clock clock,
                              @Value("${marketchat.web.base-url}") String webBaseUrl) {
        return new OrderService(orders, outbox, payments, processed, tx, clock, UUID::randomUUID, webBaseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway paymentGateway(RestClient.Builder builder, Clock clock,
                                  @Value("${marketchat.payment.base-url}") String baseUrl,
                                  @Value("${marketchat.security.token-uri}") String tokenUri,
                                  @Value("${marketchat.payment.client-id}") String clientId,
                                  @Value("${marketchat.payment.client-secret}") String clientSecret) {
        return new com.marketchat.commerce.orders.infrastructure.payment.PaymentGateways()
                .http(builder, baseUrl, tokenUri, clientId, clientSecret, clock);
    }

    /** Card orders still unpaid after 30 minutes are canceled (spec §6.3). */
    @Bean
    @ConditionalOnProperty(name = "marketchat.jobs.payment-expiry.enabled", havingValue = "true", matchIfMissing = true)
    PaymentExpiryJob paymentExpiryJob(OrderService orders) {
        return new PaymentExpiryJob(orders);
    }

    static class PaymentExpiryJob {
        private final OrderService orders;

        PaymentExpiryJob(OrderService orders) {
            this.orders = orders;
        }

        @Scheduled(fixedDelayString = "${marketchat.jobs.payment-expiry.interval-ms:60000}")
        void run() {
            int canceled = orders.expireUnpaidOrders();
            if (canceled > 0) {
                LOG.info("Canceled {} unpaid card order(s) after 30 minutes", canceled);
            }
        }
    }
}
