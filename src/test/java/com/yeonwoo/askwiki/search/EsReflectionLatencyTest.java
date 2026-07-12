package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * C1 Step 3-③ 측정: ES 이행이 반영 지연에 더하는 <b>refresh 계층</b>을 격리한다.
 *
 * <p>B1(인메모리)의 반영 지연 = relay 폴링 지연뿐(평균≈T/2, pollMs=200에서 126.7ms 실측).
 * ES는 여기에 한 겹을 더한다: relay가 {@code add()}로 색인해도 문서는 <b>다음 refresh까지 검색에 안 보인다</b>
 * (ES near-realtime, 기본 index.refresh_interval=1s). {@code refresh-on-write=false}(프로덕션 기본)에서
 * create→검색가능(=count 반영)까지를 잰다. count/search는 refresh된 세그먼트만 보므로 size() 증가 시점이 곧 "검색가능" 시점.
 *
 * <p>같은 pollMs=200·같은 지터 방법론으로 B1과 비교 가능 — 델타가 순수 ES refresh 기여분이다.
 * 실제 Ollama 불필요(임베딩 목). {@code ./gradlew evalTest}로 실행.
 */
@Tag("eval")
@SpringBootTest(properties = {
        "askwiki.outbox.scheduler-enabled=true",
        "askwiki.outbox.poll-interval-ms=200",
        "askwiki.vector-index.impl=elasticsearch",
        "askwiki.es.refresh-on-write=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EsReflectionLatencyTest {

    private static final long POLL_INTERVAL_MS = 200;
    private static final int TRIALS = 15;

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void esProperties(DynamicPropertyRegistry registry) {
        registry.add("askwiki.es.url", elasticsearch::getHttpHostAddress);
    }

    @Autowired
    DocumentService documentService;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    IndexOutboxRepository outboxRepository;

    @Autowired
    EsVectorIndex vectorIndex;

    @MockBean
    EmbeddingClient embeddingClient;

    @BeforeEach
    void reset() {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();
    }

    @Test
    void measuresReflectionLatencyWithEsRefreshLayer() throws InterruptedException {
        when(embeddingClient.embed(anyString())).thenReturn(unitVector());

        List<Long> latencies = new ArrayList<>();
        // 커밋 시점을 폴링 주기에 무작위로 흩뿌려 "폴링 직후 도착" 정렬 편향 제거(B1과 동일, 고정 시드).
        Random jitter = new Random(42);

        for (int i = 0; i < TRIALS; i++) {
            Thread.sleep(jitter.nextInt((int) POLL_INTERVAL_MS));

            int before = vectorIndex.size();
            long t0 = System.nanoTime();

            documentService.create(new CreateDocumentRequest("latency-" + i, "hello world " + i));

            long deadline = System.nanoTime() + 10_000_000_000L;
            while (vectorIndex.size() == before) {
                if (System.nanoTime() > deadline) {
                    fail("reflection timed out (not searchable within 10s)");
                }
                Thread.sleep(5);
            }

            latencies.add((System.nanoTime() - t0) / 1_000_000);
        }

        long max = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        long min = latencies.stream().mapToLong(Long::longValue).min().orElse(0);
        double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println(String.format(
                "[ES-REFLECTION-LATENCY] pollMs=%d refreshOnWrite=false trials=%d min=%dms mean=%.1fms max=%dms samples=%s",
                POLL_INTERVAL_MS, TRIALS, min, mean, max, latencies));

        assertTrue(latencies.size() == TRIALS);
        assertTrue(max <= 5_000, "max reflection within generous bound");
    }

    private float[] unitVector() {
        float[] v = new float[768];
        v[0] = 1.0f;
        return v;
    }
}
