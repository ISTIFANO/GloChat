package com.marketchat.commerce.orders.infrastructure.payment;

import com.marketchat.commerce.orders.domain.port.PaymentGateway;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/** Factory so the configuration can build the package-private HTTP adapter. */
public final class PaymentGateways {

    public PaymentGateway http(RestClient.Builder builder, String baseUrl, String tokenUri, String clientId,
                               String clientSecret, Clock clock) {
        return new HttpPaymentGateway(builder, baseUrl, tokenUri, clientId, clientSecret, clock);
    }
}
