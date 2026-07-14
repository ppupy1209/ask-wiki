package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.search.VectorIndex;
import com.yeonwoo.askwiki.search.IndexOutboxRelay;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Ollama(nomic-embed-text + llama3.2:3b)가 localhost:11434에서 필요하다.
 * gradlew evalTest로 실행하며, LLM 특성상 느리고 비결정적이라 실행마다 값이 달라질 수 있다.
 */
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class HallucinationEvalTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired
    RagService ragService;

    @Autowired
    DocumentService documentService;

    @Autowired
    VectorIndex vectorIndex;

    @Autowired
    IndexOutboxRelay relay;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    IndexOutboxRepository outboxRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Value("${askwiki.llm.provider:ollama}")
    String provider;

    /**
     * 문항 간 대기(ms). 상용 무료 티어의 분당 요청 제한 대응 — 예: gemini-2.5-flash 무료는 5 RPM이라
     * 13000ms(≈4.6 RPM)로 돌린다. Spring AI 기본 에러 핸들러가 429를 재시도하지 않아 pacing 없이는 러너가 중단된다.
     * 판정·per-call 지연 측정에는 영향 없고 총 실행 시간만 늘어난다. 기본 0 = 대기 없음(로컬 Ollama).
     */
    @Value("${askwiki.eval.pacing-ms:0}")
    long pacingMs;

    @Test
    void measuresHallucinationAndFalseRefusal() throws IOException {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();

        loadCorpus();
        relay.processPendingEvents();

        JsonNode root;
        try (InputStream inputStream = new ClassPathResource("eval/questions.json").getInputStream()) {
            root = new ObjectMapper().readTree(inputStream);
        }
        JsonNode unanswerable = root.get("unanswerable");
        JsonNode answerable = root.get("answerable");

        // About 50 real LLM calls are made here and this can take several minutes; this measures reproducibility, not performance.
        int unTotal = 0;
        int hallucinated = 0;
        for (JsonNode node : unanswerable) {
            unTotal++;
            pace();
            String answerText = answerText(questionText(node));
            if (!answerText.contains("모르겠습니다")) {
                hallucinated++;
            }
        }

        int ansTotal = 0;
        int falseRefusal = 0;
        for (JsonNode node : answerable) {
            ansTotal++;
            pace();
            String answerText = answerText(questionText(node));
            if (answerText.contains("모르겠습니다")) {
                falseRefusal++;
            }
        }

        double hRate = rate(hallucinated, unTotal);
        double fRate = rate(falseRefusal, ansTotal);

        System.out.println(String.format("[GEN-QUALITY] unanswerable=%d hallucinated=%d (%.1f%%) | answerable=%d falseRefusal=%d (%.1f%%)", unTotal, hallucinated, hRate, ansTotal, falseRefusal, fRate));
        printLlmUsageSummary();

        assertEquals(20, unTotal);
        assertEquals(30, ansTotal);
        assertTrue(0.0 <= hRate && hRate <= 100.0 && 0.0 <= fRate && fRate <= 100.0);
    }

    /** C2-5 프로바이더 비교의 부수 지표(토큰·지연·비용) — LlmMetrics가 쌓은 Micrometer 값을 러너 출력으로 노출한다. */
    private void printLlmUsageSummary() {
        double calls = meterRegistry.counter("llm.calls", "provider", provider).count();
        double inputTokens = meterRegistry.counter("llm.tokens", "provider", provider, "type", "input").count();
        double outputTokens = meterRegistry.counter("llm.tokens", "provider", provider, "type", "output").count();
        double costUsd = meterRegistry.counter("llm.cost.usd", "provider", provider).count();
        double degraded = meterRegistry.find("llm.degraded").tag("provider", provider)
                .counters().stream().mapToDouble(Counter::count).sum();
        Timer latency = meterRegistry.timer("llm.latency", "provider", provider);
        System.out.println(String.format(
                "[LLM-USAGE] provider=%s calls=%.0f degraded=%.0f tokens(in=%.0f out=%.0f) costUsd=%.4f latencyMs(mean=%.0f max=%.0f total=%.0f)",
                provider, calls, degraded, inputTokens, outputTokens, costUsd,
                latency.mean(TimeUnit.MILLISECONDS), latency.max(TimeUnit.MILLISECONDS),
                latency.totalTime(TimeUnit.MILLISECONDS)));
    }

    private void pace() {
        if (pacingMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pacingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing", e);
        }
    }

    private String answerText(String question) {
        RagResult r = ragService.answer(question, 4);
        return switch (r) {
            case RagResult.Answered a -> a.answer();
            case RagResult.NoContext n -> "모르겠습니다";
            case RagResult.LlmError e -> throw new IllegalStateException("LLM error: " + e.message());
            case RagResult.Degraded d -> throw new IllegalStateException("LLM degraded: " + d.message());
            case RagResult.Clarify c -> throw new IllegalStateException("unexpected Clarify from RagService: " + c.message());
        };
    }

    private String questionText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        return node.get("question").asText();
    }

    private Map<String, Long> loadCorpus() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:eval/corpus/*.md");
        Map<String, Long> slugToId = new HashMap<>();

        for (Resource resource : resources) {
            String slug = Objects.requireNonNull(resource.getFilename())
                    .replaceFirst("\\.md$", "");
            String text;
            try (InputStream inputStream = resource.getInputStream()) {
                text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            String[] parts = text.split("\\R", 2);
            String title = parts[0].replaceFirst("^#\\s*", "").trim();
            String content = parts.length > 1 ? parts[1].trim() : "";
            if (content.isBlank()) {
                content = text;
            }

            Long id = documentService.create(new CreateDocumentRequest(title, content)).id();
            slugToId.put(slug, id);
        }

        return slugToId;
    }

    private double rate(int count, int total) {
        if (total == 0) {
            return 0.0;
        }
        return count * 100.0 / total;
    }
}
