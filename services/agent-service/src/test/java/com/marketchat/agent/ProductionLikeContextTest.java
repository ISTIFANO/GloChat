package com.marketchat.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: starts agent-service with the same beans as in production — Gemini chat model and
 * OTLP traces/metrics/logs exporters enabled — without calling any network service.
 * It caught a protobuf 3 vs 4 conflict (google-genai vs OpenTelemetry) that unit tests with exporters
 * disabled could not see.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=google-genai",
        "spring.ai.google.genai.api-key=dummy-key-not-used",
        "management.tracing.export.otlp.enabled=true",
        "management.otlp.metrics.export.enabled=true",
        "management.logging.export.otlp.enabled=true",
        // nothing listens here: exporters are created but never reach a collector
        "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
        "management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:9/v1/logs",
        "management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics"
})
class ProductionLikeContextTest {

    @Autowired
    ApplicationContext context;

    @Test
    void startsWithGeminiAndOtlpExportersTogether() {
        assertThat(context.getBean(ChatModel.class).getClass().getSimpleName()).containsIgnoringCase("Google");
        assertThat(context.getBeanNamesForType(io.micrometer.registry.otlp.OtlpMeterRegistry.class)).isNotEmpty();
    }
}
