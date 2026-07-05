package com.yeonwoo.askwiki.common;

public record DocumentSummary(Long id, String title, int chunkCount, String createdAt) {
}
