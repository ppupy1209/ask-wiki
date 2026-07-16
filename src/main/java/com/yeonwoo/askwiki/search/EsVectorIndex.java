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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EsVectorIndex implements VectorIndex {

    private static final int VECTOR_DIMS = 768;
    // 단일 벌크로 전량을 보내면 N이 커질 때 요청 본문(수백 MB)이 힙과 ES http.max_content_length(기본 100MB)를
    // 넘긴다(20k 스케일 벤치에서 OOM으로 실측). 기본 500자 청크 본문을 더해도 500건은 수 MB 수준이라
    // 벡터 페이로드가 지배적인 기존 배치 크기를 유지하고, 각 벌크 본문을 유계로 둔다.
    private static final int BULK_BATCH_SIZE = 500;

    private record IndexedChunk(Long documentId, int seq, String content, float[] vector) {}

    private final ElasticsearchClient client;
    private final EmbeddingCodec embeddingCodec;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final String indexName;
    private final boolean refreshOnWrite;

    public EsVectorIndex(ElasticsearchClient client,
                         EmbeddingCodec embeddingCodec,
                         ChunkRepository chunkRepository,
                         DocumentRepository documentRepository,
                         @Value("${askwiki.es.index:askwiki-chunks}") String indexName,
                         @Value("${askwiki.es.refresh-on-write:false}") boolean refreshOnWrite) {
        this.client = client;
        this.embeddingCodec = embeddingCodec;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.indexName = indexName;
        this.refreshOnWrite = refreshOnWrite;
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
     * Elasticsearch cosine kNN scores are normalized as (1 + cosine) / 2; convert them back to
     * cosine so B2 score-distribution comparisons keep InMemoryVectorIndex's [-1, 1] scale.
     */
    @Override
    public List<ChunkMatch> search(String queryText, float[] queryVector, int topK) {
        // B5-1 keeps the existing kNN-only path; B5-2 will use queryText for the BM25 branch.
        if (topK <= 0 || !indexExists()) {
            return List.of();
        }

        SearchResponse<IndexedChunk> response = execute("search Elasticsearch chunks", () -> client.search(search -> search
                .index(indexName)
                .knn(knn -> knn
                        .field("vector")
                        .queryVector(toFloatList(queryVector))
                        .k((long) topK)
                        .numCandidates(Math.max(100L, topK * 10L)))
                // content is indexed for future BM25, but ChunkMatch remains hydrated from MySQL.
                .source(source -> source.filter(filter -> filter.includes("documentId", "seq"))), IndexedChunk.class));
        List<Hit<IndexedChunk>> hits = response.hits().hits();

        // Fetch only the k matching rows; the vector index is never hydrated by a full DB scan.
        List<Long> chunkIds = hits.stream().map(hit -> Long.valueOf(hit.id())).toList();
        Map<Long, Chunk> chunks = chunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(Chunk::getId, Function.identity()));
        Set<Long> documentIds = hits.stream()
                .map(Hit::source)
                .map(IndexedChunk::documentId)
                .collect(Collectors.toSet());
        Map<Long, Document> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        return hits.stream().map(hit -> {
            IndexedChunk indexedChunk = hit.source();
            Long chunkId = Long.valueOf(hit.id());
            String title = Optional.ofNullable(documents.get(indexedChunk.documentId()))
                    .map(Document::getTitle)
                    .orElse("");
            String content = Optional.ofNullable(chunks.get(chunkId))
                    .map(Chunk::getContent)
                    .orElse("");
            double cosineScore = 2.0 * hit.score() - 1.0;
            return new ChunkMatch(chunkId, indexedChunk.documentId(), title, indexedChunk.seq(), content, cosineScore);
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
