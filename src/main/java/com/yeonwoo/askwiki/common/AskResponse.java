package com.yeonwoo.askwiki.common;

import java.util.List;

public record AskResponse(String answer, List<Source> sources, long latencyMs, boolean cached) {
}
