package com.yeonwoo.askwiki.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C2-3 지표 기록 로직 검증. 실제 LLM/Ollama 없이 SimpleMeterRegistry + 목 ChatResponse로 결정적으로 확인.
 */
class LlmMetricsTest {

    @Test
    void recordsTokensCallsAndLatencyTaggedByProvider() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry, "gemini");

        ChatResponse response = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(21);
        when(usage.getCompletionTokens()).thenReturn(13);
        when(metadata.getUsage()).thenReturn(usage);
        when(response.getMetadata()).thenReturn(metadata);

        metrics.record(LlmMetrics.PURPOSE_ANSWER, response, 1_500_000_000L); // 1.5s

        assertThat(registry.get("llm.calls").tag("provider", "gemini").tag("purpose", "answer")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.tokens").tag("provider", "gemini").tag("purpose", "answer")
                .tag("type", "input").counter().count()).isEqualTo(21.0);
        assertThat(registry.get("llm.tokens").tag("provider", "gemini").tag("purpose", "answer")
                .tag("type", "output").counter().count()).isEqualTo(13.0);
        assertThat(registry.get("llm.latency").tag("provider", "gemini").tag("purpose", "answer")
                .timer().totalTime(TimeUnit.SECONDS)).isCloseTo(1.5, within(0.01));
    }

    @Test
    void toleratesNullUsageButStillCountsCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry, "ollama");

        ChatResponse response = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(null);
        when(response.getMetadata()).thenReturn(metadata);

        metrics.record(LlmMetrics.PURPOSE_REWRITE, response, 1_000_000L);

        assertThat(registry.get("llm.calls").tag("provider", "ollama").tag("purpose", "rewrite")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsDifferentPurposesIndependently() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry, "ollama");

        ChatResponse response = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(1);
        when(usage.getCompletionTokens()).thenReturn(1);
        when(metadata.getUsage()).thenReturn(usage);
        when(response.getMetadata()).thenReturn(metadata);

        metrics.record(LlmMetrics.PURPOSE_ANSWER, response, 1L);
        metrics.record(LlmMetrics.PURPOSE_CLASSIFY, response, 1L);

        assertThat(registry.get("llm.calls").tag("purpose", "answer").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.calls").tag("purpose", "classify").counter().count()).isEqualTo(1.0);
    }
}
