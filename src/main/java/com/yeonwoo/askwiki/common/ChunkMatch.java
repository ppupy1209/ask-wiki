package com.yeonwoo.askwiki.common;

public record ChunkMatch(
        Long chunkId,
        Long documentId,
        String title,
        int seq,
        String content,
        double score
) {
}
