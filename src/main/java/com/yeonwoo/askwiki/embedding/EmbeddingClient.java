package com.yeonwoo.askwiki.embedding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmbeddingClient {

    private final EmbeddingModel embeddingModel;
    private final Cache<String, float[]> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    public EmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return cache.get(text, embeddingModel::embed);
    }

    public List<float[]> embed(List<String> texts) {
        return texts.stream()
                .map(this::embed)
                .toList();
    }
}
