package com.personal.baton.application.workspace.error;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("동일한 멱등 키를 의미가 다른 생성 요청에 사용할 수 없습니다");
    }
}
