package com.yeonwoo.askwiki.rag;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticRagServiceTest {

    private static final String QUESTION = "휴가 신청 방법";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final VectorIndex vectorIndex = mock(VectorIndex.class);
    private final AgenticRagService service = new AgenticRagService(chatModel, embeddingClient, vectorIndex);

    @Test
    void searchToolFormatsResultsAndCollectsEverySearchHit() {
        float[] embedding = {0.1f, 0.2f};
        ChunkMatch hit = chunk(1L, 10L, "휴가 규정", 0, "휴가는 사전 승인 후 사용합니다.", 0.91);
        WikiSearchTool tool = new WikiSearchTool(embeddingClient, vectorIndex, 4);
        when(embeddingClient.embed(QUESTION)).thenReturn(embedding);
        when(vectorIndex.search(QUESTION, embedding, 4)).thenReturn(List.of(hit));

        String result = tool.search(QUESTION);

        assertThat(result).isEqualTo("[휴가 규정] 휴가는 사전 승인 후 사용합니다.");
        assertThat(tool.retrieved()).containsExactly(hit);
        verify(embeddingClient).embed(QUESTION);
        verify(vectorIndex).search(QUESTION, embedding, 4);
    }

    @Test
    void searchToolReportsNoResultsAndKeepsCollectorEmpty() {
        float[] embedding = {0.1f};
        WikiSearchTool tool = new WikiSearchTool(embeddingClient, vectorIndex, 4);
        when(embeddingClient.embed(QUESTION)).thenReturn(embedding);
        when(vectorIndex.search(QUESTION, embedding, 4)).thenReturn(List.of());

        String result = tool.search(QUESTION);

        assertThat(result).isEqualTo("검색 결과 없음");
        assertThat(tool.retrieved()).isEmpty();
    }

    @Test
    void mapsCollectedChunksToDeduplicatedSourcesInFirstSeenOrder() {
        ChunkMatch first = chunk(1L, 10L, "휴가 규정", 0, "첫 번째 내용", 0.91);
        ChunkMatch duplicate = chunk(1L, 99L, "다른 제목", 3, "중복 청크", 0.50);
        ChunkMatch second = chunk(2L, 20L, "승인 절차", 1, "두 번째 내용", 0.80);

        RagResult result = AgenticRagService.mapResult("휴가는 사전 승인 후 사용합니다.",
                List.of(first, duplicate, second));

        assertThat(result).isInstanceOf(RagResult.Answered.class);
        RagResult.Answered answered = (RagResult.Answered) result;
        assertThat(answered.answer()).isEqualTo("휴가는 사전 승인 후 사용합니다.");
        assertThat(answered.sources()).containsExactly(
                new Source(10L, "휴가 규정", 0, 0.91),
                new Source(20L, "승인 절차", 1, 0.80));
    }

    @Test
    void mapsMissingEvidenceOrRefusalToNoContext() {
        assertThat(AgenticRagService.mapResult("답변", List.of())).isInstanceOf(RagResult.NoContext.class);
        assertThat(AgenticRagService.mapResult("모르겠습니다", List.of(chunk(1L, 10L, "휴가 규정", 0, "내용", 0.91))))
                .isInstanceOf(RagResult.NoContext.class);
    }

    @Test
    void mapsChatClientFailureToLlmError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        RagResult result = service.answer(QUESTION, 4);

        assertThat(result).isEqualTo(new RagResult.LlmError("boom"));
    }

    private static ChunkMatch chunk(Long chunkId, Long documentId, String title, int seq, String content, double score) {
        return new ChunkMatch(chunkId, documentId, title, seq, content, score);
    }
}
