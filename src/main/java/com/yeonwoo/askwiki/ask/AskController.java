package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.cache.QueryCache;
import com.yeonwoo.askwiki.common.AskRequest;
import com.yeonwoo.askwiki.common.AskResponse;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AskController {

    private static final int DEFAULT_TOP_K = 4;

    private final RagService ragService;
    private final QueryCache queryCache;

    public AskController(RagService ragService, QueryCache queryCache) {
        this.ragService = ragService;
        this.queryCache = queryCache;
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        long startNanos = System.nanoTime();
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();

        return queryCache.get(request.question())
                .map(answer -> ResponseEntity.ok(new AskResponse(
                        answer.answer(),
                        answer.sources(),
                        elapsedMillis(startNanos),
                        true
                )))
                .orElseGet(() -> answerWithoutCache(request.question(), topK, startNanos));
    }

    private ResponseEntity<AskResponse> answerWithoutCache(String question, int topK, long startNanos) {
        RagResult result = ragService.answer(question, topK);

        return switch (result) {
            case RagResult.Answered answered -> {
                queryCache.put(question, answered);
                yield ResponseEntity.ok(new AskResponse(
                        answered.answer(),
                        answered.sources(),
                        elapsedMillis(startNanos),
                        false
                ));
            }
            case RagResult.NoContext ignored -> ResponseEntity.ok(new AskResponse(
                    "관련 문서를 찾지 못했습니다.",
                    List.of(),
                    elapsedMillis(startNanos),
                    false
            ));
            case RagResult.LlmError error -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new AskResponse(
                            error.message(),
                            List.of(),
                            elapsedMillis(startNanos),
                            false
                    ));
        };
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
