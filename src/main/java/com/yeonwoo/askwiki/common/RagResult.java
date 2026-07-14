package com.yeonwoo.askwiki.common;

import java.util.List;

public sealed interface RagResult permits RagResult.Answered, RagResult.NoContext, RagResult.LlmError, RagResult.Degraded, RagResult.Clarify {

    record Answered(String answer, List<Source> sources) implements RagResult {
    }

    record NoContext() implements RagResult {
    }

    record LlmError(String message) implements RagResult {
    }

    /** 타임아웃·과부하로 생성이 불가할 때의 degraded 폴백: 검색된 근거(sources)는 그대로 제공한다. (C2-4) */
    record Degraded(String message, List<Source> sources) implements RagResult {
    }

    /** 질문이 모호해 검색 대신 되묻는 경우(C3-2 라우팅). message = 명확화를 요청하는 질문. */
    record Clarify(String message) implements RagResult {
    }
}
