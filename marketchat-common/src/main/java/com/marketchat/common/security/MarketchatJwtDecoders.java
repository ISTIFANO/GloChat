package com.marketchat.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Builds JWT decoders that:
 * <ul>
 *   <li>fetch keys from the JWK Set URI <b>lazily</b> (services start even if Keycloak is not up yet),</li>
 *   <li>validate expiry, the expected issuer, and the MarketChat audience/scope rule.</li>
 * </ul>
 * JWK Set URI and issuer are separate on purpose: inside Docker the keys are fetched from
 * {@code http://keycloak:8080/...} while the tokens' issuer stays the public URL.
 */
public final class MarketchatJwtDecoders {

    private MarketchatJwtDecoders() {
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new MarketchatTokenValidator());
    }

    public static JwtDecoder servlet(String jwkSetUri, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    public static ReactiveJwtDecoder reactive(String jwkSetUri, String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }
}
