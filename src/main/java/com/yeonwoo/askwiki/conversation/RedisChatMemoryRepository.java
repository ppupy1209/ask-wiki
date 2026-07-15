package com.yeonwoo.askwiki.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat:mem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisChatMemoryRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${askwiki.chat-memory.ttl-minutes:120}") long ttlMinutes) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public List<String> findConversationIds() {
        // 관리자 확인용이며 요청 경로에서는 쓰지 않는다. KEYS는 O(N)이다.
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys == null) {
                return List.of();
            }
            return keys.stream().map(key -> key.substring(KEY_PREFIX.length())).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            String json = redis.opsForValue().get(key(conversationId));
            if (json == null) {
                return List.of();
            }
            List<StoredMessage> stored = objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {
            });
            return stored.stream()
                    .map(this::toMessage)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (Exception e) {
            return List.of(); // 역직렬화 실패 시 메모리 미스처럼 취급
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = messages.stream()
                    .filter(message -> message.getMessageType() == MessageType.USER
                            || message.getMessageType() == MessageType.ASSISTANT)
                    .map(message -> new StoredMessage(message.getMessageType().name(), message.getText()))
                    .toList();
            redis.opsForValue().set(key(conversationId), objectMapper.writeValueAsString(stored), ttl);
        } catch (Exception e) {
            // 메모리 저장 실패는 본 응답에 영향을 주지 않는다.
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            // 메모리 삭제 실패는 본 응답에 영향을 주지 않는다.
        }
    }

    private Optional<Message> toMessage(StoredMessage storedMessage) {
        if ("USER".equals(storedMessage.type())) {
            return Optional.of(new UserMessage(storedMessage.text()));
        }
        if ("ASSISTANT".equals(storedMessage.type())) {
            return Optional.of(new AssistantMessage(storedMessage.text()));
        }
        return Optional.empty();
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}

record StoredMessage(String type, String text) {
}
