package com.personal.baton.application.identity.error;

public class MemberIdentityConflictException extends RuntimeException {

    private static final String MESSAGE =
            "사용자 계정 또는 구성원이 이미 다른 신원과 연결되어 있습니다";

    public MemberIdentityConflictException() {
        super(MESSAGE);
    }

    public MemberIdentityConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
