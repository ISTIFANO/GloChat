package com.marketchat.commerce.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostic endpoints used to verify security (step 0c) and observability (step 0d) end to end.
 * {@code /api/me/whoami} returns the caller identity taken from the token — never from the request body.
 */
@RestController
class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    @GetMapping("/api/me/whoami")
    Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        log.info("whoami requested");   // no PII: trace.id links it to the request
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
