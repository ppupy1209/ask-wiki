package com.yeonwoo.askwiki.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM 호출의 토큰·비용·지연을 Micrometer 지표로 기록한다. (C2-3, 공고의 "토큰 최적화" 관측)
 *
 * <p>{@link com.yeonwoo.askwiki.cache.QueryCache}와 같은 카운터 등록 패턴. 활성 프로바이더
 * ({@code askwiki.llm.provider})를 태그로 달아 프로바이더별 비교(토큰·비용·지연)를 가능하게 한다.
 *
 * <p>노출 지표(Prometheus 이름은 밑줄로 변환됨):
 * purpose=answer|classify|rewrite로 구분해 라우팅·질문 재작성까지 실제 토큰 비용을 보며,
 * 공고의 "토큰 최적화" 관점에서 어떤 LLM 작업을 최적화할지 판단한다.
 * <ul>
 *   <li>{@code llm.calls{provider,purpose}} — LLM 호출 횟수</li>
 *   <li>{@code llm.tokens{provider,purpose,type=input|output}} — 입력/출력 토큰 수</li>
 *   <li>{@code llm.cost.usd{provider,purpose}} — 추정 비용(USD) = 토큰 × 단가</li>
 *   <li>{@code llm.latency{provider,purpose}} — 호출 지연(타이머, p95/p99 히스토그램)</li>
 *   <li>{@code llm.degraded{provider,purpose,reason}} — 타임아웃·과부하 폴백 횟수</li>
 * </ul>
 */
@Component
public class LlmMetrics {

    /** 지표의 purpose 태그 값 — 어떤 LLM 작업이 토큰을 쓰는지 구분한다. */
    public static final String PURPOSE_ANSWER = "answer";
    public static final String PURPOSE_CLASSIFY = "classify";
    public static final String PURPOSE_REWRITE = "rewrite";

    // ollama(로컬)·gemini(Google AI Studio 무료 티어)는 0. 유료 추가 예: "anthropic" -> {3.0, 15.0}(Sonnet 5 정가).
    private static final Map<String, double[]> PRICE_PER_1M = Map.of(
            "ollama", new double[]{0.0, 0.0},
            "gemini", new double[]{0.0, 0.0}
    );

    private final MeterRegistry registry;
    private final String provider;
    private final double[] price;

    public LlmMetrics(MeterRegistry registry,
                      @Value("${askwiki.llm.provider:ollama}") String provider) {
        this.registry = registry;
        this.provider = provider.trim().toLowerCase(Locale.ROOT);
        this.price = PRICE_PER_1M.getOrDefault(this.provider, new double[]{0.0, 0.0});
    }

    /** LLM 호출 1건의 지연·응답을 지표에 기록한다. */
    public void record(String purpose, ChatResponse response, long latencyNanos) {
        registry.counter("llm.calls", "provider", provider, "purpose", purpose).increment();
        Timer.builder("llm.latency").tags("provider", provider, "purpose", purpose)
                .register(registry).record(latencyNanos, TimeUnit.NANOSECONDS);

        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        Integer in = usage.getPromptTokens();
        Integer out = usage.getCompletionTokens();
        int inCount = in == null ? 0 : in;
        int outCount = out == null ? 0 : out;
        registry.counter("llm.tokens", "provider", provider, "purpose", purpose, "type", "input")
                .increment(inCount);
        registry.counter("llm.tokens", "provider", provider, "purpose", purpose, "type", "output")
                .increment(outCount);

        double cost = inCount / 1_000_000.0 * price[0] + outCount / 1_000_000.0 * price[1];
        if (cost > 0) {
            registry.counter("llm.cost.usd", "provider", provider, "purpose", purpose).increment(cost);
        }
    }

    /** degraded 폴백(타임아웃·과부하) 발생을 기록한다. reason: timeout|busy. (C2-4) */
    public void recordDegraded(String purpose, String reason) {
        registry.counter("llm.degraded", "provider", provider, "purpose", purpose, "reason", reason).increment();
    }
}
