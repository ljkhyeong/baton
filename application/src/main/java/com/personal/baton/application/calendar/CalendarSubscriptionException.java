package com.personal.baton.application.calendar;

public final class CalendarSubscriptionException extends RuntimeException {
    public enum Reason { DISABLED, ACCESS_DENIED, ACCOUNT_CHANGED, NOT_FOUND, IN_PROGRESS, CREDENTIAL_REQUIRED, UNAVAILABLE, INVALID_RESPONSE }
    private final Reason reason;
    public CalendarSubscriptionException(Reason reason) {
        super(switch (reason) {
            case DISABLED -> "캘린더 구독을 아직 사용할 수 없습니다.";
            case ACCOUNT_CHANGED -> "로그인 계정이 바뀌었습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.";
            case ACCESS_DENIED -> "활동 중인 팀 구성원만 캘린더 구독을 발급할 수 있습니다.";
            case NOT_FOUND -> "캘린더 구독을 찾지 못했습니다.";
            case IN_PROGRESS -> "구독 요청을 처리 중입니다. 잠시 후 상태를 다시 확인해 주세요.";
            case CREDENTIAL_REQUIRED -> "구독이 이미 있습니다. 주소가 없으면 새 주소를 발급해 주세요.";
            case UNAVAILABLE -> "구독 요청 결과를 확인하지 못했습니다. 상태를 먼저 다시 조회해 주세요.";
            case INVALID_RESPONSE -> "캘린더 연결 응답을 확인하지 못했습니다. 운영자에게 문의해 주세요.";
        });
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
