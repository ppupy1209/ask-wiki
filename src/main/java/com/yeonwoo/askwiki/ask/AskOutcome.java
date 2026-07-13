package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.common.RagResult;

public record AskOutcome(RagResult result, boolean cached) {
}
