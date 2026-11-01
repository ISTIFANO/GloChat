package com.marketchat.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;


/**
 * Step 0c: the gateway routes to the right service, enforces authentication, never exposes /internal,
 * and forwards the caller's token. A tiny in-process HTTP server stands in for commerce-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingSecurityTest {

    static HttpServer stubCommerce;

    @BeforeAll
    static void startStub() throws IOException {
        stubCommerce = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubCommerce.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = ("stub-commerce path=" + exchange.getRequestURI().getPath()
                    + " auth=" + (auth != null && auth.startsWith("Bearer ") ? "bearer" : "none"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubCommerce.start();
    }

    @AfterAll
    static void stopStub() {
        stubCommerce.stop(0);
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("COMMERCE_URL", () -> "http://127.0.0.1:" + stubCommerce.getAddress().getPort());
    }

    /** Test decoder: "Bearer user" = MarketChat user token, "Bearer service" = internal service token. */
    @TestConfiguration
    static class TestTokens {
        @Bean
        @Primary
        ReactiveJwtDecoder testJwtDecoder() {
            return token -> {
                Jwt.Builder b = Jwt.withTokenValue(token).header("alg", "none")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
                return switch (token) {
                    case "user" -> Mono.just(b.subject("user-42").audience(List.of("marketchat-api")).build());
                    case "service" -> Mono.just(b.subject("svc").claim("scope", "internal").build());
                    default -> Mono.error(new BadJwtException("invalid test token"));
                };
            };
        }
    }

    @Autowired
    WebTestClient client;

    @Test
    void publicCatalogIsRoutedWithoutToken() {
        client.get().uri("/api/catalog/ping").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("stub-commerce path=/api/catalog/ping auth=none");
    }

    @Test
    void protectedRouteRejectsAnonymous() {
        client.get().uri("/api/me/whoami").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void protectedRouteIsForwardedWithToken() {
        client.get().uri("/api/me/whoami")
                .header("Authorization", "Bearer user")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("stub-commerce path=/api/me/whoami auth=bearer");
    }

    @Test
    void internalPathsAreNeverExposed() {
        client.get().uri("/internal/ping").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/ping").header("Authorization", "Bearer service")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void unknownPathsAreDenied() {
        client.get().uri("/admin").header("Authorization", "Bearer user").exchange().expectStatus().isForbidden();
    }

    @Test
    void invalidTokenIsRejected() {
        client.get().uri("/api/me/whoami").header("Authorization", "Bearer forged").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void corsPreflightAllowsTheWebApp() {
        client.options().uri("/api/catalog/search")
                .header("Origin", "http://web.test")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://web.test");
    }

    @Test
    void corsRejectsOtherOrigins() {
        client.options().uri("/api/catalog/search")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}
