package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;

import java.util.List;

/**
 * Port for vector-index operations.
 */
public interface VectorIndex {

    int rebuild();

    /**
     * Adds a chunk idempotently, keyed by chunkId, so relay reprocessing and concurrent rebuild
     * races are safe.
     */
    void add(Chunk chunk);

    /**
     * Removes all chunks for the given documentId. This operation is idempotent.
     */
    void removeDocument(Long documentId);

    int size();

    /**
     * Guarantees a consistent snapshot read of the index.
     */
    List<ChunkMatch> search(float[] query, int topK);
}
