package com.yeonwoo.askwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonwoo.askwiki.routing.QuestionRouter;
import com.yeonwoo.askwiki.routing.QuestionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3-4c 측정: 질문 의도(WIKI/CHITCHAT/AMBIGUOUS) 분류 정확도와 분류 LLM 호출의 토큰·지연 시간을 측정한다.
 * 코퍼스에 답이 있는지(answerability)가 아니라 위키를 찾으려는 의도(intent)를 재므로, 답이 없어도 WIKI가 정답일 수 있다.
 * {@link QuestionRouter#classify(String)}를 그대로 호출해 normalize를 포함한 프로덕션 동작을 측정한다.
 * 임베딩은 필요 없으므로 Ollama 없이 챗 프로바이더만 있으면 된다.
 * 실행: {@code ./gradlew evalTest --tests "*ClassificationEvalTest"}
 * (Gemini: {@code ASKWIKI_LLM_PROVIDER=gemini} + {@code GOOGLE_GENAI_API_KEY}).
 */
@Tag("eval")
@SpringBootTest(properties = {
        "askwiki.outbox.scheduler-enabled=false",
        "askwiki.routing.enabled=true"
})
@Testcontainers
class ClassificationEvalTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Autowired QuestionRouter questionRouter;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void measuresClassificationAccuracy() throws IOException {
        JsonNode classification;
        try (InputStream inputStream = new ClassPathResource("eval/questions-classification.json").getInputStream()) {
            classification = new ObjectMapper().readTree(inputStream).get("classification");
        }

        EnumMap<QuestionType, EnumMap<QuestionType, Integer>> confusion = new EnumMap<>(QuestionType.class);
        EnumMap<QuestionType, Integer> totalsByExpected = new EnumMap<>(QuestionType.class);
        for (QuestionType expected : QuestionType.values()) {
            totalsByExpected.put(expected, 0);
            EnumMap<QuestionType, Integer> row = new EnumMap<>(QuestionType.class);
            for (QuestionType actual : QuestionType.values()) {
                row.put(actual, 0);
            }
            confusion.put(expected, row);
        }

        int total = 0;
        int correct = 0;
        long pacingMs = evalPacingMs();
        for (int index = 0; index < classification.size(); index++) {
            JsonNode node = classification.get(index);
            String question = node.get("question").asText();
            QuestionType expected = QuestionType.valueOf(node.get("expected").asText());
            QuestionType actual = questionRouter.classify(question).type();

            total++;
            totalsByExpected.merge(expected, 1, Integer::sum);
            confusion.get(expected).merge(actual, 1, Integer::sum);
            if (expected == actual) {
                correct++;
            }

            System.out.println(String.format(
                    "[CLASSIFY] %s expected=%s actual=%s | \"%s\"",
                    expected == actual ? "OK  " : "MISS", expected, actual, question
            ));
            if (index < classification.size() - 1) {
                pace(pacingMs);
            }
        }

        double accuracy = percentage(correct, total);
        System.out.println(String.format(
                "[CLASSIFY-ACC] total=%d correct=%d accuracy=%.1f%%", total, correct, accuracy
        ));
        System.out.println("[CLASSIFY-CONFUSION] expected\\actual : WIKI CHITCHAT AMBIGUOUS");
        for (QuestionType expected : QuestionType.values()) {
            EnumMap<QuestionType, Integer> row = confusion.get(expected);
            System.out.println(String.format(
                    "[CLASSIFY-CONFUSION] %-9s : %4d %8d %9d",
                    expected,
                    row.get(QuestionType.WIKI),
                    row.get(QuestionType.CHITCHAT),
                    row.get(QuestionType.AMBIGUOUS)
            ));
        }
        System.out.println(String.format(
                "[CLASSIFY-RECALL] WIKI=%.1f%% CHITCHAT=%.1f%% AMBIGUOUS=%.1f%%",
                recall(QuestionType.WIKI, confusion, totalsByExpected),
                recall(QuestionType.CHITCHAT, confusion, totalsByExpected),
                recall(QuestionType.AMBIGUOUS, confusion, totalsByExpected)
        ));

        Counter calls = meterRegistry.find("llm.calls").tag("purpose", "classify").counter();
        Counter inTok = meterRegistry.find("llm.tokens").tag("purpose", "classify").tag("type", "input").counter();
        Counter outTok = meterRegistry.find("llm.tokens").tag("purpose", "classify").tag("type", "output").counter();
        Timer timer = meterRegistry.find("llm.latency").tag("purpose", "classify").timer();
        System.out.println(String.format(
                "[CLASSIFY-COST] calls=%.0f inputTokens=%.0f outputTokens=%.0f meanLatencyMs=%.0f",
                count(calls), count(inTok), count(outTok), meanLatencyMs(timer)
        ));

        assertEquals(36, total);
        assertTrue(accuracy >= 0.0 && accuracy <= 100.0);
        for (QuestionType expected : QuestionType.values()) {
            int rowSum = confusion.get(expected).values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(totalsByExpected.get(expected).intValue(), rowSum);
        }
    }

    private double percentage(int count, int total) {
        return total == 0 ? 0.0 : count * 100.0 / total;
    }

    private double recall(QuestionType expected,
                          EnumMap<QuestionType, EnumMap<QuestionType, Integer>> confusion,
                          EnumMap<QuestionType, Integer> totalsByExpected) {
        return percentage(confusion.get(expected).get(expected), totalsByExpected.get(expected));
    }

    private double count(Counter counter) {
        return counter == null ? 0.0 : counter.count();
    }

    private double meanLatencyMs(Timer timer) {
        return timer == null ? 0.0 : timer.mean(TimeUnit.MILLISECONDS);
    }

    private long evalPacingMs() {
        return Long.parseLong(System.getenv().getOrDefault("ASKWIKI_EVAL_PACING_MS", "0"));
    }

    private void pace(long pacingMs) {
        if (pacingMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pacingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing", e);
        }
    }
}
