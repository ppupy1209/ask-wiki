package com.yeonwoo.askwiki.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import com.yeonwoo.askwiki.search.EsVectorIndex;
import com.yeonwoo.askwiki.search.InMemoryVectorIndex;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C1 Step 3-② 스케일 벤치: N=20,000 청크에서 인메모리 브루트포스와 ES kNN(HNSW)을 나란히 측정한다.
 *
 * <p>재는 것 3가지:
 * <ul>
 *   <li><b>인덱스 구축 시간</b> — InMemory rebuild(전량 로드+정규화) vs ES rebuild(bulk 색인+HNSW 그래프 생성).</li>
 *   <li><b>검색 지연</b> — InMemory(인프로세스 O(N)) vs ES(네트워크 홉+HNSW). 20k에서 누가 이기는지, 왜인지.</li>
 *   <li><b>recall</b> — ES 근사가 InMemory의 정확한 top-k를 얼마나 놓치나. num_candidates 다이얼도 스윕.</li>
 * </ul>
 *
 * <p>벡터는 가우시안 랜덤(구면에 고르게 분포) — 군집 구조가 없어 HNSW에 최악 조건이다.
 * 실제 임베딩은 군집이 있어 recall이 더 높으므로, 여기 recall은 <b>보수적 하한</b>이다.
 *
 * <p>실제 Ollama는 불필요(임베딩은 목, 벡터는 합성). {@code ./gradlew evalTest}로 실행. 수 분 소요.
 */
@Tag("eval")
@SpringBootTest(properties = "askwiki.outbox.scheduler-enabled=false")
@Testcontainers
class VectorIndexScaleBenchTest {

    private static final int N = 20_000;
    private static final int DIM = 768;
    private static final int QUERIES = 200;
    private static final int WARMUP = 20;
    private static final int TOP_K = 4;
    private static final long SEED = 42L;

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("askwiki.es.url", elasticsearch::getHttpHostAddress);
        registry.add("askwiki.vector-index.impl", () -> "elasticsearch");
        registry.add("askwiki.es.refresh-on-write", () -> "false");
    }

    @Autowired
    InMemoryVectorIndex memoryIndex;

    @Autowired
    EsVectorIndex esIndex;

    @Autowired
    ElasticsearchClient esClient;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    EmbeddingCodec embeddingCodec;

    @Value("${askwiki.es.index:askwiki-chunks}")
    String indexName;

    @MockBean
    EmbeddingClient embeddingClient;

    @Test
    void benchmarksBruteForceVsAnnAtScale() {
        seedChunks(N);

        long memBuildMs = timeMs(memoryIndex::rebuild);
        long esBuildMs = timeMs(esIndex::rebuild);
        assertEquals(N, memoryIndex.size());
        assertEquals(N, esIndex.size());

        Random rnd = new Random(SEED);
        List<float[]> queries = new ArrayList<>(QUERIES);
        for (int i = 0; i < QUERIES; i++) {
            queries.add(gaussianVector(rnd));
        }

        // 워밍업(JIT·ES 캐시) — 타이밍에서 제외.
        for (int i = 0; i < WARMUP; i++) {
            memoryIndex.search(queries.get(i), TOP_K);
            esIndex.search(queries.get(i), TOP_K);
        }

        Stats memLatency = measureLatency(queries, q -> memoryIndex.search(q, TOP_K));
        Stats esLatency = measureLatency(queries, q -> esIndex.search(q, TOP_K));

        // recall: ES 프로덕션 경로(num_candidates=100) vs InMemory 정확 top-k.
        double prodRecall = averageRecall(queries, TOP_K, -1);

        // num_candidates 다이얼: 클수록 정확·느림. topK 이상만 유효.
        int[] sweep = {TOP_K, 25, 50, 100, 200};
        StringBuilder dial = new StringBuilder();
        for (int nc : sweep) {
            double recall = averageRecall(queries, TOP_K, nc);
            dial.append(String.format(" nc=%d:%.3f", nc, recall));
        }

        System.out.println(String.format(
                "[SCALE-BENCH] N=%d dim=%d queries=%d topK=%d", N, DIM, QUERIES, TOP_K));
        System.out.println(String.format(
                "[BUILD] memoryRebuildMs=%d esRebuildMs=%d", memBuildMs, esBuildMs));
        System.out.println(String.format(
                "[LATENCY-MEMORY] %s", memLatency));
        System.out.println(String.format(
                "[LATENCY-ES] %s", esLatency));
        System.out.println(String.format(
                "[RECALL] esProd(nc=100)@%d=%.3f  dial(recall@%d):%s", TOP_K, prodRecall, TOP_K, dial));
    }

    /** ES kNN 결과 chunkId 집합과 InMemory 정확 top-k의 겹침 비율을 질의마다 계산해 평균낸다. */
    private double averageRecall(List<float[]> queries, int topK, int numCandidates) {
        double sum = 0.0;
        for (float[] q : queries) {
            Set<Long> exact = memoryIndex.search(q, topK).stream()
                    .map(ChunkMatch::chunkId).collect(Collectors.toSet());
            Set<Long> approx = numCandidates < 0
                    ? esIndex.search(q, topK).stream().map(ChunkMatch::chunkId).collect(Collectors.toSet())
                    : rawEsKnnIds(q, topK, numCandidates);
            long overlap = approx.stream().filter(exact::contains).count();
            sum += exact.isEmpty() ? 1.0 : (double) overlap / exact.size();
        }
        return sum / queries.size();
    }

    /** num_candidates를 명시해 kNN을 직접 던진다(EsVectorIndex는 100 고정이라 다이얼 스윕용 별도 경로). */
    private Set<Long> rawEsKnnIds(float[] query, int topK, int numCandidates) {
        List<Float> vector = new ArrayList<>(query.length);
        for (float v : query) {
            vector.add(v);
        }
        try {
            SearchResponse<Void> response = esClient.search(s -> s
                    .index(indexName)
                    .knn(knn -> knn
                            .field("vector")
                            .queryVector(vector)
                            .k((long) topK)
                            .numCandidates((long) numCandidates))
                    .source(src -> src.fetch(false)), Void.class);
            return response.hits().hits().stream()
                    .map(hit -> Long.valueOf(hit.id())).collect(Collectors.toSet());
        } catch (Exception e) {
            throw new IllegalStateException("raw ES kNN failed", e);
        }
    }

    private Stats measureLatency(List<float[]> queries, Consumer<float[]> search) {
        long[] nanos = new long[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            long start = System.nanoTime();
            search.accept(queries.get(i));
            nanos[i] = System.nanoTime() - start;
        }
        return Stats.of(nanos);
    }

    private void seedChunks(int count) {
        Document doc = documentRepository.save(new Document("scale-bench", "bench"));
        Random rnd = new Random(SEED);
        List<Chunk> batch = new ArrayList<>(500);
        for (int i = 0; i < count; i++) {
            batch.add(new Chunk(doc.getId(), i, "bench chunk " + i,
                    embeddingCodec.serialize(gaussianVector(rnd)), 1));
            if (batch.size() == 500) {
                chunkRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            chunkRepository.saveAll(batch);
        }
    }

    private static float[] gaussianVector(Random rnd) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = (float) rnd.nextGaussian();
        }
        return v;
    }

    private static long timeMs(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private record Stats(double avgMs, double p50Ms, double p95Ms, double maxMs) {
        static Stats of(long[] nanos) {
            long[] sorted = nanos.clone();
            java.util.Arrays.sort(sorted);
            double sum = 0.0;
            for (long n : sorted) {
                sum += n;
            }
            double avg = sum / sorted.length / 1_000_000.0;
            double p50 = sorted[(int) (sorted.length * 0.50)] / 1_000_000.0;
            double p95 = sorted[(int) (sorted.length * 0.95)] / 1_000_000.0;
            double max = sorted[sorted.length - 1] / 1_000_000.0;
            return new Stats(avg, p50, p95, max);
        }

        @Override
        public String toString() {
            return String.format("avgMs=%.2f p50Ms=%.2f p95Ms=%.2f maxMs=%.2f", avgMs, p50Ms, p95Ms, maxMs);
        }
    }
}
