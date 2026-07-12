package com.yeonwoo.askwiki.config;

import com.yeonwoo.askwiki.search.EsVectorIndex;
import com.yeonwoo.askwiki.search.InMemoryVectorIndex;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class VectorIndexConfig {

    @Bean
    @Primary
    public VectorIndex activeVectorIndex(InMemoryVectorIndex inMemory,
                                         EsVectorIndex es,
                                         @Value("${askwiki.vector-index.impl:memory}") String impl) {
        return switch (impl) {
            case "memory" -> inMemory;
            case "elasticsearch" -> es;
            default -> throw new IllegalArgumentException("Unsupported vector index implementation: " + impl);
        };
    }
}
