package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.conversation.QuestionRewriter;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.IndexOutboxRelay;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3-4b 측정: 문맥 의존 후속 질문을 원문 그대로 검색한 경우와 독립 질문으로 재작성한 경우의 검색 적중률을 비교한다.
 * turn1Answer는 turn1의 답변 품질이 결과를 흔들지 않도록 스크립트로 고정해 재작성만을 변수로 두는 통제다.
 * 실제 Ollama(임베딩)와 설정된 챗 프로바이더(재작성)가 필요하다.
 * 실행: {@code ./gradlew evalTest --tests "*MultiTurnRewriteEvalTest"}.
 */
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class MultiTurnRewriteEvalTest {

    private static final List<Integer> TOP_KS = List.of(1, 2, 4, 8);

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired DocumentService documentService;
    @Autowired VectorIndex vectorIndex;
    @Autowired EmbeddingClient embeddingClient;
    @Autowired IndexOutboxRelay relay;
    @Autowired DocumentRepository documentRepository;
    @Autowired ChunkRepository chunkRepository;
    @Autowired IndexOutboxRepository outboxRepository;
    @Autowired QuestionRewriter questionRewriter;

    @Test
    void comparesRewriteOnVsOff() throws IOException {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();

        Map<String, Long> slugToId = loadCorpus();
        relay.processPendingEvents();

        JsonNode multiTurn;
        try (InputStream inputStream = new ClassPathResource("eval/questions-multiturn.json").getInputStream()) {
            multiTurn = new ObjectMapper().readTree(inputStream).get("multiTurn");
        }

        Map<Integer, Integer> offHitsByK = new LinkedHashMap<>();
        Map<Integer, Integer> onHitsByK = new LinkedHashMap<>();
        TOP_KS.forEach(k -> {
            offHitsByK.put(k, 0);
            onHitsByK.put(k, 0);
        });
        Map<String, Integer> totalByAnchor = new HashMap<>();
        Map<String, Integer> offHitsAt4ByAnchor = new HashMap<>();
        Map<String, Integer> onHitsAt4ByAnchor = new HashMap<>();

        int total = 0;
        long pacingMs = evalPacingMs();
        for (JsonNode node : multiTurn) {
            total++;
            String id = node.get("id").asText();
            String turn1 = node.get("turn1").asText();
            String turn1Answer = node.get("turn1Answer").asText();
            String followUp = node.get("followUp").asText();
            String expectedDocSlug = node.get("expectedDocSlug").asText();
            String anchor = node.get("anchor").asText();
            Long expectedDocId = slugToId.get(expectedDocSlug);

            float[] offVector = embeddingClient.embed(followUp);
            List<Long> offTopDocIds = vectorIndex.search(followUp, offVector, 8).stream()
                    .map(ChunkMatch::documentId)
                    .toList();

            List<Message> history = List.of(new UserMessage(turn1), new AssistantMessage(turn1Answer));
            String standalone = questionRewriter.rewrite(followUp, history);
            float[] onVector = embeddingClient.embed(standalone);
            List<Long> onTopDocIds = vectorIndex.search(standalone, onVector, 8).stream()
                    .map(ChunkMatch::documentId)
                    .toList();

            for (int k : TOP_KS) {
                if (containsDocumentAtK(offTopDocIds, expectedDocId, k)) {
                    offHitsByK.put(k, offHitsByK.get(k) + 1);
                }
                if (containsDocumentAtK(onTopDocIds, expectedDocId, k)) {
                    onHitsByK.put(k, onHitsByK.get(k) + 1);
                }
            }

            boolean offHit4 = containsDocumentAtK(offTopDocIds, expectedDocId, 4);
            boolean onHit4 = containsDocumentAtK(onTopDocIds, expectedDocId, 4);
            totalByAnchor.merge(anchor, 1, Integer::sum);
            if (offHit4) {
                offHitsAt4ByAnchor.merge(anchor, 1, Integer::sum);
            }
            if (onHit4) {
                onHitsAt4ByAnchor.merge(anchor, 1, Integer::sum);
            }

            System.out.println(String.format(
                    "[REWRITE] id=%s anchor=%s off@4=%s(rank %s) on@4=%s(rank %s) | \"%s\" -> \"%s\"",
                    id, anchor,
                    offHit4 ? "HIT" : "MISS", rankOf(offTopDocIds, expectedDocId),
                    onHit4 ? "HIT" : "MISS", rankOf(onTopDocIds, expectedDocId),
                    followUp, standalone
            ));
            if (total < multiTurn.size()) {
                pace(pacingMs);
            }
        }

        double offR1 = hitRate(offHitsByK.get(1), total);
        double offR2 = hitRate(offHitsByK.get(2), total);
        double offR4 = hitRate(offHitsByK.get(4), total);
        double offR8 = hitRate(offHitsByK.get(8), total);
        double onR1 = hitRate(onHitsByK.get(1), total);
        double onR2 = hitRate(onHitsByK.get(2), total);
        double onR4 = hitRate(onHitsByK.get(4), total);
        double onR8 = hitRate(onHitsByK.get(8), total);
        double noneOff = hitRate(offHitsAt4ByAnchor.getOrDefault("none", 0), totalByAnchor.getOrDefault("none", 0));
        double noneOn = hitRate(onHitsAt4ByAnchor.getOrDefault("none", 0), totalByAnchor.getOrDefault("none", 0));
        double weakOff = hitRate(offHitsAt4ByAnchor.getOrDefault("weak", 0), totalByAnchor.getOrDefault("weak", 0));
        double weakOn = hitRate(onHitsAt4ByAnchor.getOrDefault("weak", 0), totalByAnchor.getOrDefault("weak", 0));

        System.out.println(String.format(
                "[REWRITE-AB] total=%d | OFF @1=%.1f%% @2=%.1f%% @4=%.1f%% @8=%.1f%%",
                total, offR1, offR2, offR4, offR8
        ));
        System.out.println(String.format(
                "[REWRITE-AB] total=%d | ON  @1=%.1f%% @2=%.1f%% @4=%.1f%% @8=%.1f%%",
                total, onR1, onR2, onR4, onR8
        ));
        System.out.println(String.format(
                "[REWRITE-AB-BY-ANCHOR] @4 none: off=%.1f%% on=%.1f%% | weak: off=%.1f%% on=%.1f%%",
                noneOff, noneOn, weakOff, weakOn
        ));

        assertEquals(8, total);
        assertHitRateRange(offR1, offR2, offR4, offR8, noneOff, noneOn, weakOff, weakOn);
        assertHitRateRange(onR1, onR2, onR4, onR8);
        assertTrue(offR1 <= offR2 && offR2 <= offR4 && offR4 <= offR8);
        assertTrue(onR1 <= onR2 && onR2 <= onR4 && onR4 <= onR8);
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

    private boolean containsDocumentAtK(List<Long> topDocIds, Long expectedDocId, int k) {
        if (expectedDocId == null) {
            return false;
        }
        return topDocIds.stream()
                .limit(k)
                .anyMatch(expectedDocId::equals);
    }

    /**
     * 기대 문서가 top-8에서 몇 위인지(없으면 "8+"). MISS가 근소한 순위 밀림인지 의미적 실패인지 가르는 진단값이며,
     * 채점에는 쓰이지 않는다.
     */
    private String rankOf(List<Long> topDocIds, Long expectedDocId) {
        if (expectedDocId == null) {
            return "-";
        }
        int index = topDocIds.indexOf(expectedDocId);
        return index < 0 ? "8+" : String.valueOf(index + 1);
    }

    private double hitRate(int hits, int total) {
        if (total == 0) {
            return 0.0;
        }
        return hits * 100.0 / total;
    }

    private long evalPacingMs() {
        return Long.parseLong(System.getenv().getOrDefault("ASKWIKI_EVAL_PACING_MS", "0"));
    }

    private void pace(long pacingMs) {
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

    private void assertHitRateRange(double... hitRates) {
        for (double hitRate : hitRates) {
            assertTrue(hitRate >= 0.0 && hitRate <= 100.0);
        }
    }
}
