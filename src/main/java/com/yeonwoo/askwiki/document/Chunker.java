package com.yeonwoo.askwiki.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    private static final int TARGET_CHARS = 500;
    private static final int OVERLAP_CHARS = 50;

    public List<String> split(String content) {
        String text = content.strip();
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            start = skipWhitespace(text, start);
            if (start >= text.length()) {
                break;
            }

            int hardEnd = Math.min(start + TARGET_CHARS, text.length());
            int end = hardEnd == text.length()
                    ? hardEnd
                    : lastWhitespaceBetween(text, start, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }

            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }

            int nextStart = Math.max(start + 1, end - OVERLAP_CHARS);
            start = alignToWhitespaceBoundary(text, nextStart, end);
        }

        return chunks;
    }

    private int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private int lastWhitespaceBetween(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int alignToWhitespaceBoundary(String text, int proposedStart, int previousEnd) {
        for (int i = proposedStart; i < previousEnd; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return skipWhitespace(text, i);
            }
        }
        return proposedStart;
    }
}
