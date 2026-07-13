package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.rag.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskServiceTest {

    private final RagService ragService = mock(RagService.class);
    private final QueryCache queryCache = mock(QueryCache.class);
    private final AskService askService = new AskService(ragService, queryCache);

    @Test
    void returnsCachedAnswerWithoutCallingRagService() {
        String question = "휴가 신청 방법";
        RagResult.Answered answer = new RagResult.Answered("휴가를 신청하세요.", List.of(source()));
        when(queryCache.get(question)).thenReturn(Optional.of(answer));

        AskOutcome outcome = askService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(answer);
        assertThat(outcome.cached()).isTrue();
        verify(ragService, never()).answer(anyString(), anyInt());
        verify(queryCache, never()).put(anyString(), any(RagResult.Answered.class));
    }

    @Test
    void cachesAnsweredResultAfterCacheMiss() {
        String question = "휴가 신청 방법";
        RagResult.Answered answer = new RagResult.Answered("휴가를 신청하세요.", List.of(source()));
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(ragService.answer(question, 4)).thenReturn(answer);

        AskOutcome outcome = askService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(answer);
        assertThat(outcome.cached()).isFalse();
        verify(ragService).answer(question, 4);
        verify(queryCache).put(question, answer);
    }

    @ParameterizedTest
    @MethodSource("nonCacheableResults")
    void doesNotCacheNonAnsweredResult(RagResult result) {
        String question = "휴가 신청 방법";
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(ragService.answer(question, 4)).thenReturn(result);

        AskOutcome outcome = askService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(result);
        assertThat(outcome.cached()).isFalse();
        verify(ragService).answer(question, 4);
        verify(queryCache, never()).put(anyString(), any(RagResult.Answered.class));
    }

    private static Stream<RagResult> nonCacheableResults() {
        return Stream.of(
                new RagResult.NoContext(),
                new RagResult.LlmError("LLM 오류"),
                new RagResult.Degraded("일시적으로 답변 생성이 어렵습니다.", List.of(source()))
        );
    }

    private static Source source() {
        return new Source(1L, "휴가 규정", 0, 0.9);
    }
}
