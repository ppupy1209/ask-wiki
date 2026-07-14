package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.rag.AgenticRagService;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.search.IndexOutboxRelay;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import com.yeonwoo.askwiki.search.VectorIndex;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3-1 측정: 멀티홉 질문에서 단발 {@link RagService} vs 에이전틱 {@link AgenticRagService} 비교.
 *
 * <p>정답 판정 = 답변에 factsA(문서1) 중 1개 이상 AND factsB(문서2) 중 1개 이상 포함(양쪽 문서 사실을 다 담았나).
 * 실제 Ollama(임베딩) + 설정된 챗 프로바이더가 필요하다. 에이전틱은 도구 사용 능력이 관건이라 강한 모델(Gemini)을 권장.</p>
 *
 * <p>실행 노브(env):
 * {@code ASKWIKI_LLM_PROVIDER=gemini}, {@code GOOGLE_GENAI_API_KEY},
 * {@code ASKWIKI_EVAL_PACING_MS}(권장 20000 — 에이전틱은 한 답변에 여러 번 호출해 무료 티어 RPM을 터뜨릴 수 있음),
 * {@code ASKWIKI_EVAL_MULTIHOP_TOPKS}="2" 또는 "2,4".</p>
 */
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class MultiHopComparisonEvalTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired RagService ragService;
    @Autowired AgenticRagService agenticRagService;
    @Autowired DocumentService documentService;
    @Autowired VectorIndex vectorIndex;
    @Autowired IndexOutboxRelay relay;
    @Autowired DocumentRepository documentRepository;
    @Autowired ChunkRepository chunkRepository;
    @Autowired IndexOutboxRepository outboxRepository;
    @Autowired MeterRegistry meterRegistry;

    @Value("${askwiki.llm.provider:ollama}") String provider;
    @Value("${askwiki.eval.pacing-ms:0}") long pacingMs;
    @Value("${askwiki.eval.multihop-topks:2}") String topKsCsv;

    @Test
    void comparesSingleShotVsAgenticOnMultiHop() throws IOException {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();
        loadCorpus();
        relay.processPendingEvents();

        JsonNode multihop;
        try (InputStream in = new ClassPathResource("eval/questions-multihop.json").getInputStream()) {
            multihop = new ObjectMapper().readTree(in).get("multihop");
        }

        for (String topKStr : topKsCsv.split(",")) {
            int topK = Integer.parseInt(topKStr.trim());
            int total = 0, singleCorrect = 0, agenticCorrect = 0, singleErr = 0, agenticErr = 0;
            long singleMs = 0, agenticMs = 0;

            System.out.println("\n===== [MULTIHOP] topK=" + topK + " provider=" + provider + " =====");
            for (JsonNode q : multihop) {
                total++;
                String id = q.get("id").asText();
                String question = q.get("question").asText();
                List<String> factsA = toList(q.get("factsA"));
                List<String> factsB = toList(q.get("factsB"));

                pace();
                long t0 = System.nanoTime();
                RagResult sResult = safeAnswer(true, question, topK);
                singleMs += millis(t0);
                String singleAns = answerText(sResult);
                boolean sOk = singleAns != null && hasBoth(singleAns, factsA, factsB);
                if (singleAns == null) singleErr++; else if (sOk) singleCorrect++;

                pace();
                long t1 = System.nanoTime();
                RagResult aResult = safeAnswer(false, question, topK);
                agenticMs += millis(t1);
                String agenticAns = answerText(aResult);
                boolean aOk = agenticAns != null && hasBoth(agenticAns, factsA, factsB);
                if (agenticAns == null) agenticErr++; else if (aOk) agenticCorrect++;

                System.out.println(String.format("[MULTIHOP topK=%d] %s single=%s agentic=%s",
                        topK, id, mark(singleAns, sOk), mark(agenticAns, aOk)));
                if (sResult instanceof RagResult.LlmError se) {
                    System.out.println("  [single-err] " + se.message());
                }
                if (aResult instanceof RagResult.LlmError ae) {
                    System.out.println("  [agentic-err] " + ae.message());
                }
            }
            System.out.println(String.format(
                    "[MULTIHOP-SUMMARY topK=%d] single %d/%d correct (err %d, avgMs %d) | agentic %d/%d correct (err %d, avgMs %d)",
                    topK, singleCorrect, total, singleErr, singleMs / total,
                    agenticCorrect, total, agenticErr, agenticMs / total));
        }
        printLlmUsageSummary();
        assertTrue(multihop.size() > 0);
    }

    /** single=true면 단발, false면 에이전틱. 예외(429 등)는 LlmError로 흡수해 러너가 멈추지 않게 한다. */
    private RagResult safeAnswer(boolean single, String question, int topK) {
        try {
            return single ? ragService.answer(question, topK) : agenticRagService.answer(question, topK);
        } catch (Exception e) {
            return new RagResult.LlmError(e.getMessage());
        }
    }

    /** Answered→답변, NoContext/Degraded/Clarify→""(사실 미포함), LlmError→null(에러). */
    private String answerText(RagResult r) {
        return switch (r) {
            case RagResult.Answered a -> a.answer();
            case RagResult.NoContext n -> "";
            case RagResult.Degraded d -> "";
            case RagResult.LlmError e -> null;
            case RagResult.Clarify c -> "";
        };
    }

    private boolean hasBoth(String answer, List<String> factsA, List<String> factsB) {
        boolean a = factsA.stream().anyMatch(answer::contains);
        boolean b = factsB.stream().anyMatch(answer::contains);
        return a && b;
    }

    private String mark(String ans, boolean ok) {
        if (ans == null) return "ERR";
        return ok ? "O" : "X";
    }

    private long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private List<String> toList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        arr.forEach(n -> list.add(n.asText()));
        return list;
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

    private void printLlmUsageSummary() {
        double calls = meterRegistry.counter("llm.calls", "provider", provider).count();
        double inTok = meterRegistry.counter("llm.tokens", "provider", provider, "type", "input").count();
        double outTok = meterRegistry.counter("llm.tokens", "provider", provider, "type", "output").count();
        Timer latency = meterRegistry.timer("llm.latency", "provider", provider);
        System.out.println(String.format(
                "[LLM-USAGE] provider=%s calls=%.0f tokens(in=%.0f out=%.0f) latencyMs(mean=%.0f max=%.0f)",
                provider, calls, inTok, outTok,
                latency.mean(TimeUnit.MILLISECONDS), latency.max(TimeUnit.MILLISECONDS)));
    }

    private void loadCorpus() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:eval/corpus/*.md");
        for (Resource resource : resources) {
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String[] parts = text.split("\\R", 2);
            String title = parts[0].replaceFirst("^#\\s*", "").trim();
            String content = parts.length > 1 ? parts[1].trim() : text;
            if (content.isBlank()) {
                content = text;
            }
            documentService.create(new CreateDocumentRequest(title, content));
        }
    }
}
