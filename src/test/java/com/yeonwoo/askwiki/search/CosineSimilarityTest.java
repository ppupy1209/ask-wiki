package com.yeonwoo.askwiki.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CosineSimilarityTest {

    @Test
    void returnsOneForIdenticalVectors() {
        double similarity = SearchMath.cosineSimilarity(
                new float[]{1.0f, 2.0f, 3.0f},
                new float[]{1.0f, 2.0f, 3.0f}
        );

        assertEquals(1.0, similarity, 1.0e-9);
    }

    @Test
    void returnsZeroForOrthogonalVectors() {
        double similarity = SearchMath.cosineSimilarity(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        );

        assertEquals(0.0, similarity, 1.0e-9);
    }
}
