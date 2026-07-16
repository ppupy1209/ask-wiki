package com.yeonwoo.askwiki.rag;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 답변·분류·질문 재작성 등 모든 LLM 작업을 장애로부터 격리한다. (C2-4, 구 B4)
 *
 * <ul>
 *   <li><b>동시성 세마포어</b>: 동시 LLM 호출을 {@code askwiki.llm.max-concurrent}로 제한 → 백엔드 과부하 차단.
 *       상한 초과 시 {@code acquire-timeout-ms}만큼 대기 후 실패(BUSY).</li>
 *   <li><b>타임아웃</b>: 호출이 {@code call-timeout-ms}를 넘으면 취소하고 실패(TIMEOUT) →
 *       느린 LLM이 요청 스레드를 무한 점유하는 것을 방지.</li>
 * </ul>
 *
 * <p>격리 실패는 {@link LlmUnavailableException}으로 던져 RagService가 degraded 폴백으로 처리하게 한다.
 * 실제 LLM 호출 오류는 원인 예외 그대로 전파해 LlmError로 매핑되게 둔다.
 */
@Component
public class LlmCallGuard {

    private final Duration callTimeout;
    private final Duration acquireTimeout;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    public LlmCallGuard(
            @Value("${askwiki.llm.max-concurrent:8}") int maxConcurrent,
            @Value("${askwiki.llm.call-timeout-ms:20000}") long callTimeoutMs,
            @Value("${askwiki.llm.acquire-timeout-ms:2000}") long acquireTimeoutMs) {
        this.callTimeout = Duration.ofMillis(callTimeoutMs);
        this.acquireTimeout = Duration.ofMillis(acquireTimeoutMs);
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 세마포어·타임아웃으로 보호한 채 LLM을 호출한다.
     *
     * @throws LlmUnavailableException 과부하(BUSY)·타임아웃(TIMEOUT) 시
     */
    public <T> T call(Supplier<T> operation) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException(LlmUnavailableException.Reason.BUSY, "대기 중 인터럽트");
        }
        if (!acquired) {
            throw new LlmUnavailableException(LlmUnavailableException.Reason.BUSY, "동시 요청 상한 초과");
        }

        Future<T> future = executor.submit(operation::get);
        try {
            return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmUnavailableException(LlmUnavailableException.Reason.TIMEOUT, "LLM 응답 시간 초과");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException(LlmUnavailableException.Reason.BUSY, "대기 중 인터럽트");
        } catch (ExecutionException e) {
            // 실제 LLM 호출 오류는 원인 예외를 그대로 전파 → RagService가 LlmError로 매핑.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            semaphore.release();
        }
    }

    /** 기존 ChatModel 호출 경로와의 호환을 위한 편의 메서드다. */
    public ChatResponse call(ChatModel chatModel, Prompt prompt) {
        return call(() -> chatModel.call(prompt));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
