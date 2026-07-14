package com.yeonwoo.askwiki.routing;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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

    private final ChatModel chatModel;

    public QuestionRouter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public QuestionRoute classify(String question) {
        try {
            QuestionRoute route = ChatClient.create(chatModel)
                    .prompt()
                    .system(ROUTING_SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .entity(QuestionRoute.class);
            if (route == null || route.type() == null) {
                return new QuestionRoute(QuestionType.WIKI, "");
            }
            return route;
        } catch (Exception e) {
            // 라우팅이 새로운 장애 지점이 되어서는 안 되므로 WIKI로 열어 둔다.
            return new QuestionRoute(QuestionType.WIKI, "");
        }
    }
}
