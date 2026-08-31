package com.personal.baton.application.identity.error;

public class PasswordResetException extends RuntimeException {
    public PasswordResetException() {
        super("비밀번호 재설정 링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요");
    }
}
