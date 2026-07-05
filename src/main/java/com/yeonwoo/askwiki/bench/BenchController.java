package com.yeonwoo.askwiki.bench;

import com.yeonwoo.askwiki.document.ChunkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * study ① (Virtual Threads) 측정용 엔드포인트.
 *
 * orchestrate: 메인. 한 요청을 처리하려고 여러 다운스트림 서비스를 순차 호출하고 DB도 조회하는
 *   실제 오케스트레이션(BFF/게이트웨이) 비즈니스 로직. 각 단계가 I/O 대기라서 동시 요청이 몰리면
 *   요청 처리 스레드가 오래 묶인다. 가상 스레드는 대기 동안 캐리어를 반납해 훨씬 많은 요청을 동시에 처리한다.
 * downstream: 단일 다운스트림 HTTP 호출 (비교용).
 * io: 네트워크 없이 순수 대기만 흉내내는 단순 비교용.
 */
@RestController
@RequestMapping("/api/bench")
public class BenchController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String downstreamUrl;
    private final ChunkRepository chunkRepository;

    public BenchController(@Value("${DOWNSTREAM_URL:http://httpbin:8080}") String downstreamUrl,
                           ChunkRepository chunkRepository) {
        this.downstreamUrl = downstreamUrl;
        this.chunkRepository = chunkRepository;
    }

    /** 순수 대기 (가장 단순, 비교용) */
    @GetMapping("/io")
    public String simulateIo(@RequestParam(defaultValue = "200") long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return "ok";
    }

    /** 단일 다운스트림 HTTP 호출 (비교용) */
    @GetMapping("/downstream")
    public String downstream(@RequestParam(defaultValue = "200") long delayMs) throws Exception {
        return "downstream status: " + callService(delayMs);
    }

    /**
     * 실제 오케스트레이션 비즈니스 로직: 여러 서비스 호출 + DB 조회를 순차로 수행해 결과를 조립한다.
     * (인증 확인 → 문서 메타 조회(DB) → 관련 자료 보강 → 감사 로그 적재)
     */
    @GetMapping("/orchestrate")
    public Map<String, Object> orchestrate(@RequestParam(defaultValue = "120") long ms) throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();

        // 1) 권한 확인 (인증 서비스 호출)
        steps.add(Map.of("step", "auth", "status", callService(ms)));

        // 2) 문서 메타 조회 (DB)
        steps.add(Map.of("step", "metadata", "chunkCount", chunkRepository.count()));

        // 3) 관련 자료 보강 (보강 서비스 호출)
        steps.add(Map.of("step", "enrich", "status", callService(ms)));

        // 4) 감사 로그 적재 (감사 서비스 호출)
        steps.add(Map.of("step", "audit", "status", callService(ms)));

        return Map.of("ok", true, "steps", steps);
    }

    /** 다운스트림 서비스 1회 호출 (ms 뒤 응답). 응답이 올 때까지 이 스레드가 blocking. */
    private int callService(long ms) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downstreamUrl + "/delay/" + (ms / 1000.0)))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
