package com.personal.baton.application.identity.error;

/** 새 트랜잭션에서 다시 시도할 수 있는 오래된 신원 스냅샷을 나타낸다. */
public class IdentityConcurrentModificationException extends RuntimeException {

    public IdentityConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
