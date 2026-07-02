package com.yeonwoo.ragdoc.rag;

import com.yeonwoo.ragdoc.common.ChunkMatch;
import com.yeonwoo.ragdoc.common.RagResult;
import com.yeonwoo.ragdoc.common.Source;
import com.yeonwoo.ragdoc.embedding.EmbeddingClient;
import com.yeonwoo.ragdoc.search.SearchService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RagService {

    private final EmbeddingClient embeddingClient;
    private final SearchService searchService;
    private final ChatModel chatModel;

    public RagService(EmbeddingClient embeddingClient, SearchService searchService, ChatModel chatModel) {
        this.embeddingClient = embeddingClient;
        this.searchService = searchService;
        this.chatModel = chatModel;
    }

    public RagResult answer(String question, int topK) {

        float[] questionVector = embeddingClient.embed(question);

        List<ChunkMatch> matches = searchService.findSimilar(questionVector, topK);

        if (matches.isEmpty()) {
            return new RagResult.NoContext();
        }

        String context = matches.stream()
                .map(m -> "- " + m.content())
                .collect(Collectors.joining("\n"));

        String prompt = """
                아래 컨텍스트를 읽고 질문에 한국어로 간결하게 한 문장으로 답하세요.
                컨텍스트에서 전혀 찾을 수 없을 때만 "모르겠습니다"라고 답하세요.

                컨텍스트:
                %s

                질문: %s
                답변:
                """.formatted(context, question);

        try {
            String answer = chatModel.call(prompt);
            List<Source> sources = matches.stream()
                    .map(m -> new Source(m.documentId(), m.title(), m.seq(), m.score()))
                    .toList();
            return new RagResult.Answered(answer, sources);

        } catch (Exception e) {
            return new RagResult.LlmError(e.getMessage());
        }
    }
}
