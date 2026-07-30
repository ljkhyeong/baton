package com.personal.baton.domain.identity;

import java.util.Objects;

public class MemberInvitationStateException extends RuntimeException {

    private final Reason reason;

    public MemberInvitationStateException(Reason reason) {
        super("구성원 초대 상태가 요청을 허용하지 않습니다: " + reason);
        this.reason = Objects.requireNonNull(reason, "구성원 초대 상태 오류 이유는 필수입니다");
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        EXPIRED,
        REVOKED,
        USED
    }
}
