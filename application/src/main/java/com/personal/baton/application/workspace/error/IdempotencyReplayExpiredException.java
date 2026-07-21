package com.personal.baton.application.workspace.error;

public class IdempotencyReplayExpiredException extends RuntimeException {

    public IdempotencyReplayExpiredException() {
        super("더 최신 작업이 완료되어 이 멱등 키의 응답을 더 이상 재생할 수 없습니다");
    }
}
