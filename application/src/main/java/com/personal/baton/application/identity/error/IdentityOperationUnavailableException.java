package com.personal.baton.application.identity.error;

/**
 * 신원 상태를 변경하는 동안 발생한 일시적 경합이나 인프라 장애를 나타낸다.
 *
 * <p>{@link IdentityConflictException}과 의도적으로 구분한다. 중복 신원은 신원 존재를 노출하지 않는
 * 중립적인 가입 결과일 수 있지만, 데이터베이스 경합은 요청 작업이 완료되지 않아 나중에 다시 시도해야
 * 함을 뜻한다.</p>
 */
public class IdentityOperationUnavailableException extends RuntimeException {

    public IdentityOperationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
