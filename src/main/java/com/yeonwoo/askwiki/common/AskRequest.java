package com.yeonwoo.askwiki.common;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(@NotBlank String question, Integer topK) {
}
