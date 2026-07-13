package com.yeonwoo.askwiki.mcp;

import com.yeonwoo.askwiki.ask.AskOutcome;
import com.yeonwoo.askwiki.ask.AskService;
import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WikiMcpToolsTest {

    private static final String QUESTION = "휴가 신청 방법";

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final VectorIndex vectorIndex = mock(VectorIndex.class);
    private final AskService askService = mock(AskService.class);
    private final WikiMcpTools tools = new WikiMcpTools(embeddingClient, vectorIndex, askService);

    @Test
    void searchWikiEmbedsQueryAndDefaultsTopKToFour() {
        float[] embedding = {0.1f, 0.2f};
        List<ChunkMatch> expected = List.of(chunk());
        when(embeddingClient.embed(QUESTION)).thenReturn(embedding);
        when(vectorIndex.search(embedding, 4)).thenReturn(expected);

        List<ChunkMatch> result = tools.searchWiki(QUESTION, null);

        assertThat(result).isSameAs(expected);
        verify(embeddingClient).embed(QUESTION);
        verify(vectorIndex).search(embedding, 4);
        verifyNoInteractions(askService);
    }

    @Test
    void askWikiMapsAnsweredResult() {
        List<Source> sources = List.of(source());

        WikiAnswer result = askWith(new RagResult.Answered("휴가를 신청하세요.", sources));

        assertThat(result).isEqualTo(new WikiAnswer("휴가를 신청하세요.", sources));
    }

    @Test
    void askWikiMapsNoContextResult() {
        WikiAnswer result = askWith(new RagResult.NoContext());

        assertThat(result).isEqualTo(new WikiAnswer("관련 문서를 찾지 못했습니다.", List.of()));
    }

    @Test
    void askWikiMapsDegradedResult() {
        List<Source> sources = List.of(source());

        WikiAnswer result = askWith(new RagResult.Degraded("일시적으로 답변 생성이 어렵습니다.", sources));

        assertThat(result).isEqualTo(new WikiAnswer("일시적으로 답변 생성이 어렵습니다.", sources));
    }

    @Test
    void askWikiMapsLlmErrorResult() {
        WikiAnswer result = askWith(new RagResult.LlmError("연결 실패"));

        assertThat(result).isEqualTo(new WikiAnswer("답변 생성 중 오류가 발생했습니다: 연결 실패", List.of()));
    }

    private WikiAnswer askWith(RagResult result) {
        when(askService.ask(QUESTION, 4)).thenReturn(new AskOutcome(result, false));

        WikiAnswer answer = tools.askWiki(QUESTION, null);

        verify(askService).ask(QUESTION, 4);
        return answer;
    }

    private static ChunkMatch chunk() {
        return new ChunkMatch(1L, 2L, "휴가 규정", 0, "휴가 신청 절차", 0.9);
    }

    private static Source source() {
        return new Source(2L, "휴가 규정", 0, 0.9);
    }
}
