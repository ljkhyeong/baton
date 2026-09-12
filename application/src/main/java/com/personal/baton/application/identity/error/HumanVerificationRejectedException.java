package com.personal.baton.application.identity.error;

public class HumanVerificationRejectedException extends RuntimeException {

    public HumanVerificationRejectedException() {
        super("자동 요청 방지 확인이 완료되지 않았습니다");
    }
}
