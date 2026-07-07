package com.yeonwoo.askwiki.search;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.document.Chunk;
import com.yeonwoo.askwiki.document.ChunkRepository;
import com.yeonwoo.askwiki.document.Document;
import com.yeonwoo.askwiki.document.DocumentRepository;
import com.yeonwoo.askwiki.embedding.EmbeddingCodec;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * study ③ 최적화: 인메모리 벡터 인덱스.
 *
 * baseline(SearchService)은 매 검색마다 전체 청크를 DB에서 읽고 JSON 파싱한다(O(N) 전수 스캔).
 * 여기서는 임베딩을 앱 시작 시 메모리에 한 번만 올려 미리 정규화해 둔다.
 * 검색은 메모리 안에서 내적(정규화했으므로 코사인과 동일)만 계산하고, 본문/제목은 상위 K건만 DB에서 가져온다.
 * 즉 매 검색의 "DB 전체 읽기 + 파싱" 비용을 제거한다.
 */
@Component
public class InMemoryVectorIndex {

    private record Entry(Long chunkId, Long documentId, int seq, float[] vector) {}

    private record Scored(Entry entry, double score) {}

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingCodec embeddingCodec;

    private final AtomicReference<List<Entry>> entriesRef =
            new AtomicReference<>(new ArrayList<>());

    public InMemoryVectorIndex(ChunkRepository chunkRepository,
                               DocumentRepository documentRepository,
                               EmbeddingCodec embeddingCodec) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.embeddingCodec = embeddingCodec;
    }

    /** 앱 시작 시 1회 전체 로드. 이후 수동 호출로도 재빌드 가능. */
    @EventListener(ApplicationReadyEvent.class)
    public int rebuild() {
        List<Entry> next = new ArrayList<>();
        for (Chunk c : chunkRepository.findAllByOrderByIdAsc()) {
            next.add(toEntry(c));
        }
        entriesRef.set(next);
        return next.size();
    }

    /** 문서 생성 시 새 청크를 증분 추가(전체 재빌드 없이). */
    public void add(Chunk chunk) {
        // 재처리/재시작-rebuild 중복 방지.
        entriesRef.updateAndGet(cur -> {
            if (cur.stream().anyMatch(entry -> entry.chunkId().equals(chunk.getId()))) {
                return cur;
            }
            List<Entry> next = new ArrayList<>(cur);
            next.add(toEntry(chunk));
            return next;
        });
    }

    public void removeDocument(Long documentId) {
        // Idempotently drops all chunks for the deleted document.
        entriesRef.updateAndGet(cur -> cur.stream()
                .filter(entry -> !entry.documentId().equals(documentId))
                .collect(Collectors.toList()));
    }

    public int size() {
        return entriesRef.get().size();
    }

    public List<ChunkMatch> search(float[] query, int topK) {
        float[] q = normalize(query);
        List<Entry> snapshot = entriesRef.get();

        List<Scored> top = snapshot.stream()
                .map(e -> new Scored(e, dot(q, e.vector())))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(Math.max(0, topK))
                .toList();

        // 본문/제목은 상위 K건만 DB에서 조회 (전수 읽기 없음)
        List<Long> chunkIds = top.stream().map(s -> s.entry().chunkId()).toList();
        Map<Long, Chunk> chunks = chunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(Chunk::getId, Function.identity()));
        Set<Long> docIds = top.stream().map(s -> s.entry().documentId()).collect(Collectors.toSet());
        Map<Long, Document> docs = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        return top.stream().map(s -> {
            Entry e = s.entry();
            String title = Optional.ofNullable(docs.get(e.documentId())).map(Document::getTitle).orElse("");
            String content = Optional.ofNullable(chunks.get(e.chunkId())).map(Chunk::getContent).orElse("");
            return new ChunkMatch(e.chunkId(), e.documentId(), title, e.seq(), content, s.score());
        }).toList();
    }

    private Entry toEntry(Chunk c) {
        return new Entry(c.getId(), c.getDocumentId(), c.getSeq(),
                normalize(embeddingCodec.deserialize(c.getEmbedding())));
    }

    private static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return v.clone();
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0.0;
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
