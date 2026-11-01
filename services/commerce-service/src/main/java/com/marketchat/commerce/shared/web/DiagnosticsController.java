package com.marketchat.commerce.shared.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostic endpoints used to verify the security chain end to end (step 0c).
 * {@code /api/me/whoami} returns the caller identity taken from the token — never from the request body.
 */
@RestController
class DiagnosticsController {

    @GetMapping("/api/me/whoami")
    Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "customerId", jwt.getSubject(),
                "email", String.valueOf(jwt.getClaimAsString("email")));
    }

    @GetMapping("/internal/ping")
    Map<String, Object> internalPing(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("service", "commerce-service", "caller", String.valueOf(jwt.getClaimAsString("azp")));
    }

    @GetMapping("/api/catalog/ping")
    Map<String, Object> publicCatalogPing() {
        return Map.of("service", "commerce-service", "public", true);
    }
}
