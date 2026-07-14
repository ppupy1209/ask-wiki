package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.routing.QuestionRoute;
import com.yeonwoo.askwiki.routing.QuestionRouter;
import com.yeonwoo.askwiki.routing.QuestionType;
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
    private final QuestionRouter questionRouter = mock(QuestionRouter.class);
    private final AskService askService = new AskService(ragService, queryCache, questionRouter, false);

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
        verify(questionRouter, never()).classify(anyString());
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
        verify(questionRouter, never()).classify(anyString());
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
        verify(questionRouter, never()).classify(anyString());
    }

    @Test
    void routesChitchatWithoutCallingRagOrCaching() {
        String question = "안녕하세요";
        String message = "안녕하세요. 사내 규정과 매뉴얼 관련 질문을 도와드릴 수 있습니다.";
        AskService routingAskService = new AskService(ragService, queryCache, questionRouter, true);
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(questionRouter.classify(question)).thenReturn(new QuestionRoute(QuestionType.CHITCHAT, message));

        AskOutcome outcome = routingAskService.ask(question, 4);

        assertThat(outcome.result()).isEqualTo(new RagResult.Answered(message, List.of()));
        assertThat(outcome.cached()).isFalse();
        verify(ragService, never()).answer(anyString(), anyInt());
        verify(queryCache, never()).put(anyString(), any(RagResult.Answered.class));
    }

    @Test
    void routesAmbiguousQuestionWithoutCallingRag() {
        String question = "그거 알려줘";
        String message = "어떤 규정을 말씀하시는지 구체적으로 알려주시겠어요?";
        AskService routingAskService = new AskService(ragService, queryCache, questionRouter, true);
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(questionRouter.classify(question)).thenReturn(new QuestionRoute(QuestionType.AMBIGUOUS, message));

        AskOutcome outcome = routingAskService.ask(question, 4);

        assertThat(outcome.result()).isEqualTo(new RagResult.Clarify(message));
        assertThat(outcome.cached()).isFalse();
        verify(ragService, never()).answer(anyString(), anyInt());
        verify(queryCache, never()).put(anyString(), any(RagResult.Answered.class));
    }

    @Test
    void routesWikiQuestionToRagAndCachesAnsweredResult() {
        String question = "휴가 신청 방법";
        RagResult.Answered answer = new RagResult.Answered("휴가를 신청하세요.", List.of(source()));
        AskService routingAskService = new AskService(ragService, queryCache, questionRouter, true);
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(questionRouter.classify(question)).thenReturn(new QuestionRoute(QuestionType.WIKI, ""));
        when(ragService.answer(question, 4)).thenReturn(answer);

        AskOutcome outcome = routingAskService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(answer);
        assertThat(outcome.cached()).isFalse();
        verify(ragService).answer(question, 4);
        verify(queryCache).put(question, answer);
    }

    @Test
    void usesCacheBeforeRoutingWhenRoutingIsEnabled() {
        String question = "휴가 신청 방법";
        RagResult.Answered answer = new RagResult.Answered("휴가를 신청하세요.", List.of(source()));
        AskService routingAskService = new AskService(ragService, queryCache, questionRouter, true);
        when(queryCache.get(question)).thenReturn(Optional.of(answer));

        AskOutcome outcome = routingAskService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(answer);
        assertThat(outcome.cached()).isTrue();
        verify(questionRouter, never()).classify(anyString());
        verify(ragService, never()).answer(anyString(), anyInt());
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
