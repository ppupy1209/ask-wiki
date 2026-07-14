package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.rag.RagService;
import com.yeonwoo.askwiki.routing.QuestionRoute;
import com.yeonwoo.askwiki.routing.QuestionRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AskService {

    private final RagService ragService;
    private final QueryCache queryCache;
    private final QuestionRouter questionRouter;
    private final boolean routingEnabled;

    public AskService(
            RagService ragService,
            QueryCache queryCache,
            QuestionRouter questionRouter,
            @Value("${askwiki.routing.enabled:false}") boolean routingEnabled) {
        this.ragService = ragService;
        this.queryCache = queryCache;
        this.questionRouter = questionRouter;
        this.routingEnabled = routingEnabled;
    }

    public AskOutcome ask(String question, int topK) {
        Optional<RagResult.Answered> hit = queryCache.get(question);
        if (hit.isPresent()) {
            return new AskOutcome(hit.get(), true);
        }

        if (routingEnabled) {
            QuestionRoute route = questionRouter.classify(question);
            switch (route.type()) {
                case CHITCHAT -> {
                    return new AskOutcome(new RagResult.Answered(route.message(), List.of()), false);
                }
                case AMBIGUOUS -> {
                    return new AskOutcome(new RagResult.Clarify(route.message()), false);
                }
                case WIKI -> {
                    // 기존 RAG 경로를 따른다.
                }
            }
        }

        RagResult result = ragService.answer(question, topK);
        if (result instanceof RagResult.Answered answer) {
            queryCache.put(question, answer);
        }
        return new AskOutcome(result, false);
    }
}
