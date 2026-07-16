package com.yeonwoo.askwiki.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EsVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(EsVectorIndex.class);
    private static final int VECTOR_DIMS = 768;
    // RRF's conventional k=60 dampens the difference between adjacent ranks while preserving
    // each retriever's ordering; the value is the standard constant from the original RRF work.
    private static final int RRF_K = 60;
    // Each retriever must contribute a wider candidate set than the caller will receive. Five
    // times topK, with a floor of 50, lets a document rescued by the other retriever compete in
    // fusion without making small product queries depend on only a few ranks.
    private static final long HYBRID_CANDIDATE_COUNT_FLOOR = 50;
    // 단일 벌크로 전량을 보내면 N이 커질 때 요청 본문(수백 MB)이 힙과 ES http.max_content_length(기본 100MB)를
    // 넘긴다(20k 스케일 벤치에서 OOM으로 실측). 기본 500자 청크 본문을 더해도 500건은 수 MB 수준이라
    // 벡터 페이로드가 지배적인 기존 배치 크기를 유지하고, 각 벌크 본문을 유계로 둔다.
    private static final int BULK_BATCH_SIZE = 500;

    private record IndexedChunk(Long documentId, int seq, String content, float[] vector) {}

    private record ScoredHit(Hit<IndexedChunk> hit, double score) {}

    private final ElasticsearchClient client;
    private final EmbeddingCodec embeddingCodec;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final String indexName;
    private final boolean refreshOnWrite;
    private final boolean hybridSearchEnabled;
    private final String vectorIndexImplementation;
    private final long numCandidatesOverride;

    public EsVectorIndex(ElasticsearchClient client,
                         EmbeddingCodec embeddingCodec,
                         ChunkRepository chunkRepository,
                         DocumentRepository documentRepository,
                         @Value("${askwiki.es.index:askwiki-chunks}") String indexName,
                         @Value("${askwiki.es.refresh-on-write:false}") boolean refreshOnWrite,
                         @Value("${askwiki.search.hybrid:false}") boolean hybridSearchEnabled,
                         @Value("${askwiki.vector-index.impl:memory}") String vectorIndexImplementation,
                         @Value("${askwiki.es.num-candidates:0}") long numCandidatesOverride) {
        this.client = client;
        this.embeddingCodec = embeddingCodec;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.indexName = indexName;
        this.refreshOnWrite = refreshOnWrite;
        this.hybridSearchEnabled = hybridSearchEnabled;
        this.vectorIndexImplementation = vectorIndexImplementation;
        this.numCandidatesOverride = numCandidatesOverride;
    }

    /**
     * kNN이 훑을 후보 수. 기본(0)은 기존 공식을 그대로 써 운영 동작을 불변으로 둔다.
     * <p>후보 수는 kNN 재현율을 좌우한다(C1 실측 dial: nc 100 → recall 0.83, nc 200 → 0.879). 그런데 하이브리드는
     * 융합 재료를 넓히려 더 많은 결과를 요구하므로 같은 공식이 벡터 단독보다 큰 nc를 만든다 — 그대로 A/B하면
     * <b>"하이브리드가 좋다"와 "후보를 더 봤다"가 뒤섞인다</b>. B5-3은 이 노브로 팔마다 nc를 고정해 둘을 분리한다.
     */
    private long numCandidatesFor(long resultSize) {
        if (numCandidatesOverride <= 0) {
            return Math.max(100L, resultSize * 10L);
        }
        // Elasticsearch는 num_candidates >= k를 요구한다.
        return Math.max(numCandidatesOverride, resultSize);
    }

    @Override
    public int rebuild() {
        if (indexExists()) {
            execute("delete Elasticsearch index", () -> client.indices().delete(d -> d.index(indexName)));
        }
        createIndex();

        List<Chunk> chunks = chunkRepository.findAllByOrderByIdAsc();
        for (int from = 0; from < chunks.size(); from += BULK_BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(from, Math.min(from + BULK_BATCH_SIZE, chunks.size()));
            BulkRequest.Builder request = new BulkRequest.Builder().index(indexName);
            for (Chunk chunk : batch) {
                request.operations(operation -> operation.index(index -> index
                        .id(chunk.getId().toString())
                        .document(toIndexedChunk(chunk))));
            }

            BulkResponse response = execute("bulk index chunks", () -> client.bulk(request.build()));
            if (response.errors()) {
                throw new IllegalStateException("Failed to bulk index chunks into " + indexName);
            }
        }

        refreshIndex();
        return chunks.size();
    }

    @Override
    public void add(Chunk chunk) {
        ensureIndex();

        IndexRequest.Builder<IndexedChunk> request = new IndexRequest.Builder<IndexedChunk>()
                .index(indexName)
                // A stable chunk ID makes replayed outbox events Elasticsearch upserts.
                .id(chunk.getId().toString())
                .document(toIndexedChunk(chunk));
        if (refreshOnWrite) {
            request.refresh(Refresh.True);
        }

        execute("index chunk", () -> client.index(request.build()));
    }

    @Override
    public void removeDocument(Long documentId) {
        ensureIndex();

        DeleteByQueryRequest.Builder request = new DeleteByQueryRequest.Builder()
                .index(indexName)
                .conflicts(Conflicts.Proceed)
                .query(query -> query.term(term -> term.field("documentId").value(documentId)));
        if (refreshOnWrite) {
            request.refresh(true);
        }

        execute("remove document chunks", () -> client.deleteByQuery(request.build()));
    }

    @Override
    public int size() {
        if (!indexExists()) {
            return 0;
        }
        return Math.toIntExact(execute("count Elasticsearch chunks",
                () -> client.count(count -> count.index(indexName)).count()));
    }

    /**
     * The kNN-only path converts Elasticsearch's normalized scores back to cosine, preserving
     * B2's {@code [-1, 1]} score scale. The hybrid path deliberately returns RRF fusion scores:
     * a fused rank cannot truthfully be represented as a single cosine similarity.
     */
    @Override
    public List<ChunkMatch> search(String queryText, float[] queryVector, int topK) {
        if (topK <= 0 || !indexExists()) {
            return List.of();
        }

        if (!hybridSearchEnabled || queryText == null || queryText.isBlank()) {
            return hydrate(searchKnn(queryVector, topK));
        }

        long candidateCount = Math.max(HYBRID_CANDIDATE_COUNT_FLOOR, (long) topK * 5);
        List<Hit<IndexedChunk>> knnHits = searchKnnHits(queryVector, candidateCount);
        List<Hit<IndexedChunk>> bm25Hits = searchBm25Hits(queryText, candidateCount);
        return hydrate(fuseWithRrf(knnHits, bm25Hits, topK));
    }

    /**
     * Rebuilds the index on startup whenever hybrid search is on, so the switch always starts from an
     * index that agrees with MySQL. Narrower triggers cannot detect every disagreement: a missing index
     * leaves existing chunks unsearchable, and chunks left from an earlier corpus can match MySQL's
     * count while holding different ids. The index is derived data, and this rebuild is charged only to
     * whoever turns hybrid on — the kNN-only default keeps Elasticsearch's zero-rebuild startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildForHybridSearch() {
        if (!hybridSearchEnabled || !"elasticsearch".equals(vectorIndexImplementation)) {
            return;
        }

        try {
            int indexedChunks = rebuild();
            log.info("Rebuilt Elasticsearch index {} with {} chunks for hybrid search", indexName, indexedChunks);
        } catch (RuntimeException exception) {
            // Elasticsearch is intentionally lazy at startup; a temporary outage must not stop the app.
            log.warn("Could not rebuild Elasticsearch index for hybrid search; continuing startup", exception);
        }
    }

    private List<ScoredHit> searchKnn(float[] queryVector, int topK) {
        return searchKnnHits(queryVector, topK).stream()
                .map(hit -> new ScoredHit(hit, 2.0 * hit.score() - 1.0))
                .toList();
    }

    private List<Hit<IndexedChunk>> searchKnnHits(float[] queryVector, long resultSize) {
        SearchResponse<IndexedChunk> response = execute("search Elasticsearch chunks", () -> client.search(search -> search
                .index(indexName)
                .knn(knn -> knn
                        .field("vector")
                        .queryVector(toFloatList(queryVector))
                        .k(resultSize)
                        .numCandidates(numCandidatesFor(resultSize)))
                // content is indexed for future BM25, but ChunkMatch remains hydrated from MySQL.
                .source(source -> source.filter(filter -> filter.includes("documentId", "seq"))), IndexedChunk.class));
        return response.hits().hits();
    }

    private List<Hit<IndexedChunk>> searchBm25Hits(String queryText, long resultSize) {
        SearchResponse<IndexedChunk> response = execute("search Elasticsearch chunk content", () -> client.search(search -> search
                .index(indexName)
                .query(query -> query.match(match -> match.field("content").query(queryText)))
                .size((int) resultSize)
                .source(source -> source.filter(filter -> filter.includes("documentId", "seq"))), IndexedChunk.class));
        return response.hits().hits();
    }

    private List<ScoredHit> fuseWithRrf(List<Hit<IndexedChunk>> knnHits,
                                         List<Hit<IndexedChunk>> bm25Hits,
                                         int topK) {
        Map<Long, Hit<IndexedChunk>> hitsByChunkId = new HashMap<>();
        Map<Long, Double> rrfScores = new HashMap<>();
        addRrfScores(knnHits, hitsByChunkId, rrfScores);
        addRrfScores(bm25Hits, hitsByChunkId, rrfScores);

        return rrfScores.entrySet().stream()
                .map(entry -> new ScoredHit(hitsByChunkId.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed()
                        .thenComparing(scored -> Long.valueOf(scored.hit().id())))
                .limit(topK)
                .toList();
    }

    private void addRrfScores(List<Hit<IndexedChunk>> hits,
                              Map<Long, Hit<IndexedChunk>> hitsByChunkId,
                              Map<Long, Double> rrfScores) {
        for (int index = 0; index < hits.size(); index++) {
            Hit<IndexedChunk> hit = hits.get(index);
            Long chunkId = Long.valueOf(hit.id());
            hitsByChunkId.putIfAbsent(chunkId, hit);
            int rank = index + 1;
            rrfScores.merge(chunkId, 1.0 / (RRF_K + rank), Double::sum);
        }
    }

    private List<ChunkMatch> hydrate(List<ScoredHit> hits) {

        // Fetch only the k matching rows; the vector index is never hydrated by a full DB scan.
        List<Long> chunkIds = hits.stream().map(hit -> Long.valueOf(hit.hit().id())).toList();
        Map<Long, Chunk> chunks = chunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(Chunk::getId, Function.identity()));
        Set<Long> documentIds = hits.stream()
                .map(ScoredHit::hit)
                .map(Hit::source)
                .map(IndexedChunk::documentId)
                .collect(Collectors.toSet());
        Map<Long, Document> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        return hits.stream().map(hit -> {
            IndexedChunk indexedChunk = hit.hit().source();
            Long chunkId = Long.valueOf(hit.hit().id());
            String title = Optional.ofNullable(documents.get(indexedChunk.documentId()))
                    .map(Document::getTitle)
                    .orElse("");
            String content = Optional.ofNullable(chunks.get(chunkId))
                    .map(Chunk::getContent)
                    .orElse("");
            return new ChunkMatch(chunkId, indexedChunk.documentId(), title, indexedChunk.seq(), content, hit.score());
        }).toList();
    }

    private synchronized void ensureIndex() {
        if (!indexExists()) {
            createIndex();
        }
    }

    private void createIndex() {
        execute("create Elasticsearch index", () -> client.indices().create(create -> create
                .index(indexName)
                .mappings(mapping -> mapping
                        .properties("documentId", property -> property.long_(value -> value))
                        .properties("seq", property -> property.integer(value -> value))
                        .properties("content", property -> property.text(text -> text))
                        .properties("vector", property -> property.denseVector(vector -> vector
                                .dims(VECTOR_DIMS)
                                .index(true)
                                .similarity("cosine"))))));
    }

    private boolean indexExists() {
        return execute("check Elasticsearch index", () -> client.indices()
                .exists(exists -> exists.index(indexName))
                .value());
    }

    private void refreshIndex() {
        execute("refresh Elasticsearch index", () -> client.indices().refresh(refresh -> refresh.index(indexName)));
    }

    private IndexedChunk toIndexedChunk(Chunk chunk) {
        // Elasticsearch's cosine similarity normalizes vectors while searching; retain the raw embedding here.
        return new IndexedChunk(chunk.getDocumentId(), chunk.getSeq(), chunk.getContent(),
                embeddingCodec.deserialize(chunk.getEmbedding()));
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private <T> T execute(String operation, EsOperation<T> action) {
        try {
            return action.run();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to " + operation, exception);
        }
    }

    @FunctionalInterface
    private interface EsOperation<T> {
        T run() throws IOException;
    }
}
