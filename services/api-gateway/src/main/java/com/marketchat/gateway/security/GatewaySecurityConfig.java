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
                .authorizeExchange(ex -> ex
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

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${marketchat.security.jwk-set-uri}") String jwkSetUri,
                                  @Value("${marketchat.security.issuer-uri}") String issuer) {
        return MarketchatJwtDecoders.reactive(jwkSetUri, issuer);
    }
}
