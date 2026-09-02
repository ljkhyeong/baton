package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalEmailVerificationRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalRegistrationRequest;
import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.in.web.config.SocialLoginProviderCatalog;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthControllerFeatureGateTest {

    private final RegisterLocalAccountUseCase registerUseCase =
            mock(RegisterLocalAccountUseCase.class);
    private final VerifyLocalEmailUseCase verifyUseCase = mock(VerifyLocalEmailUseCase.class);
    private final PasswordResetUseCase resetUseCase = mock(PasswordResetUseCase.class);
    private final AuthController controller = new AuthController(
            registerUseCase,
            verifyUseCase,
            resetUseCase,
            registrations(),
            new AuthRateLimiter(),
            new AuthFeatureProperties(false, false)
    );

    @DisplayName("자체 이메일 가입 gate가 닫히면 계정 존재 조회 전에 fail-closed 한다")
    @Test
    void rejectsRegistrationBeforeCallingTheApplicationPort() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.registerLocalAccount(
                new LocalRegistrationRequest("member@example.com", "박민서"),
                servletRequest
        )).isInstanceOf(EmailVerificationDeliveryUnavailableException.class);

        verifyNoInteractions(registerUseCase);
    }

    @DisplayName("가입 gate가 닫혀도 이미 발급한 이메일 검증 링크는 완료할 수 있다")
    @Test
    void keepsVerificationAvailableWhileRegistrationIsClosed() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();

        controller.verifyLocalEmail(
                new LocalEmailVerificationRequest(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "correct horse battery staple"
                ),
                servletRequest
        );

        verify(verifyUseCase).verifyLocalEmail(any());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<SocialLoginProviderCatalog> registrations() {
        return mock(ObjectProvider.class);
    }

    @Test
    @DisplayName("재설정 요청을 닫아도 이미 발급한 토큰은 사용할 수 있다")
    void closesOnlyNewResetRequests() {
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.requestPasswordReset(
                new AuthRequests.PasswordResetRequest("member@example.com"), request))
                .isInstanceOf(EmailVerificationDeliveryUnavailableException.class);
        verifyNoInteractions(resetUseCase);
        controller.resetPassword(new AuthRequests.PasswordResetCompletionRequest(
                "a".repeat(32), "correct horse battery staple"), request);
        verify(resetUseCase).resetPassword(any());
    }
}
