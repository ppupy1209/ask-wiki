package com.yeonwoo.askwiki.bench;

import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import com.yeonwoo.askwiki.search.VectorIndex;
import com.yeonwoo.askwiki.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * study ③ (MySQL 벡터 검색 최적화) 측정 전용.
 *
 * seed:   임의 임베딩(768차원) 청크를 대량 주입해 N을 키운다 (Ollama 없이 빠르게).
 * search: LLM 없이 검색만 runs회 돌려 평균 응답시간을 잰다.
 *         mode=dbscan  baseline(O(N) DB 전수 스캔)
 *         mode=memory  최적화(인메모리 인덱스)
 */
@RestController
@RequestMapping("/api/bench")
public class SearchBenchController {

    private static final int DIM = 768; // nomic-embed-text 차원

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingCodec embeddingCodec;
    private final SearchService searchService;     // baseline
    private final VectorIndex vectorIndex; // 최적화

    public SearchBenchController(DocumentRepository documentRepository,
                                 ChunkRepository chunkRepository,
                                 EmbeddingCodec embeddingCodec,
                                 SearchService searchService,
                                 VectorIndex vectorIndex) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingCodec = embeddingCodec;
        this.searchService = searchService;
        this.vectorIndex = vectorIndex;
    }

    /** 임의 임베딩 청크 count개 주입 (500개씩 배치 insert) 후 인덱스 재빌드 */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "20000") int count) {
        Document doc = documentRepository.save(new Document("bench-seed", "bench"));
        Random rnd = new Random();
        List<Chunk> batch = new ArrayList<>(500);
        for (int i = 0; i < count; i++) {
            batch.add(new Chunk(doc.getId(), i, "bench chunk " + i,
                    embeddingCodec.serialize(randomVector(rnd)), 1));
            if (batch.size() == 500) {
                chunkRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            chunkRepository.saveAll(batch);
        }
        int indexed = vectorIndex.rebuild();
        return Map.of("inserted", count, "totalChunks", chunkRepository.count(), "indexed", indexed);
    }

    /** 인메모리 인덱스 수동 재빌드 */
    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuildIndex() {
        long start = System.nanoTime();
        int indexed = vectorIndex.rebuild();
        return Map.of("indexed", indexed, "rebuildMs", (System.nanoTime() - start) / 1_000_000);
    }

    /** 검색만 runs회 반복해 평균 응답시간(ms) 측정. mode=dbscan|memory */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "4") int topK,
                                      @RequestParam(defaultValue = "10") int runs,
                                      @RequestParam(defaultValue = "memory") String mode) {
        Random rnd = new Random();
        long totalNanos = 0;
        int lastMatches = 0;
        for (int r = 0; r < runs; r++) {
            float[] query = randomVector(rnd);
            long start = System.nanoTime();
            lastMatches = "dbscan".equals(mode)
                    ? searchService.findSimilar(query, topK).size()
                    : vectorIndex.search(query, topK).size();
            totalNanos += System.nanoTime() - start;
        }
        double avgMs = totalNanos / 1_000_000.0 / runs;
        return Map.of(
                "mode", mode,
                "totalChunks", chunkRepository.count(),
                "topK", topK,
                "runs", runs,
                "matches", lastMatches,
                "avgLatencyMs", Math.round(avgMs * 100.0) / 100.0
        );
    }

    private float[] randomVector(Random rnd) {
        float[] v = new float[DIM];
        for (int j = 0; j < DIM; j++) {
            v[j] = rnd.nextFloat();
        }
        return v;
    }
}
