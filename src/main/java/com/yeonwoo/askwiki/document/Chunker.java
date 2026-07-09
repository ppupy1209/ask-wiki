package com.yeonwoo.askwiki.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    private final int targetChars;
    private final int overlapChars;

    public Chunker(
            @Value("${askwiki.chunk.target-chars:500}") int targetChars,
            @Value("${askwiki.chunk.overlap-chars:50}") int overlapChars
    ) {
        this.targetChars = targetChars;
        this.overlapChars = overlapChars;
    }

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

            int hardEnd = Math.min(start + this.targetChars, text.length());
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

            int nextStart = Math.max(start + 1, end - this.overlapChars);
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
