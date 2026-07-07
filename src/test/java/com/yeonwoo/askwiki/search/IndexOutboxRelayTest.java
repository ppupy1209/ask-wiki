package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.CreateDocumentResponse;
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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class IndexOutboxRelayTest {

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
    IndexOutboxRelay relay;

    @Autowired
    Chunker chunker;

    @MockBean
    EmbeddingClient embeddingClient;

    @BeforeEach
    void rebuildVectorIndex() {
        outboxRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();
    }

    @Test
    void reflectsCommittedChunksAfterRelayRuns() {
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
    }

    @Test
    void isIdempotentOnRepeatedPolls() {
        createThreeChunkDocument();
        assertEquals(3, relay.processPendingEvents());

        int processed = relay.processPendingEvents();

        assertEquals(0, processed);
        assertEquals(3, vectorIndex.size());
        assertEquals(3, outboxRepository.findByStatusOrderByIdAsc(
                IndexOutboxEvent.Status.PROCESSED
        ).size());
    }

    @Test
    void doesNotDuplicateWhenChunkAlreadyIndexed() {
        createThreeChunkDocument();

        vectorIndex.rebuild();
        assertEquals(3, vectorIndex.size());

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
    void reflectsDeletionAfterRelayRuns() {
        String content = threeChunkContent();
        assertEquals(3, chunker.split(content).size());

        when(embeddingClient.embed(anyString()))
                .thenReturn(new float[]{1.0f, 0.0f})
                .thenReturn(new float[]{0.0f, 1.0f})
                .thenReturn(new float[]{1.0f, 1.0f});

        Long docId = documentService.create(new CreateDocumentRequest("to delete", content)).id();

        relay.processPendingEvents();
        assertEquals(3, vectorIndex.size());

        documentService.delete(docId);
        assertEquals(3, vectorIndex.size());

        relay.processPendingEvents();

        assertEquals(0, vectorIndex.size());
        assertEquals(IndexOutboxEvent.Status.PROCESSED, outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == IndexOutboxEvent.EventType.DOCUMENT_DELETED)
                .findFirst()
                .orElseThrow()
                .getStatus());

        relay.processPendingEvents();
        assertEquals(0, vectorIndex.size());
    }

    @Test
    void removeDocumentOnlyAffectsTargetDocument() {
        String content = threeChunkContent();
        assertEquals(3, chunker.split(content).size());

        when(embeddingClient.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        CreateDocumentResponse first = documentService.create(new CreateDocumentRequest("first", content));
        Long secondDocId = documentService.create(new CreateDocumentRequest("second", content)).id();

        relay.processPendingEvents();
        assertEquals(6, vectorIndex.size());

        documentService.delete(first.id());
        relay.processPendingEvents();

        assertEquals(3, vectorIndex.size());
        assertEquals(3, chunkRepository.countByDocumentId(secondDocId));
    }

    private void createThreeChunkDocument() {
        String content = threeChunkContent();
        assertEquals(3, chunker.split(content).size());

        when(embeddingClient.embed(anyString()))
                .thenReturn(new float[]{1.0f, 0.0f})
                .thenReturn(new float[]{0.0f, 1.0f})
                .thenReturn(new float[]{1.0f, 1.0f});

        documentService.create(new CreateDocumentRequest("committed create", content));
    }

    private String threeChunkContent() {
        return IntStream.range(0, 119)
                .mapToObj(i -> String.format("word%06d", i))
                .collect(Collectors.joining(" "));
    }
}
