package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.document.Chunker;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.SearchMath;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class ChunkSizeMatrixEvalTest {

    private static final int[] SIZES = {200, 400, 800};
    private static final int OVERLAP = 50;
    private static final int[] KS = {2, 4, 8};

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired
    EmbeddingClient embeddingClient;

    @Test
    void measuresHitRateAcrossChunkSizes() throws IOException {
        Map<String, String> slugToContent = loadCorpus();
        List<AnswerableQuestion> answerable = loadAnswerableQuestions();

        assertEquals(30, answerable.size());

        List<float[]> qVectors = new ArrayList<>();
        for (AnswerableQuestion question : answerable) {
            qVectors.add(embeddingClient.embed(question.question()));
        }

        for (int size : SIZES) {
            Chunker chunker = new Chunker(size, OVERLAP);
            List<String> chunkSlugs = new ArrayList<>();
            List<float[]> chunkVecs = new ArrayList<>();

            for (Map.Entry<String, String> entry : slugToContent.entrySet()) {
                for (String chunk : chunker.split(entry.getValue())) {
                    chunkSlugs.add(entry.getKey());
                    chunkVecs.add(embeddingClient.embed(chunk));
                }
            }

            int[] hitsByK = new int[KS.length];
            for (int i = 0; i < answerable.size(); i++) {
                List<ScoredSlug> ranked = new ArrayList<>();
                float[] qVector = qVectors.get(i);
                for (int j = 0; j < chunkVecs.size(); j++) {
                    ranked.add(new ScoredSlug(
                            chunkSlugs.get(j),
                            SearchMath.cosineSimilarity(qVector, chunkVecs.get(j))
                    ));
                }
                ranked.sort(Comparator.comparingDouble(ScoredSlug::score).reversed());

                List<String> topSlugs = ranked.stream()
                        .limit(8)
                        .map(ScoredSlug::slug)
                        .toList();
                String expectedDocSlug = answerable.get(i).expectedDocSlug();

                for (int kIndex = 0; kIndex < KS.length; kIndex++) {
                    if (containsSlugAtK(topSlugs, expectedDocSlug, KS[kIndex])) {
                        hitsByK[kIndex]++;
                    }
                }
            }

            double r2 = hitRate(hitsByK[0], answerable.size());
            double r4 = hitRate(hitsByK[1], answerable.size());
            double r8 = hitRate(hitsByK[2], answerable.size());

            System.out.println(String.format(
                    "[CHUNK-MATRIX] size=%d chunks=%d @2=%.1f%% @4=%.1f%% @8=%.1f%%",
                    size, chunkVecs.size(), r2, r4, r8
            ));

            assertTrue(r2 <= r4 && r4 <= r8);
        }
    }

    private Map<String, String> loadCorpus() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:eval/corpus/*.md");
        Map<String, String> slugToContent = new LinkedHashMap<>();

        for (Resource resource : resources) {
            String slug = Objects.requireNonNull(resource.getFilename())
                    .replaceFirst("\\.md$", "");
            String text;
            try (InputStream inputStream = resource.getInputStream()) {
                text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            String[] parts = text.split("\\R", 2);
            String content = parts.length > 1 ? parts[1].trim() : "";
            if (content.isBlank()) {
                content = text;
            }
            slugToContent.put(slug, content);
        }

        return slugToContent;
    }

    private List<AnswerableQuestion> loadAnswerableQuestions() throws IOException {
        JsonNode answerable;
        try (InputStream inputStream = new ClassPathResource("eval/questions.json").getInputStream()) {
            answerable = new ObjectMapper().readTree(inputStream).get("answerable");
        }

        List<AnswerableQuestion> questions = new ArrayList<>();
        for (JsonNode node : answerable) {
            questions.add(new AnswerableQuestion(
                    node.get("question").asText(),
                    node.get("expectedDocSlug").asText()
            ));
        }
        return questions;
    }

    private boolean containsSlugAtK(List<String> topSlugs, String expectedDocSlug, int k) {
        return topSlugs.stream()
                .limit(k)
                .anyMatch(expectedDocSlug::equals);
    }

    private double hitRate(int hits, int total) {
        if (total == 0) {
            return 0.0;
        }
        return hits * 100.0 / total;
    }

    private record AnswerableQuestion(String question, String expectedDocSlug) {
    }

    private record ScoredSlug(String slug, double score) {
    }
}
