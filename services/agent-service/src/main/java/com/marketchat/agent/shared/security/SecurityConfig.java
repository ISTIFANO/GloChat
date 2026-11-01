package com.marketchat.agent.shared.security;

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

/** agent-service: discovery chat is open to guests (cart/order tools refuse without a token); conversation history needs a user token. */
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
                        .requestMatchers("/api/chat/**").permitAll()
                        .requestMatchers("/api/conversations/**").authenticated()
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
