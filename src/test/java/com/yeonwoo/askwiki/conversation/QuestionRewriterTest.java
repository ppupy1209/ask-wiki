package com.yeonwoo.askwiki.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionRewriterTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final QuestionRewriter questionRewriter = new QuestionRewriter(chatModel);

    @Test
    void keepsOriginalQuestionWithoutCallingLlmForEmptyHistory() {
        assertThat(questionRewriter.rewrite("반차는?", null)).isEqualTo("반차는?");
        assertThat(questionRewriter.rewrite("반차는?", List.of())).isEqualTo("반차는?");

        verifyNoInteractions(chatModel);
    }

    @Test
    void fallsOpenToOriginalQuestionWhenLlmFails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThat(questionRewriter.rewrite("그럼 반차는?", List.of(new UserMessage("연차는 며칠인가요?"))))
                .isEqualTo("그럼 반차는?");
    }

    @Test
    void buildsPromptFromConversationHistoryDeterministically() {
        List<Message> history = List.of(
                new UserMessage("연차는 며칠인가요?"),
                new AssistantMessage("15일입니다.")
        );

        String prompt = QuestionRewriter.buildUserPrompt("그럼 반차는?", history);

        assertThat(prompt).contains(
                "이전 대화:",
                "사용자: 연차는 며칠인가요?",
                "도우미: 15일입니다.",
                "마지막 사용자 질문: 그럼 반차는?"
        );
    }
}
