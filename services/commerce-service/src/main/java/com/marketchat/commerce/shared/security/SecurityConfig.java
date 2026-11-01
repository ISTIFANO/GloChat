package com.marketchat.commerce.shared.security;

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

/**
 * commerce-service is an OAuth2 resource server: every request is authenticated by a Keycloak JWT,
 * except public catalog browsing and health probes.
 * <ul>
 *   <li>{@code /internal/**} — service-to-service only: requires the {@code internal} scope.</li>
 *   <li>{@code /api/**} — user endpoints: authenticated (audience checked by the decoder).</li>
 *   <li>Everything else — denied.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // stateless JSON API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // error dispatches must reach the error controller, otherwise a 500 is masked as 403
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/search").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_internal")
                        .requestMatchers("/api/**").authenticated()
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
