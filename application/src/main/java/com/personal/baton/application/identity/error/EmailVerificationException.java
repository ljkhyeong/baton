package com.personal.baton.application.identity.error;

public class EmailVerificationException extends RuntimeException {

    public EmailVerificationException() {
        super("이메일 인증 요청이 올바르지 않거나 만료되었습니다");
    }
}
