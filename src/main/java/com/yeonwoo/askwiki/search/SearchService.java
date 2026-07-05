package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingCodec embeddingCodec;

    public SearchService(
            ChunkRepository chunkRepository,
            DocumentRepository documentRepository,
            EmbeddingCodec embeddingCodec
    ) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.embeddingCodec = embeddingCodec;
    }

    /**
     * Performs an O(N) brute-force scan over every chunk embedding.
     */
    @Transactional(readOnly = true)
    public List<ChunkMatch> findSimilar(float[] queryVector, int topK) {
        // study #3: optimization point (candidate reduction / in-memory normalized index)
        List<Chunk> chunks = chunkRepository.findAllByOrderByIdAsc();
        Map<Long, Document> documents = documentRepository.findAllById(
                        chunks.stream().map(Chunk::getDocumentId).collect(Collectors.toSet())
                )
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        return chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, cosineSimilarity(
                        queryVector,
                        embeddingCodec.deserialize(chunk.getEmbedding())
                )))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(0, topK))
                .map(scored -> toMatch(scored.chunk(), scored.score(), documents))
                .toList();
    }

    private ChunkMatch toMatch(Chunk chunk, double score, Map<Long, Document> documents) {
        Document document = documents.get(chunk.getDocumentId());
        String title = document == null ? "" : document.getTitle();
        return new ChunkMatch(
                chunk.getId(),
                chunk.getDocumentId(),
                title,
                chunk.getSeq(),
                chunk.getContent(),
                score
        );
    }

    private double cosineSimilarity(float[] left, float[] right) {
        return SearchMath.cosineSimilarity(left, right);
    }

    private record ScoredChunk(Chunk chunk, double score) {
    }
}
