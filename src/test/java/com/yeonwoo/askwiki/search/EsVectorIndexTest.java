package com.yeonwoo.askwiki.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class EsVectorIndexTest {

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
    EsVectorIndex vectorIndex;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    EmbeddingCodec embeddingCodec;

    @Autowired
    ElasticsearchClient elasticsearchClient;

    @Value("${askwiki.es.index:askwiki-chunks}")
    String indexName;

    @MockBean
    EmbeddingClient embeddingClient;

    @BeforeEach
    void resetIndexes() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        vectorIndex.rebuild();
    }

    @Test
    void addIsIdempotentForTheSameChunk() {
        Chunk chunk = saveChunk(saveDocument("idempotency"), 0, "same chunk", vector(0, 1.0f));

        vectorIndex.add(chunk);
        vectorIndex.add(chunk);

        assertEquals(1, vectorIndex.size());
    }

    @Test
    void ranksBySimilarityAndRestoresCosineScoreRange() {
        Document document = saveDocument("ranking");
        Chunk closest = saveChunk(document, 0, "closest", vector(0, 1.0f));
        Chunk middle = saveChunk(document, 1, "middle", vector(0, 0.8f, 1, 0.2f));
        Chunk farthest = saveChunk(document, 2, "farthest", vector(1, 1.0f));
        vectorIndex.add(closest);
        vectorIndex.add(middle);
        vectorIndex.add(farthest);

        List<ChunkMatch> matches = vectorIndex.search(null, vector(0, 1.0f), 3);

        assertEquals(closest.getId(), matches.getFirst().chunkId());
        assertTrue(matches.stream().allMatch(match -> match.score() >= -1.0 && match.score() <= 1.0));
    }

    @Test
    void removeDocumentIsIdempotentAndLeavesOtherDocuments() {
        Document deleted = saveDocument("delete me");
        Document retained = saveDocument("keep me");
        vectorIndex.add(saveChunk(deleted, 0, "delete one", vector(0, 1.0f)));
        vectorIndex.add(saveChunk(deleted, 1, "delete two", vector(1, 1.0f)));
        vectorIndex.add(saveChunk(retained, 0, "keep", vector(2, 1.0f)));

        vectorIndex.removeDocument(deleted.getId());
        assertEquals(1, vectorIndex.size());

        vectorIndex.removeDocument(deleted.getId());

        assertEquals(1, vectorIndex.size());
        List<ChunkMatch> matches = vectorIndex.search(null, vector(2, 1.0f), 10);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().allMatch(match -> match.documentId().equals(retained.getId())));
    }

    @Test
    void rebuildMatchesTheCurrentDatabaseChunkSet() {
        Document first = saveDocument("first");
        Document second = saveDocument("second");
        saveChunk(first, 0, "one", vector(0, 1.0f));
        saveChunk(first, 1, "two", vector(1, 1.0f));
        saveChunk(second, 0, "three", vector(2, 1.0f));

        int rebuilt = vectorIndex.rebuild();

        assertEquals(chunkRepository.count(), rebuilt);
        assertEquals(chunkRepository.count(), vectorIndex.size());
    }

    @Test
    void addIndexesChunkContentForFutureLexicalSearch() throws Exception {
        Chunk chunk = saveChunk(saveDocument("content"), 0, "연차 휴가는 승인 후 사용합니다.", vector(0, 1.0f));

        vectorIndex.add(chunk);

        GetResponse<Map> response = elasticsearchClient.get(get -> get
                .index(indexName)
                .id(chunk.getId().toString()), Map.class);
        assertTrue(response.found());
        assertEquals(chunk.getContent(), response.source().get("content"));
    }

    @Test
    void hybridSwitchOffKeepsKnnResultsIdenticalWhenQueryTextIsPresent() {
        Document document = saveDocument("hybrid off");
        vectorIndex.add(saveChunk(document, 0, "lexical-only-term", vector(1, 1.0f)));
        vectorIndex.add(saveChunk(document, 1, "semantic match", vector(0, 1.0f)));

        List<ChunkMatch> knnOnly = vectorIndex.search(null, vector(0, 1.0f), 2);
        List<ChunkMatch> queryTextProvided = vectorIndex.search("lexical-only-term", vector(0, 1.0f), 2);

        assertEquals(knnOnly, queryTextProvided);
    }

    @Test
    void hybridSearchSurfacesLexicalMatchThatKnnRanksLast() {
        Document document = saveDocument("hybrid lexical");
        Chunk lexicalOnly = saveChunk(document, 0, "lexical-only-term is documented here", vector(1, 1.0f));
        Chunk semanticBest = saveChunk(document, 1, "semantic result", vector(0, 1.0f));
        Chunk semanticSecond = saveChunk(document, 2, "another semantic result", vector(0, 0.9f, 2, 0.1f));
        vectorIndex.add(lexicalOnly);
        vectorIndex.add(semanticBest);
        vectorIndex.add(semanticSecond);

        List<ChunkMatch> knnOnly = vectorIndex.search(null, vector(0, 1.0f), 3);
        List<ChunkMatch> hybrid = hybridVectorIndex().search("lexical-only-term", vector(0, 1.0f), 1);

        assertEquals(lexicalOnly.getId(), knnOnly.getLast().chunkId());
        assertEquals(lexicalOnly.getId(), hybrid.getFirst().chunkId());
        assertTrue(hybrid.getFirst().score() > 0.0 && hybrid.getFirst().score() < 0.1);
    }

    @Test
    void hybridSearchWithNullQueryTextFallsBackToKnn() {
        Document document = saveDocument("null query text");
        vectorIndex.add(saveChunk(document, 0, "first", vector(0, 1.0f)));
        vectorIndex.add(saveChunk(document, 1, "second", vector(1, 1.0f)));

        List<ChunkMatch> expected = vectorIndex.search(null, vector(0, 1.0f), 2);
        List<ChunkMatch> actual = hybridVectorIndex().search(null, vector(0, 1.0f), 2);

        assertEquals(expected, actual);
    }

    @Test
    void hybridStartupRebuildsAnExistingIndexWhenContentIsMissing() throws Exception {
        float[] chunkVector = vector(0, 1.0f);
        Chunk chunk = saveChunk(saveDocument("missing content"), 0, "rebuild must restore this content", chunkVector);
        indexWithoutContent(chunk, chunkVector);

        hybridVectorIndex().rebuildForHybridSearch();

        GetResponse<Map> response = elasticsearchClient.get(get -> get
                .index(indexName)
                .id(chunk.getId().toString()), Map.class);
        assertTrue(response.found());
        assertEquals(chunk.getContent(), response.source().get("content"));
    }

    @Test
    void hybridStartupIndexesExistingChunksWhenTheIndexIsMissing() throws Exception {
        Chunk chunk = saveChunk(saveDocument("no index yet"), 0, "hybrid must be able to find this", vector(0, 1.0f));
        elasticsearchClient.indices().delete(delete -> delete.index(indexName));

        hybridVectorIndex().rebuildForHybridSearch();

        GetResponse<Map> response = elasticsearchClient.get(get -> get
                .index(indexName)
                .id(chunk.getId().toString()), Map.class);
        assertTrue(response.found());
        assertEquals(chunk.getContent(), response.source().get("content"));
    }

    @Test
    void hybridStartupDropsIndexedChunksThatNoLongerExistInTheDatabase() throws Exception {
        Chunk stale = saveChunk(saveDocument("earlier corpus"), 0, "stale chunk", vector(0, 1.0f));
        vectorIndex.add(stale);
        Long staleId = stale.getId();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        Chunk current = saveChunk(saveDocument("current corpus"), 0, "current chunk", vector(0, 1.0f));

        hybridVectorIndex().rebuildForHybridSearch();

        // The stale chunk left the index at the database's own count, so a count check cannot detect it.
        assertEquals(1, vectorIndex.size());
        assertFalse(elasticsearchClient.get(get -> get
                .index(indexName)
                .id(staleId.toString()), Map.class).found());
        assertTrue(elasticsearchClient.get(get -> get
                .index(indexName)
                .id(current.getId().toString()), Map.class).found());
    }

    private Document saveDocument(String title) {
        return documentRepository.save(new Document(title, "test"));
    }

    private Chunk saveChunk(Document document, int seq, String content, float[] vector) {
        return chunkRepository.save(new Chunk(
                document.getId(), seq, content, embeddingCodec.serialize(vector), vector.length
        ));
    }

    private EsVectorIndex hybridVectorIndex() {
        return new EsVectorIndex(elasticsearchClient, embeddingCodec, chunkRepository, documentRepository,
                indexName, true, true, "elasticsearch", 0);
    }

    private void indexWithoutContent(Chunk chunk, float[] vector) throws Exception {
        elasticsearchClient.index(index -> index
                .index(indexName)
                .id(chunk.getId().toString())
                .document(Map.of(
                        "documentId", chunk.getDocumentId(),
                        "seq", chunk.getSeq(),
                        "vector", vectorValues(vector))));
        elasticsearchClient.indices().refresh(refresh -> refresh.index(indexName));
    }

    private List<Float> vectorValues(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private float[] vector(int firstIndex, float firstValue) {
        float[] vector = new float[768];
        vector[firstIndex] = firstValue;
        return vector;
    }

    private float[] vector(int firstIndex, float firstValue, int secondIndex, float secondValue) {
        float[] vector = vector(firstIndex, firstValue);
        vector[secondIndex] = secondValue;
        return vector;
    }
}
