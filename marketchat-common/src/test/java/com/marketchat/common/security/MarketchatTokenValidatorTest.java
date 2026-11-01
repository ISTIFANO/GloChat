package com.marketchat.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MarketchatTokenValidatorTest {

    private final MarketchatTokenValidator validator = new MarketchatTokenValidator();

    private static Jwt jwt(Consumer<Jwt.Builder> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.accept(b);
        return b.build();
    }

    @Test
    void acceptsUserTokenWithApiAudience() {
        assertThat(validator.validate(jwt(b -> b.audience(List.of("account", "marketchat-api")))).hasErrors()).isFalse();
    }

    @Test
    void acceptsServiceTokenWithInternalScope() {
        assertThat(validator.validate(jwt(b -> b.claim("scope", "profile internal"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutAudienceOrScope() {
        assertThat(validator.validate(jwt(b -> b.audience(List.of("account")).claim("scope", "profile email"))).hasErrors()).isTrue();
    }

    @Test
    void scopeMatchIsExactNotSubstring() {
        assertThat(validator.validate(jwt(b -> b.claim("scope", "internals not-internal"))).hasErrors()).isTrue();
    }
}
