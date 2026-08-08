package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAuthenticationFailureHandlerTest {

    private static final ErrorResponse FALLBACK = new ErrorResponse(
            "INVALID_CREDENTIALS",
            "이메일 또는 비밀번호가 올바르지 않습니다"
    );

    private final AccountAuthenticationFailureHandler handler =
            new AccountAuthenticationFailureHandler(
                    new SecurityErrorResponseWriter(new ObjectMapper()),
                    FALLBACK
            );

    @DisplayName("일반 자격 증명 실패는 기존 401 계약을 유지한다")
    @Test
    void preservesCredentialFailureContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("bad credentials")
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("INVALID_CREDENTIALS");
    }

    @DisplayName("Spring Security가 감싼 identity 인프라 장애는 모두 503으로 분류한다")
    @ParameterizedTest(name = "{0}")
    @MethodSource("identityInfrastructureFailures")
    void exposesWrappedIdentityInfrastructureFailure(
            String description,
            RuntimeException failure
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new InternalAuthenticationServiceException("wrapped", failure)
        );

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("IDENTITY_TEMPORARILY_UNAVAILABLE");
    }

    private static Stream<Arguments> identityInfrastructureFailures() {
        IllegalStateException cause = new IllegalStateException("test database failure");
        return Stream.of(
                Arguments.of(
                        "identity adapter translation",
                        new IdentityOperationUnavailableException(
                                "identity repository unavailable",
                                cause
                        )
                ),
                Arguments.of(
                        "transaction start",
                        new CannotCreateTransactionException(
                                "transaction unavailable",
                                cause
                        )
                ),
                Arguments.of(
                        "transaction timeout",
                        new TransactionTimedOutException("transaction timed out")
                ),
                Arguments.of(
                        "transient data access",
                        new QueryTimeoutException("query timed out")
                ),
                Arguments.of(
                        "recoverable data access",
                        new RecoverableDataAccessException("connection can recover")
                ),
                Arguments.of(
                        "data access resource failure",
                        new DataAccessResourceFailureException("connection was lost")
                )
        );
    }
}
