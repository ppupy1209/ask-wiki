package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.rag.RagService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AskService {

    private final RagService ragService;
    private final QueryCache queryCache;

    public AskService(RagService ragService, QueryCache queryCache) {
        this.ragService = ragService;
        this.queryCache = queryCache;
    }

    public AskOutcome ask(String question, int topK) {
        Optional<RagResult.Answered> hit = queryCache.get(question);
        if (hit.isPresent()) {
            return new AskOutcome(hit.get(), true);
        }

        RagResult result = ragService.answer(question, topK);
        if (result instanceof RagResult.Answered answer) {
            queryCache.put(question, answer);
        }
        return new AskOutcome(result, false);
    }
}
