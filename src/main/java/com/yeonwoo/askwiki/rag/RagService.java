package com.yeonwoo.askwiki.rag;

import com.yeonwoo.askwiki.common.ChunkMatch;
import com.yeonwoo.askwiki.common.RagResult;
import com.yeonwoo.askwiki.common.Source;
import com.yeonwoo.askwiki.embedding.EmbeddingClient;
import com.yeonwoo.askwiki.search.VectorIndex;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class RagService {

    private final EmbeddingClient embeddingClient;
    private final VectorIndex vectorIndex;
    private final ChatModel chatModel;
    private final LlmMetrics llmMetrics;
    private final LlmCallGuard llmCallGuard;

    public RagService(EmbeddingClient embeddingClient, VectorIndex vectorIndex,
                      ChatModel chatModel, LlmMetrics llmMetrics, LlmCallGuard llmCallGuard) {
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.chatModel = chatModel;
        this.llmMetrics = llmMetrics;
        this.llmCallGuard = llmCallGuard;
    }

    public RagResult answer(String question, int topK) {

        float[] questionVector = embeddingClient.embed(question);

        List<ChunkMatch> matches = vectorIndex.search(questionVector, topK);

        if (matches.isEmpty()) {
            return new RagResult.NoContext();
        }

        String context = matches.stream()
                .map(m -> "- " + m.content())
                .collect(Collectors.joining("\n"));

        String prompt = """
                당신은 사내 규정 안내 도우미입니다. 아래 실제 컨텍스트만 근거로 한국어로 답하세요.

                판단 순서:
                1. 질문이 묻는 구체 대상/항목을 찾습니다.
                2. 실제 컨텍스트에서 그 대상/항목 또는 같은 뜻의 표현을 찾습니다.
                3. 찾으면 자신 있게 답합니다. 못 찾으면 "모르겠습니다"라고만 답합니다.

                규칙:
                - 컨텍스트가 같은 큰 주제를 다루는 것만으로는 답하지 않습니다. 예: 휴가 문서에 연차/반차/병가만 있고 육아휴직이 없으면, 육아휴직 질문에는 "모르겠습니다"라고 답합니다.
                - 다른 표현이나 동의어는 허용합니다. 예: 승인=결재, 퇴사=퇴직처럼 실제 컨텍스트에서 같은 항목임이 분명하면 답합니다.
                - 같은 주제, 상위 범주, 비슷한 제도는 같은 뜻이 아닙니다.
                - 실제 컨텍스트에 없는 숫자, 기간, 조건, 예외, 절차를 만들지 않습니다.
                - 답변은 1~3문장으로 간결하게 작성합니다.
                - 아래 예시는 형식 학습용이며 실제 답변의 근거가 아닙니다. 실제 질문에는 실제 컨텍스트만 근거로 답합니다.

                예시 1
                컨텍스트: 업무용 노트북은 퇴사일 전날까지 정보보안팀에 반납한다.
                질문: 퇴직할 때 업무용 노트북은 어디에 돌려줘야 하나요?
                답변: 업무용 노트북은 퇴사일 전날까지 정보보안팀에 반납합니다.

                예시 2
                컨텍스트: 휴가는 연차, 오전반차, 오후반차, 병가, 경조휴가로 구분한다. 연차는 1일 단위로 사용하고 반차는 4시간으로 계산한다.
                질문: 포상휴가는 며칠 받을 수 있나요?
                답변: 모르겠습니다

                실제 컨텍스트:
                %s

                실제 질문: %s
                답변:
                """.formatted(context, question);

        // 검색된 근거는 미리 만들어 둔다 — 정상 답변뿐 아니라 degraded 폴백에서도 그대로 제공한다.
        List<Source> sources = matches.stream()
                .map(m -> new Source(m.documentId(), m.title(), m.seq(), m.score()))
                .toList();

        try {
            // 세마포어·타임아웃으로 보호한 채 호출(C2-4). 호출 지연·토큰 usage는 Micrometer 지표로 기록(C2-3).
            long startNanos = System.nanoTime();
            ChatResponse response = llmCallGuard.call(chatModel, new Prompt(prompt));
            llmMetrics.record(LlmMetrics.PURPOSE_ANSWER, response, System.nanoTime() - startNanos);

            String answer = response.getResult().getOutput().getText();
            return new RagResult.Answered(answer, sources);

        } catch (LlmUnavailableException e) {
            // 타임아웃/과부하 → 하드 실패 대신 degraded: 검색된 근거는 그대로 돌려준다.
            llmMetrics.recordDegraded(LlmMetrics.PURPOSE_ANSWER, e.reason().name().toLowerCase(Locale.ROOT));
            return new RagResult.Degraded(
                    "일시적으로 답변 생성이 어렵습니다. 아래 관련 문서를 참고하세요.", sources);

        } catch (Exception e) {
            return new RagResult.LlmError(e.getMessage());
        }
    }
}
