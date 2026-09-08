package com.personal.baton.application.workspace.error;

public final class ResourceCheckRequestException extends RuntimeException {
    public enum Reason { INACTIVE, RATE_LIMITED, UNAVAILABLE }

    private final Reason reason;
    private final Long retryAfterSeconds;

    public ResourceCheckRequestException(Reason reason, Long retryAfterSeconds) {
        super(switch (reason) {
            case INACTIVE -> "현재 이 자료는 자동 점검 대상이 아닙니다";
            case RATE_LIMITED -> "잠시 뒤 다시 점검을 요청해 주세요";
            case UNAVAILABLE -> "점검 요청을 전달하지 못했습니다. 잠시 뒤 다시 시도해 주세요";
        });
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason reason() { return reason; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
