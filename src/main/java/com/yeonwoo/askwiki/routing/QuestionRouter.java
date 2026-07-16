package com.yeonwoo.askwiki.routing;

import com.yeonwoo.askwiki.rag.LlmCallGuard;
import com.yeonwoo.askwiki.rag.LlmMetrics;
import com.yeonwoo.askwiki.rag.LlmUnavailableException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

@Component
public class QuestionRouter {

    private static final String ROUTING_SYSTEM_PROMPT = """
            당신은 사내 위키 챗봇의 질문 분류기입니다. 아래 세 범주 중 하나로 질문을 분류하세요.

            - WIKI: 사내 규정, 매뉴얼, 업무 절차 등 내부 문서가 있어야 답할 수 있는 질문입니다. 기본 범주입니다.
            - CHITCHAT: 인사, 잡담, 챗봇 자체의 기능에 대한 질문, 또는 내부 문서와 무관한 일반·외부 지식 질문입니다.
            - AMBIGUOUS: 무엇을 묻는지 판단할 수 없을 만큼 질문이 모호한 경우입니다.

            message 필드 규칙:
            - WIKI면 빈 문자열을 넣으세요.
            - CHITCHAT이면 한국어로 한두 문장으로 답하세요. 내부 문서와 무관한 주제라면 사내 규정이나 매뉴얼 질문만 도울 수 있다고 정중하게 설명하세요.
            - AMBIGUOUS이면 사용자가 무엇을 의미하는지 구체적으로 알려 달라고 요청하는 한국어 질문 한 문장만 넣으세요.

            판단이 애매하면 WIKI로 분류하세요. 검색이 기본 경로입니다.
            """;

    /** 분류 실패·이행 불가 시의 기본 경로 — 검색(WIKI)으로 열어 둔다. */
    private static final QuestionRoute FALLBACK_TO_WIKI = new QuestionRoute(QuestionType.WIKI, "");

    private final ChatModel chatModel;
    private final LlmCallGuard llmCallGuard;
    private final LlmMetrics llmMetrics;

    public QuestionRouter(ChatModel chatModel, LlmCallGuard llmCallGuard, LlmMetrics llmMetrics) {
        this.chatModel = chatModel;
        this.llmCallGuard = llmCallGuard;
        this.llmMetrics = llmMetrics;
    }

    public QuestionRoute classify(String question) {
        try {
            long start = System.nanoTime();
            ResponseEntity<ChatResponse, QuestionRoute> re = llmCallGuard.call(() ->
                    ChatClient.create(chatModel).prompt()
                            .system(ROUTING_SYSTEM_PROMPT).user(question)
                            .call().responseEntity(QuestionRoute.class));
            llmMetrics.record(LlmMetrics.PURPOSE_CLASSIFY, re.getResponse(), System.nanoTime() - start);
            return normalize(re.getEntity());
        } catch (LlmUnavailableException e) {
            llmMetrics.recordDegraded(LlmMetrics.PURPOSE_CLASSIFY,
                    e.reason().name().toLowerCase(java.util.Locale.ROOT));
            return FALLBACK_TO_WIKI;
        } catch (Exception e) {
            // 라우팅이 새로운 장애 지점이 되어서는 안 되므로 WIKI로 열어 둔다.
            return FALLBACK_TO_WIKI;
        }
    }

    /**
     * 분류 결과를 이행 가능한 형태로 정규화한다. 비-WIKI 경로는 message가 그대로 사용자 응답이 되므로,
     * type이나 message가 비어 있으면 그 분류는 이행할 수 없다 → 분류 실패와 동일하게 WIKI로 열어 둔다.
     * (WIKI는 message를 쓰지 않으므로 비어 있어도 그대로 둔다.)
     *
     * <p>{@code ChatClient.entity()} 성공 경로는 목 ChatModel로 구동할 수 없어, 순수 함수로 분리해
     * 결정적으로 검증한다. {@code AgenticRagService.mapResult}와 같은 이유·같은 패턴.</p>
     */
    static QuestionRoute normalize(QuestionRoute route) {
        if (route == null || route.type() == null) {
            return FALLBACK_TO_WIKI;
        }
        if (route.type() != QuestionType.WIKI && (route.message() == null || route.message().isBlank())) {
            return FALLBACK_TO_WIKI;
        }
        return route;
    }
}
