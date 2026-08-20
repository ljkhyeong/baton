package com.personal.baton.application.workspace.error;

public class IdempotencyKeyConflictException extends RuntimeException {

    private static final String MESSAGE =
            "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요";

    public IdempotencyKeyConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
