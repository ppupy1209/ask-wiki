package com.yeonwoo.askwiki.common;

public record Source(Long documentId, String title, int chunkSeq, double score) {
}
