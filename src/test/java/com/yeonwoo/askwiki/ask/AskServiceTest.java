package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.conversation.QuestionRewriter;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.routing.QuestionRoute;
import com.yeonwoo.askwiki.routing.QuestionRouter;
import com.yeonwoo.askwiki.routing.QuestionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AskServiceTest {

    private final RagService ragService = mock(RagService.class);
    private final QueryCache queryCache = mock(QueryCache.class);
    private final QuestionRouter questionRouter = mock(QuestionRouter.class);
    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final QuestionRewriter questionRewriter = mock(QuestionRewriter.class);
    private final AskService askService = new AskService(
            ragService, queryCache, questionRouter, chatMemory, questionRewriter, false);

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
        AskService routingAskService = new AskService(
                ragService, queryCache, questionRouter, chatMemory, questionRewriter, true);
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
        AskService routingAskService = new AskService(
                ragService, queryCache, questionRouter, chatMemory, questionRewriter, true);
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
        AskService routingAskService = new AskService(
                ragService, queryCache, questionRouter, chatMemory, questionRewriter, true);
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
        AskService routingAskService = new AskService(
                ragService, queryCache, questionRouter, chatMemory, questionRewriter, true);
        when(queryCache.get(question)).thenReturn(Optional.of(answer));

        AskOutcome outcome = routingAskService.ask(question, 4);

        assertThat(outcome.result()).isSameAs(answer);
        assertThat(outcome.cached()).isTrue();
        verify(questionRouter, never()).classify(anyString());
        verify(ragService, never()).answer(anyString(), anyInt());
    }

    @Test
    void keepsSingleTurnPathUnchangedWhenConversationIdIsNull() {
        String question = "휴가 신청 방법";
        RagResult.Answered answer = new RagResult.Answered("휴가를 신청하세요.", List.of(source()));
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(ragService.answer(question, 4)).thenReturn(answer);

        askService.ask(question, 4, null);

        verify(queryCache).get(question);
        verify(ragService).answer(question, 4);
        verifyNoInteractions(chatMemory);
        verify(questionRewriter, never()).rewrite(any(), any());
    }

    @Test
    void recordsFirstTurnWithoutRewritingIt() {
        String question = "연차는 며칠인가요?";
        RagResult.Answered answer = new RagResult.Answered("15일입니다.", List.of(source()));
        when(chatMemory.get("c1")).thenReturn(List.of());
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(ragService.answer(question, 4)).thenReturn(answer);

        askService.ask(question, 4, "c1");

        verify(questionRewriter, never()).rewrite(any(), any());
        verify(queryCache).get(question);
        verify(ragService).answer(question, 4);
        verify(chatMemory).add(eq("c1"), org.mockito.ArgumentMatchers.<List<Message>>argThat(
                messages -> isConversationTurn(messages, question, "15일입니다.")));
    }

    @Test
    void rewritesFollowUpForRetrievalButRecordsOriginalQuestion() {
        String question = "그럼 반차는?";
        String standalone = "반차는 며칠인가요?";
        List<Message> history = List.of(new UserMessage("연차는 며칠인가요?"), new AssistantMessage("15일입니다."));
        RagResult.Answered answer = new RagResult.Answered("반차는 0.5일입니다.", List.of(source()));
        when(chatMemory.get("c1")).thenReturn(history);
        when(questionRewriter.rewrite(question, history)).thenReturn(standalone);
        when(queryCache.get(standalone)).thenReturn(Optional.empty());
        when(ragService.answer(standalone, 4)).thenReturn(answer);

        askService.ask(question, 4, "c1");

        verify(queryCache).get(standalone);
        verify(ragService).answer(standalone, 4);
        verify(chatMemory).add(eq("c1"), org.mockito.ArgumentMatchers.<List<Message>>argThat(
                messages -> isConversationTurn(messages, question, "반차는 0.5일입니다.")));
    }

    @Test
    void recordsCachedAnswerAsConversationTurn() {
        String question = "연차는 며칠인가요?";
        RagResult.Answered answer = new RagResult.Answered("15일입니다.", List.of(source()));
        when(chatMemory.get("c1")).thenReturn(List.of());
        when(queryCache.get(question)).thenReturn(Optional.of(answer));

        AskOutcome outcome = askService.ask(question, 4, "c1");

        assertThat(outcome.cached()).isTrue();
        verify(chatMemory).add(eq("c1"), org.mockito.ArgumentMatchers.<List<Message>>argThat(
                messages -> isConversationTurn(messages, question, "15일입니다.")));
    }

    @Test
    void doesNotRecordFailedLlmExchange() {
        String question = "연차는 며칠인가요?";
        when(chatMemory.get("c1")).thenReturn(List.of());
        when(queryCache.get(question)).thenReturn(Optional.empty());
        when(ragService.answer(question, 4)).thenReturn(new RagResult.LlmError("LLM 오류"));

        askService.ask(question, 4, "c1");

        verify(chatMemory, never()).add(anyString(), org.mockito.ArgumentMatchers.<List<Message>>any());
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

    private static boolean isConversationTurn(List<Message> messages, String question, String answer) {
        return messages.size() == 2
                && messages.get(0) instanceof UserMessage userMessage
                && userMessage.getText().equals(question)
                && messages.get(1) instanceof AssistantMessage assistantMessage
                && assistantMessage.getText().equals(answer);
    }
}
