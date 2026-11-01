package com.marketchat.commerce.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step 0c: authorization rules of commerce-service (token signature/audience are tested in marketchat-common). */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void userEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/me/whoami")).andExpect(status().isUnauthorized());
    }

    @Test
    void identityComesFromTheToken() throws Exception {
        mvc.perform(get("/api/me/whoami").with(jwt().jwt(j -> j.subject("user-42").claim("email", "a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("user-42"))
                .andExpect(jsonPath("$.email").value("a@b.c"));
    }

    @Test
    void publicCatalogNeedsNoToken() throws Exception {
        mvc.perform(get("/api/catalog/ping")).andExpect(status().isOk());
    }

    @Test
    void internalEndpointRejectsUserTokens() throws Exception {
        mvc.perform(get("/internal/ping").with(jwt().jwt(j -> j.subject("user-42").claim("scope", "profile email"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalEndpointAcceptsInternalScope() throws Exception {
        mvc.perform(get("/internal/ping").with(jwt().jwt(j -> j.claim("scope", "internal").claim("azp", "payment-service"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("payment-service"));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/admin").with(jwt())).andExpect(status().isForbidden());
    }
}
