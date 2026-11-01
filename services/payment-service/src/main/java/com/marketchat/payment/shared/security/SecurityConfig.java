package com.marketchat.payment.shared.security;

import com.marketchat.common.security.MarketchatJwtDecoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** payment-service: only the Stripe webhook is public (verified by Stripe signature, not by a JWT); everything else is service-to-service. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // error dispatches must reach the error controller, otherwise a 500 is masked as 403
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/stripe/webhook").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_internal")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${marketchat.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${marketchat.security.issuer-uri}") String issuer) {
        return MarketchatJwtDecoders.servlet(jwkSetUri, issuer);
    }
}
