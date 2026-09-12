package com.personal.baton.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.application.identity.error.HumanVerificationRejectedException;
import com.personal.baton.application.identity.error.HumanVerificationUnavailableException;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase.Action;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase.HumanVerificationCommand;
import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import com.personal.baton.application.identity.port.out.HumanVerificationPort.HumanVerificationAttempt;
import com.personal.baton.application.identity.port.out.HumanVerificationPort.VerificationOutcome;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HumanVerificationServiceTest {

    private static final HumanVerificationCommand COMMAND = new HumanVerificationCommand(
            "turnstile-token",
            "192.0.2.10",
            Action.LOCAL_REGISTRATION
    );

    @Test
    @DisplayName("설정한 사이트 키와 검증 동작을 외부 검증 포트에 전달한다")
    void delegatesConfiguredVerification() {
        HumanVerificationPort port = mock(HumanVerificationPort.class);
        when(port.siteKey()).thenReturn(Optional.of("site-key"));
        when(port.verify(new HumanVerificationAttempt(
                "turnstile-token",
                "192.0.2.10",
                "local_registration"
        ))).thenReturn(VerificationOutcome.VERIFIED);
        var service = new HumanVerificationService(port);

        assertThat(service.siteKey()).contains("site-key");
        assertThatCode(() -> service.verify(COMMAND)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("거부된 token과 외부 검증 장애를 구분한다")
    void mapsVerificationFailures() {
        HumanVerificationPort port = mock(HumanVerificationPort.class);
        var service = new HumanVerificationService(port);
        when(port.verify(new HumanVerificationAttempt(
                "turnstile-token",
                "192.0.2.10",
                "local_registration"
        )))
                .thenReturn(VerificationOutcome.REJECTED)
                .thenReturn(VerificationOutcome.UNAVAILABLE);

        assertThatThrownBy(() -> service.verify(COMMAND))
                .isInstanceOf(HumanVerificationRejectedException.class);
        assertThatThrownBy(() -> service.verify(COMMAND))
                .isInstanceOf(HumanVerificationUnavailableException.class);
    }
}
