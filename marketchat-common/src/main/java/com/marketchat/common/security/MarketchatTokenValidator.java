package com.marketchat.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;

/**
 * Accepts a token only if it is meant for MarketChat:
 * <ul>
 *   <li>a <b>user</b> token carrying the {@value #API_AUDIENCE} audience (added by the Keycloak client scope), or</li>
 *   <li>a <b>service</b> token (client credentials) carrying the {@value #INTERNAL_SCOPE} scope.</li>
 * </ul>
 * Tokens issued by the same realm for other purposes (e.g. no audience, no scope) are rejected.
 */
public final class MarketchatTokenValidator implements OAuth2TokenValidator<Jwt> {

    public static final String API_AUDIENCE = "marketchat-api";
    public static final String INTERNAL_SCOPE = "internal";

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "Token is neither a MarketChat user token nor an internal service token", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (hasApiAudience(jwt) || hasInternalScope(jwt)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID);
    }

    public static boolean hasApiAudience(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return audience != null && audience.contains(API_AUDIENCE);
    }

    public static boolean hasInternalScope(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        return scope != null && Arrays.asList(scope.split(" ")).contains(INTERNAL_SCOPE);
    }
}
