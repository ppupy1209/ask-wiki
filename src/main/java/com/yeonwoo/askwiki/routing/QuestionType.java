package com.yeonwoo.askwiki.routing;

public enum QuestionType {

    /** 사내 위키 문서 검색이 필요한 질문으로 RAG로 라우팅한다. */
    WIKI,

    /** 인사·잡담·챗봇 기능·사내 문서와 무관한 질문으로 직접 응답한다. */
    CHITCHAT,

    /** 무엇을 묻는지 판단하기에 너무 모호한 질문으로 되묻는다. */
    AMBIGUOUS
}
