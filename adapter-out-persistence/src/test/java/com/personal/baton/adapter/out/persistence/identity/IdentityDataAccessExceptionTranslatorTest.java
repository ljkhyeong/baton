package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityDataAccessExceptionTranslatorTest {

    @DisplayName("연결 자원 장애는 원인을 보존한 인증 일시 처리 불가로 변환한다")
    @Test
    void translatesDataAccessResourceFailure() {
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException(
                "database connection is unavailable"
        );

        assertThatThrownBy(() ->
                IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                        "인증 요청을 저장할 수 없습니다",
                        (Runnable) () -> {
                            throw cause;
                        }
                )).isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("복구 가능한 data access 장애는 원인을 보존한 재시도 경계로 변환한다")
    @Test
    void translatesRecoverableDataAccessFailure() {
        RecoverableDataAccessException cause = new RecoverableDataAccessException(
                "database requested a retry"
        );

        assertThatThrownBy(() ->
                IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                        "인증 요청을 갱신할 수 없습니다",
                        (Runnable) () -> {
                            throw cause;
                        }
                )).isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("영구 SQL 사용 오류는 503으로 숨기지 않고 원래 예외를 보존한다")
    @Test
    void preservesPermanentDataAccessFailure() {
        InvalidDataAccessResourceUsageException cause =
                new InvalidDataAccessResourceUsageException("invalid SQL");

        assertThatThrownBy(() ->
                IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                        "인증 요청을 저장할 수 없습니다",
                        (Runnable) () -> {
                            throw cause;
                        }
                )).isSameAs(cause);
    }
}
