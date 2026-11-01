package com.marketchat.agent.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step 0c: authorization rules of agent-service. 404 = allowed through security, no controller yet. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void chatIsOpenToGuests() throws Exception {
        mvc.perform(post("/api/chat")).andExpect(status().isNotFound());
    }

    @Test
    void conversationsNeedAToken() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations").with(jwt())).andExpect(status().isNotFound());
    }

    @Test
    void internalRequiresInternalScope() throws Exception {
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/anything").with(jwt().jwt(j -> j.claim("scope", "profile")))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/anything").with(jwt().jwt(j -> j.claim("scope", "internal")))).andExpect(status().isNotFound());
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/admin").with(jwt())).andExpect(status().isForbidden());
    }
}
