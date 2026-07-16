package com.yeonwoo.askwiki.rag;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-request search tool for the agentic RAG path.
 *
 * <p>This is deliberately not a Spring bean: each {@link AgenticRagService} invocation needs an
 * isolated collector for the chunks used during that invocation.</p>
 */
public class WikiSearchTool {

    private final EmbeddingClient embeddingClient;
    private final VectorIndex vectorIndex;
    private final int topK;
    private final List<ChunkMatch> retrieved = new ArrayList<>();

    public WikiSearchTool(EmbeddingClient embeddingClient, VectorIndex vectorIndex, int topK) {
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.topK = topK;
    }

    @Tool(name = "search_wiki", description = "사내 위키 문서에서 질의로 관련 문서 조각을 검색한다. 답에 필요한 정보를 찾을 때까지 다른 키워드로 질의를 바꿔가며 여러 번 호출해도 된다.")
    public String search(@ToolParam(description = "검색 질의 (핵심 키워드로 재작성 가능)") String query) {
        List<ChunkMatch> hits = vectorIndex.search(query, embeddingClient.embed(query), topK);
        retrieved.addAll(hits);

        if (hits.isEmpty()) {
            return "검색 결과 없음";
        }

        return hits.stream()
                .map(hit -> "[" + hit.title() + "] " + hit.content())
                .collect(Collectors.joining("\n---\n"));
    }

    public List<ChunkMatch> retrieved() {
        return retrieved;
    }
}
