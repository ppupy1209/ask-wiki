package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.search.InMemoryVectorIndex;
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
import java.util.Map;
import java.util.Objects;

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
    InMemoryVectorIndex vectorIndex;

    @Autowired
    IndexOutboxRelay relay;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    IndexOutboxRepository outboxRepository;

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
            String answerText = answerText(questionText(node));
            if (!answerText.contains("모르겠습니다")) {
                hallucinated++;
            }
        }

        int ansTotal = 0;
        int falseRefusal = 0;
        for (JsonNode node : answerable) {
            ansTotal++;
            String answerText = answerText(questionText(node));
            if (answerText.contains("모르겠습니다")) {
                falseRefusal++;
            }
        }

        double hRate = rate(hallucinated, unTotal);
        double fRate = rate(falseRefusal, ansTotal);

        System.out.println(String.format("[GEN-QUALITY] unanswerable=%d hallucinated=%d (%.1f%%) | answerable=%d falseRefusal=%d (%.1f%%)", unTotal, hallucinated, hRate, ansTotal, falseRefusal, fRate));

        assertEquals(20, unTotal);
        assertEquals(30, ansTotal);
        assertTrue(0.0 <= hRate && hRate <= 100.0 && 0.0 <= fRate && fRate <= 100.0);
    }

    private String answerText(String question) {
        RagResult r = ragService.answer(question, 4);
        return switch (r) {
            case RagResult.Answered a -> a.answer();
            case RagResult.NoContext n -> "모르겠습니다";
            case RagResult.LlmError e -> throw new IllegalStateException("LLM error: " + e.message());
            case RagResult.Degraded d -> throw new IllegalStateException("LLM degraded: " + d.message());
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
