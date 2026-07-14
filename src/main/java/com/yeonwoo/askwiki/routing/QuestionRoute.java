package com.yeonwoo.askwiki.routing;

/**
 * LLM이 채우는 Structured Output 대상이다. message는 WIKI가 아닐 때의 응답 문구다.
 * AMBIGUOUS는 명확화를 요청하는 질문이고, CHITCHAT은 짧은 직접 응답이며, WIKI에서는 빈 문자열로 무시한다.
 */
public record QuestionRoute(QuestionType type, String message) {
}
