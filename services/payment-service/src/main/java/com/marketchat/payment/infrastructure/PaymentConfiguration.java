package com.marketchat.payment.infrastructure;

import com.marketchat.payment.application.PaymentService;
import com.marketchat.payment.domain.port.PaymentProvider;
import com.marketchat.payment.domain.port.PaymentStore;
import com.marketchat.payment.domain.port.WebhookVerifier;
import com.marketchat.payment.infrastructure.stripe.StripePaymentProvider;
import com.marketchat.payment.infrastructure.stripe.StripeWebhookVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
class PaymentConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(PaymentProvider.class)
    PaymentProvider stripePaymentProvider(@Value("${marketchat.payment.stripe.secret-key:}") String secretKey) {
        return new StripePaymentProvider(secretKey);
    }

    @Bean
    @ConditionalOnMissingBean(WebhookVerifier.class)
    WebhookVerifier stripeWebhookVerifier(@Value("${marketchat.payment.stripe.webhook-secret:}") String secret) {
        return new StripeWebhookVerifier(secret);
    }

    @Bean
    PaymentService paymentService(PaymentStore store, PaymentProvider provider, WebhookVerifier webhooks,
                                  PlatformTransactionManager txManager, Clock clock,
                                  @Value("${marketchat.payment.session-ttl-minutes:30}") long ttlMinutes) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        PaymentService.Tx tx = new PaymentService.Tx() {
            @Override
            public <T> T run(java.util.function.Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
        return new PaymentService(store, provider, webhooks, tx, clock, Duration.ofMinutes(ttlMinutes));
    }
}
