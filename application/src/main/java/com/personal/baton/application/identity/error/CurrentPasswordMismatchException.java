package com.personal.baton.application.identity.error;

public class CurrentPasswordMismatchException extends RuntimeException {

    public CurrentPasswordMismatchException() {
        super("현재 비밀번호가 올바르지 않습니다");
    }
}
