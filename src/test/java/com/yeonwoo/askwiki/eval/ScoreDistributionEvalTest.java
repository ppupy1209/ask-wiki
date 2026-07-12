package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.IndexOutboxRelay;
import com.yeonwoo.askwiki.search.IndexOutboxRepository;
import com.yeonwoo.askwiki.search.VectorIndex;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2 유사도 임계값 사전 조사: answerable vs unanswerable 질문의 "최상위 청크 유사도 점수" 분포를 뽑는다.
 * 두 그룹의 점수가 갈리면 임계값 게이트(약한 매치는 LLM에 안 넘김)로 환각·오거부를 동시에 낮출 수 있다.
 * 실제 Ollama(nomic-embed-text) 필요, 결정적. ./gradlew evalTest 로 실행(@Tag("eval")).
 */
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class ScoreDistributionEvalTest {

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
    void printsTopScoreDistribution() throws IOException {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();

        loadCorpus();
        relay.processPendingEvents();

        JsonNode root;
        try (InputStream in = new ClassPathResource("eval/questions.json").getInputStream()) {
            root = new ObjectMapper().readTree(in);
        }

        List<Double> answerable = topScores(root.get("answerable"));
        List<Double> unanswerable = topScores(root.get("unanswerable"));

        report("ANSWERABLE", answerable);
        report("UNANSWERABLE", unanswerable);

        assertEquals(30, answerable.size());
        assertEquals(20, unanswerable.size());
    }

    private List<Double> topScores(JsonNode questions) {
        List<Double> scores = new ArrayList<>();
        for (JsonNode node : questions) {
            String question = node.get("question").asText();
            List<ChunkMatch> top = vectorIndex.search(embeddingClient.embed(question), 1);
            scores.add(top.isEmpty() ? 0.0 : top.get(0).score());
        }
        scores.sort(Double::compareTo);
        return scores;
    }

    private void report(String label, List<Double> scores) {
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        String sorted = scores.stream()
                .map(s -> String.format("%.3f", s))
                .collect(Collectors.joining(" "));
        System.out.println(String.format(
                "[SCORE-%s] n=%d min=%.3f mean=%.3f max=%.3f%n  sorted: %s",
                label, scores.size(), min, mean, max, sorted));
    }

    private void loadCorpus() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:eval/corpus/*.md");
        for (Resource resource : resources) {
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Objects.requireNonNull(resource.getFilename());
            String[] parts = text.split("\\R", 2);
            String title = parts[0].replaceFirst("^#\\s*", "").trim();
            String content = parts.length > 1 ? parts[1].trim() : "";
            if (content.isBlank()) {
                content = text;
            }
            documentService.create(new CreateDocumentRequest(title, content));
        }
    }
}
