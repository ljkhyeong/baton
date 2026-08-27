package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.identity.port.in.ExpireEmailVerificationOutboxUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationOutboxExpirySchedulerTest {

    private final ExpireEmailVerificationOutboxUseCase expiry = mock(
            ExpireEmailVerificationOutboxUseCase.class
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ExpireEmailVerificationOutboxUseCase.class, () -> expiry)
            .withUserConfiguration(EmailVerificationOutboxExpiryScheduler.class);

    @DisplayName("이메일 발송을 비활성화해도 만료 payload 정리 scheduler는 등록한다")
    @Test
    void registersExpirySchedulerForDisabledDelivery() {
        contextRunner
                .withPropertyValues("baton.identity.email-verification.delivery=disabled")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(EmailVerificationOutboxExpiryScheduler.class));
    }

    @DisplayName("이메일 인증 만료 scheduler는 독립된 expiry 유스케이스를 호출한다")
    @Test
    void delegatesToExpiryUseCase() {
        when(expiry.expireUndeliverable()).thenReturn(1);
        var scheduler = new EmailVerificationOutboxExpiryScheduler(expiry);

        scheduler.expireUndeliverable();

        verify(expiry).expireUndeliverable();
    }
}
