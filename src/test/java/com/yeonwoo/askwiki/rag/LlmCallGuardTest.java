package com.yeonwoo.askwiki.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C2-4 장애격리 검증 — 타임아웃·과부하를 결정적으로 주입한다(실제 LLM/Ollama 불필요).
 */
class LlmCallGuardTest {

    private final Prompt prompt = new Prompt("hi");

    @Test
    void timesOutSlowCall() {
        // 타임아웃 100ms인데 LLM이 2초 걸림 → TIMEOUT
        LlmCallGuard guard = new LlmCallGuard(8, 100, 2000);
        ChatModel slow = mock(ChatModel.class);
        when(slow.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(2000);
            return mock(ChatResponse.class);
        });

        LlmUnavailableException ex =
                assertThrows(LlmUnavailableException.class, () -> guard.call(slow, prompt));
        assertThat(ex.reason()).isEqualTo(LlmUnavailableException.Reason.TIMEOUT);
    }

    @Test
    void rejectsWhenConcurrencyLimitReached() throws Exception {
        // 동시 1개만 허용. 한 호출이 permit을 잡고 있는 동안 두 번째는 BUSY.
        LlmCallGuard guard = new LlmCallGuard(1, 5000, 100); // acquire 대기 100ms
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel blocking = mock(ChatModel.class);
        when(blocking.call(any(Prompt.class))).thenAnswer(inv -> {
            started.countDown();
            release.await();          // permit을 붙잡은 채 대기
            return mock(ChatResponse.class);
        });

        ExecutorService bg = Executors.newSingleThreadExecutor();
        Future<?> inflight = bg.submit(() -> guard.call(blocking, prompt));
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue(); // 첫 호출 in-flight 확인

            LlmUnavailableException ex = assertThrows(
                    LlmUnavailableException.class, () -> guard.call(mock(ChatModel.class), prompt));
            assertThat(ex.reason()).isEqualTo(LlmUnavailableException.Reason.BUSY);
        } finally {
            release.countDown();
            inflight.get(2, TimeUnit.SECONDS);
            bg.shutdownNow();
        }
    }

    @Test
    void returnsResponseOnSuccess() {
        LlmCallGuard guard = new LlmCallGuard(8, 5000, 2000);
        ChatModel ok = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        when(ok.call(any(Prompt.class))).thenReturn(expected);

        assertThat(guard.call(ok, prompt)).isSameAs(expected);
    }

    @Test
    void propagatesRealLlmErrorInsteadOfWrapping() {
        // 실제 LLM 오류는 LlmUnavailableException이 아니라 원인 예외 그대로 전파 → RagService가 LlmError로 매핑.
        LlmCallGuard guard = new LlmCallGuard(8, 5000, 2000);
        ChatModel failing = mock(ChatModel.class);
        when(failing.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> guard.call(failing, prompt));
        assertThat(ex).hasMessage("boom");
    }

    @Test
    void timesOutSlowGenericOperation() {
        LlmCallGuard guard = new LlmCallGuard(8, 50, 2000);

        LlmUnavailableException ex = assertThrows(LlmUnavailableException.class, () -> guard.call(() -> {
            try {
                Thread.sleep(500);
                return "late";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }));

        assertThat(ex.reason()).isEqualTo(LlmUnavailableException.Reason.TIMEOUT);
    }

    @Test
    void propagatesRuntimeExceptionFromGenericOperation() {
        LlmCallGuard guard = new LlmCallGuard(8, 5000, 2000);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> guard.call(() -> { throw new RuntimeException("boom"); }));
        assertThat(ex).hasMessage("boom");
    }
}
