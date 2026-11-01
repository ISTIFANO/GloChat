package com.marketchat.commerce.orders.infrastructure.payment;

import com.marketchat.commerce.orders.domain.port.PaymentGateway;
import com.marketchat.commerce.shared.domain.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Calls payment-service's internal API with a client-credentials token (Keycloak client "commerce-service",
 * scope "internal"). Not routed by the gateway; payment-service requires SCOPE_internal.
 */
class HttpPaymentGateway implements PaymentGateway {

    private final RestClient payment;
    private final RestClient keycloak;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    HttpPaymentGateway(RestClient.Builder builder, String paymentBaseUrl, String tokenUri, String clientId,
                       String clientSecret, Clock clock) {
        this.payment = builder.clone().baseUrl(paymentBaseUrl).build();
        this.keycloak = builder.clone().build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    record SessionResponse(String sessionId, String redirectUrl) {
    }

    record SyncResponse(String status, java.math.BigDecimal amount, String currency, String providerRef) {
    }

    record TokenResponse(String access_token, long expires_in) {
    }

    @Override
    public Session createSession(SessionRequest r) {
        try {
            SessionResponse s = payment.post().uri("/internal/payments/sessions")
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", r.orderId(), "orderNumber", r.orderNumber(), "amount", r.amount(),
                            "currency", r.currency(), "successUrl", r.successUrl(), "cancelUrl", r.cancelUrl()))
                    .retrieve().body(SessionResponse.class);
            return new Session(s.sessionId(), s.redirectUrl());
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    @Override
    public State sync(UUID orderId) {
        try {
            SyncResponse s = payment.post().uri("/internal/payments/sync")
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId))
                    .retrieve().body(SyncResponse.class);
            return new State(s.status(), s.amount(), s.currency(), s.providerRef());
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private synchronized String token() {
        if (token != null && clock.instant().isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", "internal");
        TokenResponse t = keycloak.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(TokenResponse.class);
        token = t.access_token();
        tokenExpiresAt = clock.instant().plusSeconds(Math.max(10, t.expires_in() - 30));
        return token;
    }

    private static BusinessException unavailable(RestClientException e) {
        return new BusinessException("PAYMENT_UNAVAILABLE", 503,
                "The payment service is unavailable, please retry in a moment (" + e.getClass().getSimpleName() + ")");
    }
}
