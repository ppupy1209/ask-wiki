package com.yeonwoo.askwiki.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

@Component
public class EmbeddingCodec {

    private final ObjectMapper objectMapper;

    public EmbeddingCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize embedding", exception);
        }
    }

    public float[] deserialize(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException("Failed to deserialize embedding", exception);
        }
    }
}
