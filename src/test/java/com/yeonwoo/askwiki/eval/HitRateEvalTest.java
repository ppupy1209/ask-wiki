package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import com.yeonwoo.askwiki.search.IndexOutboxRelay;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

// 이 테스트는 실제 Ollama(nomic-embed-text)가 localhost:11434에 필요. './gradlew evalTest'로 실행. 기본 test에선 @Tag('eval')로 제외됨.
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class HitRateEvalTest {

    private static final List<Integer> TOP_KS = List.of(1, 2, 4, 8);

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired
    DocumentService documentService;

    @Autowired
    VectorIndex vectorIndex;

    @Autowired
    EmbeddingClient embeddingClient;

    @Autowired
    IndexOutboxRelay relay;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    IndexOutboxRepository outboxRepository;

    @Test
    void measuresHitRateAtK() throws IOException {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();

        Map<String, Long> slugToId = loadCorpus();
        relay.processPendingEvents();

        JsonNode answerable;
        try (InputStream inputStream = new ClassPathResource("eval/questions.json").getInputStream()) {
            answerable = new ObjectMapper().readTree(inputStream).get("answerable");
        }
        Map<Integer, Integer> hitsByK = new LinkedHashMap<>();
        TOP_KS.forEach(k -> hitsByK.put(k, 0));
        Map<String, Integer> totalByDifficulty = new HashMap<>();
        Map<String, Integer> hitsAt4ByDifficulty = new HashMap<>();

        int total = 0;
        for (JsonNode node : answerable) {
            total++;
            String question = node.get("question").asText();
            String expectedDocSlug = node.get("expectedDocSlug").asText();
            String difficulty = node.get("difficulty").asText();

            float[] qv = embeddingClient.embed(question);
            List<ChunkMatch> top = vectorIndex.search(qv, 8);
            List<Long> topDocIds = top.stream()
                    .map(ChunkMatch::documentId)
                    .toList();
            Long expectedDocId = slugToId.get(expectedDocSlug);

            for (int k : TOP_KS) {
                if (containsDocumentAtK(topDocIds, expectedDocId, k)) {
                    hitsByK.put(k, hitsByK.get(k) + 1);
                }
            }

            totalByDifficulty.merge(difficulty, 1, Integer::sum);
            if (containsDocumentAtK(topDocIds, expectedDocId, 4)) {
                hitsAt4ByDifficulty.merge(difficulty, 1, Integer::sum);
            }
        }

        double r1 = hitRate(hitsByK.get(1), total);
        double r2 = hitRate(hitsByK.get(2), total);
        double r4 = hitRate(hitsByK.get(4), total);
        double r8 = hitRate(hitsByK.get(8), total);
        double easy = hitRate(
                hitsAt4ByDifficulty.getOrDefault("easy", 0),
                totalByDifficulty.getOrDefault("easy", 0)
        );
        double medium = hitRate(
                hitsAt4ByDifficulty.getOrDefault("medium", 0),
                totalByDifficulty.getOrDefault("medium", 0)
        );
        double hard = hitRate(
                hitsAt4ByDifficulty.getOrDefault("hard", 0),
                totalByDifficulty.getOrDefault("hard", 0)
        );

        System.out.println(String.format(
                "[HIT-RATE] total=%d @1=%.1f%% @2=%.1f%% @4=%.1f%% @8=%.1f%%",
                total, r1, r2, r4, r8
        ));
        System.out.println(String.format(
                "[HIT-RATE-BY-DIFF] easy=%.1f%% medium=%.1f%% hard=%.1f%% (@4)",
                easy, medium, hard
        ));

        assertEquals(30, total);
        assertTrue(r1 <= r2 && r2 <= r4 && r4 <= r8);
        assertTrue(r8 > 0.0);
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

    private double hitRate(int hits, int total) {
        if (total == 0) {
            return 0.0;
        }
        return hits * 100.0 / total;
    }
}
