package com.yeonwoo.askwiki.ask;

import com.yeonwoo.askwiki.common.AskRequest;
import com.yeonwoo.askwiki.common.AskResponse;
import com.yeonwoo.askwiki.common.RagResult;
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

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        long startNanos = System.nanoTime();
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();

        AskOutcome outcome = askService.ask(request.question(), topK);

        return switch (outcome.result()) {
            case RagResult.Answered answered -> ResponseEntity.ok(new AskResponse(
                    answered.answer(),
                    answered.sources(),
                    elapsedMillis(startNanos),
                    outcome.cached()
            ));
            case RagResult.NoContext ignored -> ResponseEntity.ok(new AskResponse(
                    "관련 문서를 찾지 못했습니다.",
                    List.of(),
                    elapsedMillis(startNanos),
                    outcome.cached()
            ));
            case RagResult.LlmError error -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new AskResponse(
                            error.message(),
                            List.of(),
                            elapsedMillis(startNanos),
                            outcome.cached()
                    ));
            case RagResult.Degraded degraded -> ResponseEntity.ok(new AskResponse(
                    degraded.message(),
                    degraded.sources(),
                    elapsedMillis(startNanos),
                    outcome.cached()
            ));
        };
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
