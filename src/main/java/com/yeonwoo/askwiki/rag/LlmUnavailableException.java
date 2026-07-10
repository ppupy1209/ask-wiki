package com.yeonwoo.askwiki.rag;

/**
 * LLM 호출이 타임아웃(TIMEOUT)이나 과부하(BUSY)로 불가할 때 던진다.
 * RagService가 이를 잡아 하드 실패 대신 degraded 폴백으로 처리한다. (C2-4)
 *
 * <p>실제 LLM 호출 오류(4xx/5xx 등)는 이 예외가 아니라 원인 예외 그대로 전파되어 LlmError로 매핑된다.
 */
public class LlmUnavailableException extends RuntimeException {

    public enum Reason { TIMEOUT, BUSY }

    private final Reason reason;

    public LlmUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
