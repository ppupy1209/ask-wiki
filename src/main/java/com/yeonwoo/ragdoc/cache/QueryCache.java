package com.yeonwoo.ragdoc.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.ragdoc.common.RagResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 질의 응답 캐시 (study ④). 실무처럼 Redis(분산 캐시)를 사용한다.
 * Caffeine(인스턴스 로컬)과 달리 앱 재시작·다중 인스턴스에도 캐시가 유지/공유된다.
 *
 * 히트/미스는 Micrometer 카운터로 직접 집계해 cache_gets_total{cache="query_cache",result="hit|miss"} 로 노출한다.
 * (Prometheus 규칙상 미터 이름 "cache.gets"가 지표 "cache_gets_total"이 된다. Grafana 대시보드 그대로 동작.)
 */
@Component
public class QueryCache {

    private static final String KEY_PREFIX = "rag:query:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Counter hitCounter;
    private final Counter missCounter;

    public QueryCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.hitCounter = Counter.builder("cache.gets")
                .tag("cache", "query_cache").tag("result", "hit").register(meterRegistry);
        this.missCounter = Counter.builder("cache.gets")
                .tag("cache", "query_cache").tag("result", "miss").register(meterRegistry);
    }

    public Optional<RagResult.Answered> get(String question) {
        String json = redis.opsForValue().get(key(question));
        if (json == null) {
            missCounter.increment();
            return Optional.empty();
        }
        hitCounter.increment();
        try {
            return Optional.of(objectMapper.readValue(json, RagResult.Answered.class));
        } catch (Exception e) {
            return Optional.empty(); // 역직렬화 실패 시 캐시 미스처럼 취급
        }
    }

    public void put(String question, RagResult.Answered answer) {
        try {
            redis.opsForValue().set(key(question), objectMapper.writeValueAsString(answer), TTL);
        } catch (Exception e) {
            // 캐시 저장 실패는 무시 (본 응답에는 영향 없음)
        }
    }

    private String key(String question) {
        return KEY_PREFIX + question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
