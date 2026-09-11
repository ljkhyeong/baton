package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase.DispatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationOutboxSchedulerTest {

    private final DispatchEmailVerificationOutboxUseCase dispatch = mock(
            DispatchEmailVerificationOutboxUseCase.class
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DispatchEmailVerificationOutboxUseCase.class, () -> dispatch)
            .withUserConfiguration(EmailVerificationOutboxScheduler.class);

    @DisplayName("이메일 인증 스케줄러는 커밋 뒤 아웃박스 디스패처만 호출한다")
    @Test
    void delegatesToOutboxDispatcher() {
        when(dispatch.dispatchPending()).thenReturn(new DispatchResult(1, 1, 0, 0));
        EmailVerificationOutboxScheduler scheduler = new EmailVerificationOutboxScheduler(dispatch);

        scheduler.dispatchPending();

        verify(dispatch).dispatchPending();
    }

    @DisplayName("SMTP 발송 모드에서는 이메일 인증 아웃박스 스케줄러를 등록한다")
    @Test
    void registersSchedulerForSmtpDelivery() {
        contextRunner
                .withPropertyValues("baton.identity.email-verification.delivery=smtp")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(EmailVerificationOutboxScheduler.class));
    }

    @DisplayName("메일 발송을 비활성화하면 대기 작업의 처리 권한을 확보할 스케줄러를 등록하지 않는다")
    @Test
    void pausesSchedulerForDisabledDelivery() {
        contextRunner
                .withPropertyValues("baton.identity.email-verification.delivery=disabled")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(EmailVerificationOutboxScheduler.class));
    }
}
