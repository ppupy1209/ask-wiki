package com.yeonwoo.askwiki.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryRepositoryTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(redis, new ObjectMapper(), 120);

    @Test
    void savesMessagesAsJsonWithTtl() {
        when(redis.opsForValue()).thenReturn(values);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        repository.saveAll("c1", List.of(new UserMessage("연차 며칠?"), new AssistantMessage("15일")));

        verify(values).set(eq("chat:mem:c1"), json.capture(), eq(Duration.ofMinutes(120)));
        assertThat(json.getValue()).contains("USER", "연차 며칠?", "ASSISTANT", "15일");
    }

    @Test
    void restoresUserAndAssistantMessages() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("chat:mem:c1")).thenReturn("""
                [{"type":"USER","text":"연차 며칠?"},{"type":"ASSISTANT","text":"15일"}]
                """);

        List<Message> messages = repository.findByConversationId("c1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(messages.get(0).getText()).isEqualTo("연차 며칠?");
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(1).getText()).isEqualTo("15일");
    }

    @Test
    void returnsEmptyListWhenMemoryIsMissing() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("chat:mem:c1")).thenReturn(null);

        assertThat(repository.findByConversationId("c1")).isEmpty();
    }

    @Test
    void returnsEmptyListWhenStoredJsonIsMalformed() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("chat:mem:c1")).thenReturn("not-json");

        assertThat(repository.findByConversationId("c1")).isEmpty();
    }
}
