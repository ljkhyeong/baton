package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityDataAccessExceptionTranslatorTest {

    @DisplayName("연결 자원·복구 가능한 data access 장애는 원인을 보존한 인증 일시 처리 불가로 변환한다")
    @ParameterizedTest(name = "{0}")
    @MethodSource("temporaryFailures")
    void translatesTemporaryDataAccessFailure(String description, DataAccessException cause) {
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

    private static Stream<Arguments> temporaryFailures() {
        return Stream.of(
                Arguments.of(
                        "연결 자원 장애",
                        new DataAccessResourceFailureException("database connection is unavailable")
                ),
                Arguments.of(
                        "복구 가능한 장애",
                        new RecoverableDataAccessException("database requested a retry")
                )
        );
    }
}
