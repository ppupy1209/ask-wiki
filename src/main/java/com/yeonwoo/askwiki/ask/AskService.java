package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.conversation.QuestionRewriter;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.routing.QuestionRoute;
import com.yeonwoo.askwiki.routing.QuestionRouter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AskService {

    private final RagService ragService;
    private final QueryCache queryCache;
    private final QuestionRouter questionRouter;
    private final ChatMemory chatMemory;
    private final QuestionRewriter questionRewriter;
    private final boolean routingEnabled;

    public AskService(
            RagService ragService,
            QueryCache queryCache,
            QuestionRouter questionRouter,
            ChatMemory chatMemory,
            QuestionRewriter questionRewriter,
            @Value("${askwiki.routing.enabled:false}") boolean routingEnabled) {
        this.ragService = ragService;
        this.queryCache = queryCache;
        this.questionRouter = questionRouter;
        this.chatMemory = chatMemory;
        this.questionRewriter = questionRewriter;
        this.routingEnabled = routingEnabled;
    }

    public AskOutcome ask(String question, int topK) {
        return ask(question, topK, null);
    }

    public AskOutcome ask(String question, int topK, String conversationId) {
        List<Message> history = loadHistory(conversationId);
        String standalone = history.isEmpty() ? question : questionRewriter.rewrite(question, history);

        // 문맥 의존 질문을 키로 쓰면 대화 간 충돌로 오답이 난다. 독립형 질문은 문맥 독립적이라 히트율도 높인다.
        Optional<RagResult.Answered> hit = queryCache.get(standalone);
        if (hit.isPresent()) {
            remember(conversationId, question, hit.get());
            return new AskOutcome(hit.get(), true);
        }

        if (routingEnabled) {
            QuestionRoute route = questionRouter.classify(standalone);
            switch (route.type()) {
                case CHITCHAT -> {
                    RagResult result = new RagResult.Answered(route.message(), List.of());
                    remember(conversationId, question, result);
                    return new AskOutcome(result, false);
                }
                case AMBIGUOUS -> {
                    RagResult result = new RagResult.Clarify(route.message());
                    remember(conversationId, question, result);
                    return new AskOutcome(result, false);
                }
                case WIKI -> {
                    // 기존 RAG 경로를 따른다.
                }
            }
        }

        RagResult result = ragService.answer(standalone, topK);
        if (result instanceof RagResult.Answered answer) {
            queryCache.put(standalone, answer);
        }
        remember(conversationId, question, result);
        return new AskOutcome(result, false);
    }

    private List<Message> loadHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return chatMemory.get(conversationId);
    }

    private void remember(String conversationId, String question, RagResult result) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        String assistantText = switch (result) {
            case RagResult.Answered answered -> answered.answer();
            case RagResult.Clarify clarify -> clarify.message();
            case RagResult.Degraded degraded -> degraded.message();
            case RagResult.NoContext ignored -> "관련 문서를 찾지 못했습니다.";
            case RagResult.LlmError ignored -> null;
        };
        if (assistantText == null || assistantText.isBlank()) {
            // 실패한 대화는 히스토리에 기록하지 않는다.
            return;
        }
        chatMemory.add(conversationId, List.<Message>of(
                new UserMessage(question),
                new AssistantMessage(assistantText)
        ));
    }
}
