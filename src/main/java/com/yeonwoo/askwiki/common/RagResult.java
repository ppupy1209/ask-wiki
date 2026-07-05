package com.yeonwoo.askwiki.common;

import java.util.List;

public sealed interface RagResult permits RagResult.Answered, RagResult.NoContext, RagResult.LlmError {

    record Answered(String answer, List<Source> sources) implements RagResult {
    }

    record NoContext() implements RagResult {
    }

    record LlmError(String message) implements RagResult {
    }
}
