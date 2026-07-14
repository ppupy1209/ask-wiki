package com.yeonwoo.askwiki.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionRouterTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final QuestionRouter questionRouter = new QuestionRouter(chatModel);

    @Test
    void fallsOpenToWikiWhenChatClientFails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        QuestionRoute route = questionRouter.classify("휴가 신청 방법");

        assertThat(route).isEqualTo(new QuestionRoute(QuestionType.WIKI, ""));
    }
}
