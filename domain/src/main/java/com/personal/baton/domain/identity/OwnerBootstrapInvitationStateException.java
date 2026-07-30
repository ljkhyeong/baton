package com.personal.baton.domain.identity;

public class OwnerBootstrapInvitationStateException extends RuntimeException {

    private final Reason reason;

    public OwnerBootstrapInvitationStateException(Reason reason) {
        super(switch (reason) {
            case EXPIRED -> "bootstrap 초대가 만료되었습니다";
            case REVOKED -> "bootstrap 초대가 폐기되었습니다";
            case USED -> "bootstrap 초대가 이미 사용되었습니다";
        });
        this.reason = reason;
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
