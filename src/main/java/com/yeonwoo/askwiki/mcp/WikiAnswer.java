package com.yeonwoo.askwiki.mcp;

import com.yeonwoo.askwiki.common.Source;

import java.util.List;

public record WikiAnswer(String answer, List<Source> sources) {
}
