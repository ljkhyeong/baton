package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.identity.port.in.ExpireEmailVerificationOutboxUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class EmailVerificationOutboxExpiryScheduler {

    private static final Log log = LogFactory.getLog(
            EmailVerificationOutboxExpiryScheduler.class
    );

    private final ExpireEmailVerificationOutboxUseCase expireEmailVerificationOutbox;

    EmailVerificationOutboxExpiryScheduler(
            ExpireEmailVerificationOutboxUseCase expireEmailVerificationOutbox
    ) {
        this.expireEmailVerificationOutbox = expireEmailVerificationOutbox;
    }

    @Scheduled(
            fixedDelayString = "${baton.identity.email-verification.expiry-interval:PT1M}",
            initialDelayString = "${baton.identity.email-verification.expiry-interval:PT1M}",
            scheduler = "taskScheduler"
    )
    void expireUndeliverable() {
        int expiredCount = expireEmailVerificationOutbox.expireUndeliverable();
        if (expiredCount > 0) {
            log.info("만료된 이메일 인증 outbox payload를 정리했습니다. expired="
                    + expiredCount);
        }
    }
}
