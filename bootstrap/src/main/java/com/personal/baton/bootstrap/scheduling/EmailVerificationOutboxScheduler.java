package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class EmailVerificationOutboxScheduler {

    private static final Log log = LogFactory.getLog(EmailVerificationOutboxScheduler.class);

    private final DispatchEmailVerificationOutboxUseCase dispatchEmailVerificationOutbox;

    EmailVerificationOutboxScheduler(
            DispatchEmailVerificationOutboxUseCase dispatchEmailVerificationOutbox
    ) {
        this.dispatchEmailVerificationOutbox = dispatchEmailVerificationOutbox;
    }

    @Scheduled(
            fixedDelayString = "${baton.identity.email-verification.dispatch-interval:PT10S}",
            initialDelayString = "${baton.identity.email-verification.dispatch-interval:PT10S}",
            scheduler = "emailVerificationTaskScheduler"
    )
    void dispatchPending() {
        DispatchEmailVerificationOutboxUseCase.DispatchResult result =
                dispatchEmailVerificationOutbox.dispatchPending();
        if (result.hasFailures()) {
            log.warn("이메일 인증 outbox 전달에 실패가 있습니다. claimed="
                    + result.claimedCount() + ", delivered=" + result.deliveredCount()
                    + ", superseded=" + result.supersededCount()
                    + ", failed=" + result.failedCount());
        }
    }
}
