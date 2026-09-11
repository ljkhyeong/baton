package com.personal.baton.adapter.in.web.auth;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.in.web.config.SocialLoginProviderCatalog;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AuthExceptionObservationTest {

    private RegisterLocalAccountUseCase registerLocalAccountUseCase;
    private AtomicReference<ServerRequestObservationContext> stoppedObservation;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registerLocalAccountUseCase = mock(RegisterLocalAccountUseCase.class);
        VerifyLocalEmailUseCase verifyLocalEmailUseCase = mock(VerifyLocalEmailUseCase.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SocialLoginProviderCatalog> registrationRepositoryProvider =
                mock(ObjectProvider.class);

        AuthController controller = new AuthController(
                registerLocalAccountUseCase,
                verifyLocalEmailUseCase,
                mock(PasswordResetUseCase.class),
                registrationRepositoryProvider,
                new AuthRateLimiter(),
                new AuthFeatureProperties(true, true)
        );
        stoppedObservation = new AtomicReference<>();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler<ServerRequestObservationContext>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return context instanceof ServerRequestObservationContext;
                    }

                    @Override
                    public void onStop(ServerRequestObservationContext context) {
                        stoppedObservation.set(context);
                    }
                }
        );
        mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler())
                .addFilters(new ServerHttpObservationFilter(observationRegistry))
                .build();
    }

    @DisplayName("인증 트랜잭션 시작 실패는 공통 503으로 응답하고 HTTP 관측 정보에는 원래 오류를 기록한다")
    @Test
    void recordsTransactionStartFailureAsObservationError() throws Exception {
        CannotCreateTransactionException failure = new CannotCreateTransactionException(
                "database connection is unavailable",
                new IllegalStateException("jdbc:mysql://secret-host/internal")
        );
        doThrow(failure).when(registerLocalAccountUseCase).registerLocalAccount(any());

        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
                ));

        assertThat(stoppedObservation.get()).isNotNull();
        assertThat(stoppedObservation.get().getError()).isSameAs(failure);
    }

    @DisplayName("인증 커밋 연결 장애는 공통 503으로 응답하고 HTTP 관측 정보에는 원래 오류를 기록한다")
    @Test
    void recordsCommitResourceFailureAsObservationError() throws Exception {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "database connection was lost during commit"
        );
        doThrow(failure).when(registerLocalAccountUseCase).registerLocalAccount(any());

        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"));

        assertThat(stoppedObservation.get()).isNotNull();
        assertThat(stoppedObservation.get().getError()).isSameAs(failure);
    }

    @DisplayName("감싼 인증 인프라 장애는 분류에 사용한 원인 예외를 HTTP 관측에 기록한다")
    @Test
    void recordsWrappedInfrastructureCauseAsObservationError() throws Exception {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "database connection was lost during commit"
        );
        TransactionSystemException wrapper = new TransactionSystemException(
                "transaction commit failed",
                failure
        );
        doThrow(wrapper).when(registerLocalAccountUseCase).registerLocalAccount(any());

        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"));

        assertThat(stoppedObservation.get()).isNotNull();
        assertThat(stoppedObservation.get().getError()).isSameAs(failure);
    }
}
