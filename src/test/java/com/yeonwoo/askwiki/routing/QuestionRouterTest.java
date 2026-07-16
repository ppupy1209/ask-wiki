package com.yeonwoo.askwiki.routing;

import com.yeonwoo.askwiki.rag.LlmCallGuard;
import com.yeonwoo.askwiki.rag.LlmMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionRouterTest {

    private static final QuestionRoute WIKI = new QuestionRoute(QuestionType.WIKI, "");

    private final ChatModel chatModel = mock(ChatModel.class);
    private final LlmCallGuard llmCallGuard = new LlmCallGuard(8, 20000, 2000);
    private final LlmMetrics llmMetrics = mock(LlmMetrics.class);
    private final QuestionRouter questionRouter = new QuestionRouter(chatModel, llmCallGuard, llmMetrics);

    @Test
    void fallsOpenToWikiWhenChatClientFails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        QuestionRoute route = questionRouter.classify("휴가 신청 방법");

        assertThat(route).isEqualTo(WIKI);

    }
    @Test
    void fallsOpenAndRecordsDegradedWhenGuardTimesOut() {
        LlmCallGuard timeoutGuard = new LlmCallGuard(8, 50, 2000);
        QuestionRouter router = new QuestionRouter(chatModel, timeoutGuard, llmMetrics);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(500);
            return mock(ChatResponse.class);
        });

        assertThat(router.classify("휴가 신청 방법")).isEqualTo(WIKI);

        verify(llmMetrics).recordDegraded(eq(LlmMetrics.PURPOSE_CLASSIFY), anyString());
    }

    @Test
    void normalizesUnusableClassificationToWiki() {
        // 비-WIKI인데 message가 비면 사용자에게 내보낼 응답이 없다 → 이행 불가 → 분류 실패와 동일하게 검색으로.
        assertThat(QuestionRouter.normalize(null)).isEqualTo(WIKI);
        assertThat(QuestionRouter.normalize(new QuestionRoute(null, "타입 없음"))).isEqualTo(WIKI);
        assertThat(QuestionRouter.normalize(new QuestionRoute(QuestionType.CHITCHAT, null))).isEqualTo(WIKI);
        assertThat(QuestionRouter.normalize(new QuestionRoute(QuestionType.CHITCHAT, "   "))).isEqualTo(WIKI);
        assertThat(QuestionRouter.normalize(new QuestionRoute(QuestionType.AMBIGUOUS, null))).isEqualTo(WIKI);
        assertThat(QuestionRouter.normalize(new QuestionRoute(QuestionType.AMBIGUOUS, ""))).isEqualTo(WIKI);
    }

    @Test
    void keepsUsableClassificationAsIs() {
        QuestionRoute chitchat = new QuestionRoute(QuestionType.CHITCHAT, "안녕하세요. 무엇을 도와드릴까요?");
        QuestionRoute ambiguous = new QuestionRoute(QuestionType.AMBIGUOUS, "어떤 규정을 말씀하시나요?");

        assertThat(QuestionRouter.normalize(chitchat)).isSameAs(chitchat);
        assertThat(QuestionRouter.normalize(ambiguous)).isSameAs(ambiguous);
        // WIKI는 message를 쓰지 않으므로 비어 있어도 그대로 통과한다.
        assertThat(QuestionRouter.normalize(WIKI)).isSameAs(WIKI);
    }
}
