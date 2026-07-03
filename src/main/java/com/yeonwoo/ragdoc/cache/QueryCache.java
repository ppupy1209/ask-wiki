package com.yeonwoo.ragdoc.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yeonwoo.ragdoc.common.RagResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Component
public class QueryCache {

    private final Cache<String, RagResult.Answered> cache;

    public QueryCache(MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats() // 히트/미스 통계 기록 → 관측 가능하게
                .build();
        // Micrometer에 등록 → /actuator/prometheus 에 cache_gets_total{cache="query_cache",result="hit|miss"} 노출
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "query_cache");
    }

    public Optional<RagResult.Answered> get(String question) {
        return Optional.ofNullable(cache.getIfPresent(normalize(question)));
    }

    public void put(String question, RagResult.Answered answer) {
        cache.put(normalize(question), answer);
    }

    private String normalize(String question) {
        return question.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
