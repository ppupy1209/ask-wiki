package com.yeonwoo.askwiki.mcp;

import com.yeonwoo.askwiki.ask.AskOutcome;
import com.yeonwoo.askwiki.ask.AskService;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WikiMcpTools {

    private final EmbeddingClient embeddingClient;
    private final VectorIndex vectorIndex;
    private final AskService askService;

    // @Lazy on askService: 이 컴포넌트는 ToolCallbackProvider로 노출돼 챗 모델의 tool-calling 인프라
    // (toolCallbackResolver)가 기동 시 즉시 생성한다. askService → ragService → ChatModel로 이어져
    // ChatModel 생성 중 다시 이 빈이 필요해지는 순환(BeanCurrentlyInCreation)이 생긴다. 지연 주입으로 끊는다.
    // search_wiki 경로(embeddingClient·vectorIndex)는 ChatModel에 의존하지 않아 즉시 주입해도 안전하다.
    public WikiMcpTools(EmbeddingClient embeddingClient, VectorIndex vectorIndex, @Lazy AskService askService) {
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.askService = askService;
    }

    @Tool(name = "search_wiki", description = "사내 위키 문서에서 질의와 의미적으로 유사한 청크를 검색한다. LLM 생성 없이 검색 결과(근거 청크)만 반환한다. 원문 근거가 필요할 때 사용.")
    public List<ChunkMatch> searchWiki(
            @ToolParam(description = "검색할 자연어 질의") String query,
            @ToolParam(description = "반환할 최대 청크 수 (기본 4)", required = false) Integer topK) {
        int k = (topK == null || topK < 1) ? 4 : topK;
        return vectorIndex.search(query, embeddingClient.embed(query), k);
    }

    @Tool(name = "ask_wiki", description = "사내 위키 문서를 근거로 질문에 답한다. 검색 후 LLM이 답변을 생성하고 출처를 함께 반환한다. 근거가 없으면 모른다고 답한다.")
    public WikiAnswer askWiki(
            @ToolParam(description = "사내 규정·매뉴얼에 대한 질문") String question,
            @ToolParam(description = "검색할 최대 청크 수 (기본 4)", required = false) Integer topK) {
        int k = (topK == null || topK < 1) ? 4 : topK;
        AskOutcome outcome = askService.ask(question, k);

        return switch (outcome.result()) {
            case RagResult.Answered answered -> new WikiAnswer(answered.answer(), answered.sources());
            case RagResult.NoContext ignored -> new WikiAnswer("관련 문서를 찾지 못했습니다.", List.of());
            case RagResult.Degraded degraded -> new WikiAnswer(degraded.message(), degraded.sources());
            case RagResult.LlmError error -> new WikiAnswer("답변 생성 중 오류가 발생했습니다: " + error.message(), List.of());
            case RagResult.Clarify clarify -> new WikiAnswer(clarify.message(), List.of());
        };
    }
}
