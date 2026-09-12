package com.personal.baton.application.identity.error;

public class HumanVerificationUnavailableException extends RuntimeException {

    public HumanVerificationUnavailableException() {
        super("자동 요청 방지 확인을 일시적으로 사용할 수 없습니다");
    }
}
