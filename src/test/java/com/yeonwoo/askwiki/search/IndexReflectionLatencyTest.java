package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Chunker;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.document.DocumentService;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
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
 * B1 Step 3-7 측정: relay가 도입한 "커밋 → 인덱스 반영" 지연을 격리 측정한다.
 * 실제 스케줄러를 켜고(poll-interval-ms=200) create() 커밋 시각부터 인덱스 반영까지를 잰다.
 * 임베딩은 목이라 무시 — 여기서 보려는 건 폴링 지연뿐. 커밋 도착이 폴링 주기 T에 균등 분포하면
 * 기대값은 평균≈T/2, 최대≈T.
 */
@SpringBootTest(properties = {
        "askwiki.outbox.scheduler-enabled=true",
        "askwiki.outbox.poll-interval-ms=200"
})
@Testcontainers
class IndexReflectionLatencyTest {

    private static final long POLL_INTERVAL_MS = 200;
    private static final int TRIALS = 20;

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired
    DocumentService documentService;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    IndexOutboxRepository outboxRepository;

    @Autowired
    InMemoryVectorIndex vectorIndex;

    @Autowired
    Chunker chunker;

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
    void measuresReflectionLatencyUnderPolling() throws InterruptedException {
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        List<Long> latencies = new ArrayList<>();
        // 업로드는 폴링 시계와 무관하게 도착한다 — 커밋 시점을 폴링 주기에 무작위로 흩뿌려
        // "폴링 직후 도착"에 정렬되는 편향을 없앤다(고정 시드로 재현 가능).
        Random jitter = new Random(42);

        for (int i = 0; i < TRIALS; i++) {
            Thread.sleep(jitter.nextInt((int) POLL_INTERVAL_MS));

            int before = vectorIndex.size();
            long t0 = System.nanoTime();

            // 짧은 텍스트 → 1청크 → 커밋 시 outbox 이벤트 1건
            documentService.create(new CreateDocumentRequest("latency-" + i, "hello world " + i));

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (vectorIndex.size() == before) {
                if (System.nanoTime() > deadline) {
                    fail("reflection timed out (relay did not reflect within 5s)");
                }
                Thread.sleep(2);
            }

            latencies.add((System.nanoTime() - t0) / 1_000_000);
        }

        long max = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        long min = latencies.stream().mapToLong(Long::longValue).min().orElse(0);
        double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println(String.format(
                "[REFLECTION-LATENCY] pollMs=%d trials=%d min=%dms mean=%.1fms max=%dms samples=%s",
                POLL_INTERVAL_MS, TRIALS, min, mean, max, latencies));

        // 값 검증이 아니라 플레이키 방지용 느슨한 sanity 단언.
        assertTrue(latencies.size() == TRIALS);
        assertTrue(max <= POLL_INTERVAL_MS * 5, "max latency within generous bound");
    }
}
