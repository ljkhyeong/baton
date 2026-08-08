package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase.DispatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationOutboxSchedulerTest {

    @DisplayName("이메일 인증 scheduler는 commit 이후 outbox dispatcher만 호출한다")
    @Test
    void delegatesToOutboxDispatcher() {
        DispatchEmailVerificationOutboxUseCase dispatch = mock(
                DispatchEmailVerificationOutboxUseCase.class
        );
        when(dispatch.dispatchPending()).thenReturn(new DispatchResult(1, 1, 0, 0));
        EmailVerificationOutboxScheduler scheduler = new EmailVerificationOutboxScheduler(dispatch);

        scheduler.dispatchPending();

        verify(dispatch).dispatchPending();
    }
}
