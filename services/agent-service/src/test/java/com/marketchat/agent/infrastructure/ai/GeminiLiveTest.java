package com.marketchat.agent.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 0f: a real Gemini call through Spring AI's ChatClient.
 * Opt-in only (costs tokens, needs network + billing): run with RUN_LIVE_LLM_TESTS=true and GEMINI_API_KEY set.
 * Skipped in normal builds and in CI, even if GEMINI_API_KEY exists in the environment.
 */
@SpringBootTest(properties = "spring.ai.model.chat=google-genai")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LLM_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLiveTest {

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Test
    void geminiAnswersThroughSpringAi() {
        ChatResponse response = chatClientBuilder.build()
                .prompt()
                .system("You are a connectivity check. Follow instructions exactly.")
                .user("Reply with exactly one word: PONG")
                .call()
                .chatResponse();

        String text = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();

        assertThat(text).containsIgnoringCase("PONG");
        assertThat(usage.getPromptTokens()).isPositive();
        assertThat(usage.getTotalTokens()).isPositive();
        assertThat(response.getMetadata().getModel()).containsIgnoringCase("gemini");
    }
}
