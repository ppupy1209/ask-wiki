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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class EsOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("askwiki.es.url", elasticsearch::getHttpHostAddress);
        registry.add("askwiki.vector-index.impl", () -> "elasticsearch");
        registry.add("askwiki.es.refresh-on-write", () -> "true");
        registry.add("askwiki.outbox.scheduler-enabled", () -> "false");
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
    IndexOutboxRelay relay;

    @Autowired
    Chunker chunker;

    @MockBean
    EmbeddingClient embeddingClient;

    @SpyBean
    EsVectorIndex vectorIndex;

    @BeforeEach
    void rebuildVectorIndex() {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();
    }

    @Test
    void ghostFreeWhenCreateRollsBack() {
        String content = threeChunkContent();
        assertEquals(3, chunker.split(content).size());

        when(embeddingClient.embed(anyString()))
                .thenReturn(oneHotVector(0))
                .thenReturn(oneHotVector(1))
                .thenThrow(new RuntimeException("third chunk embedding failed"));

        assertThrows(RuntimeException.class, () -> documentService.create(
                new CreateDocumentRequest("rollback reproduction", content)
        ));

        assertEquals(0, documentRepository.count());
        assertEquals(0, chunkRepository.count());
        assertEquals(0, outboxRepository.count());
        assertEquals(0, vectorIndex.size());
    }

    @Test
    void relayReflectsCommittedChunksAndIsIdempotent() {
        createThreeChunkDocument();

        assertEquals(0, vectorIndex.size());

        int processed = relay.processPendingEvents();

        assertEquals(3, processed);
        assertEquals(3, vectorIndex.size());
        assertEquals(0, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PENDING
        ).size());
        assertEquals(3, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PROCESSED
        ).size());

        assertEquals(0, relay.processPendingEvents());
        assertEquals(3, vectorIndex.size());
    }

    @Test
    void recoversWithoutLossWhenRelayCrashesMidBatch() {
        createThreeChunkDocument();

        assertEquals(3, outboxRepository.count());
        assertEquals(0, vectorIndex.size());

        AtomicInteger addCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (addCalls.incrementAndGet() == 2) {
                throw new RuntimeException("relay crashed before marking");
            }
            return invocation.callRealMethod();
        }).when(vectorIndex).add(any());

        assertThrows(RuntimeException.class, () -> relay.processPendingEvents());

        assertEquals(3, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PENDING
        ).size());
        assertEquals(0, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PROCESSED
        ).size());

        int processed = relay.processPendingEvents();

        assertEquals(3, processed);
        assertEquals(3, vectorIndex.size());
        assertEquals(0, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PENDING
        ).size());
        assertEquals(3, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PROCESSED
        ).size());
    }

    @Test
    void deleteFlowsThroughOutboxAndIsIdempotent() {
        Long documentId = createThreeChunkDocument();
        assertEquals(3, relay.processPendingEvents());
        assertEquals(3, vectorIndex.size());

        documentService.delete(documentId);
        assertEquals(3, vectorIndex.size());

        assertEquals(1, relay.processPendingEvents());
        assertEquals(0, vectorIndex.size());
        assertEquals(IndexOutboxEvent.Status.PROCESSED, outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == IndexOutboxEvent.EventType.DOCUMENT_DELETED)
                .findFirst()
                .orElseThrow()
                .getStatus());

        assertEquals(0, relay.processPendingEvents());
        assertEquals(0, vectorIndex.size());
    }

    private Long createThreeChunkDocument() {
        String content = threeChunkContent();
        assertEquals(3, chunker.split(content).size());

        when(embeddingClient.embed(anyString()))
                .thenReturn(oneHotVector(0))
                .thenReturn(oneHotVector(1))
                .thenReturn(oneHotVector(2));

        return documentService.create(new CreateDocumentRequest("committed create", content)).id();
    }

    private String threeChunkContent() {
        return IntStream.range(0, 119)
                .mapToObj(i -> String.format("word%06d", i))
                .collect(Collectors.joining(" "));
    }

    /** ES 매핑이 dims=768을 강제하므로 목 벡터도 768차원이어야 한다(2차원이면 색인 자체가 거부됨). */
    private float[] oneHotVector(int hotIndex) {
        float[] vector = new float[768];
        vector[hotIndex] = 1.0f;
        return vector;
    }
}
