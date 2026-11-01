package com.marketchat.payment.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authorization rules of payment-service: the webhook is public (signature-checked), /internal needs scope internal. */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.marketchat.payment.support.TestcontainersConfiguration.class)
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void stripeWebhookIsPublic() throws Exception {
        // reaches the controller without a token; rejected there because the Stripe signature is missing
        mvc.perform(post("/api/payments/stripe/webhook").content("{}")).andExpect(status().isBadRequest());
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
