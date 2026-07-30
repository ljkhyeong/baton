package com.personal.baton.application.identity.error;

public class InactiveMemberIdentityException extends RuntimeException {

    public InactiveMemberIdentityException() {
        super("활동 종료한 구성원은 사용자 계정과 연결할 수 없습니다");
    }
}
