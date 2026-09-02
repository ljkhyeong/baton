package com.personal.baton.application.identity.error;

public class LocalPasswordUnavailableException extends RuntimeException {

    public LocalPasswordUnavailableException() {
        super("자체 이메일 비밀번호를 사용하는 계정이 아닙니다");
    }
}
