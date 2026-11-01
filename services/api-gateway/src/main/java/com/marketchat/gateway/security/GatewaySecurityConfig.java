package com.marketchat.gateway.security;

import com.marketchat.common.security.MarketchatJwtDecoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * First line of defence. The gateway validates the user token before routing; each service validates it again
 * (defence in depth). The token is forwarded unchanged in the Authorization header.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS is answered by the gateway only (services are never called from a browser directly)
                .cors(Customizer.withDefaults())
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        // service-to-service endpoints are never reachable from outside
                        .pathMatchers("/internal/**").denyAll()
                        // public: catalog browsing, discovery chat (cart/order tools refuse without a token), Stripe webhook
                        .pathMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/catalog/search").permitAll()
                        .pathMatchers("/api/chat/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/payments/stripe/webhook").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Only the configured front-end origins (comma-separated) may call the API from a browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${marketchat.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "traceparent"));
        cors.setExposedHeaders(List.of("traceparent"));
        cors.setAllowCredentials(false);   // bearer tokens, no cookies
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${marketchat.security.jwk-set-uri}") String jwkSetUri,
                                  @Value("${marketchat.security.issuer-uri}") String issuer) {
        return MarketchatJwtDecoders.reactive(jwkSetUri, issuer);
    }
}
