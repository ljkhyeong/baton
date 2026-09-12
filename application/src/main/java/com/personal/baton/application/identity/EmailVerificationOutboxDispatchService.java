package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort.EmailVerificationDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationOutboxDispatchService
        implements DispatchEmailVerificationOutboxUseCase {

    private static final Log log = LogFactory.getLog(
            EmailVerificationOutboxDispatchService.class
    );
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAXIMUM_ATTEMPTS = 8;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);
    private static final String DELIVERY_FAILURE_CODE = "EMAIL_DELIVERY_UNAVAILABLE";
    private static final String PAYLOAD_KEY_FAILURE_CODE = "EMAIL_PAYLOAD_KEY_UNAVAILABLE";
    private static final String INVALID_PAYLOAD_CODE = "EMAIL_PAYLOAD_INVALID";

    private final EmailVerificationOutboxPort outboxPort;
    private final EmailVerificationOutboxPayloadProtector payloadProtector;
    private final EmailVerificationDeliveryPort deliveryPort;
    private final Clock clock;
    private final EmailVerificationRetryPolicy retryPolicy;

    public EmailVerificationOutboxDispatchService(
            EmailVerificationOutboxPort outboxPort,
            EmailVerificationOutboxPayloadProtector payloadProtector,
            EmailVerificationDeliveryPort deliveryPort,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.payloadProtector = payloadProtector;
        this.deliveryPort = deliveryPort;
        this.clock = clock;
        this.retryPolicy = new EmailVerificationRetryPolicy();
    }

    @Override
    public DispatchResult dispatchPending() {
        Instant claimedAt = clock.instant();
        List<EmailVerificationOutboxDelivery> deliveries = outboxPort.claimPending(
                DEFAULT_BATCH_SIZE,
                claimedAt,
                DEFAULT_LEASE_DURATION
        );
        int deliveredCount = 0;
        int supersededCount = 0;
        int failedCount = 0;
        for (EmailVerificationOutboxDelivery delivery : deliveries) {
            DispatchOutcome outcome = dispatchOne(delivery);
            switch (outcome) {
                case DELIVERED -> deliveredCount++;
                case SUPERSEDED -> supersededCount++;
                case FAILED -> failedCount++;
            }
        }
        return new DispatchResult(
                deliveries.size(),
                deliveredCount,
                supersededCount,
                failedCount
        );
    }

    private DispatchOutcome dispatchOne(EmailVerificationOutboxDelivery delivery) {
        Instant checkedAt = clock.instant();
        if (!outboxPort.isClaimCurrent(
                delivery.deliveryId(),
                delivery.leaseToken(),
                checkedAt
        )) {
            return DispatchOutcome.SUPERSEDED;
        }

        PlainPayload plainPayload;
        try {
            plainPayload = payloadProtector.unprotect(
                    delivery.protectionContext(),
                    delivery.protectedPayload()
            );
        } catch (EmailVerificationPayloadProtectionException exception) {
            log.warn("이메일 인증 outbox payload를 복호화하지 못했습니다. deliveryId="
                    + delivery.deliveryId()
                    + ", attemptCount=" + delivery.attemptCount()
                    + ", retryable=" + exception.isRetryable());
            if (exception.isRetryable()) {
                recordRetryableFailure(delivery, PAYLOAD_KEY_FAILURE_CODE);
            } else {
                markInvalidPayload(delivery);
            }
            return DispatchOutcome.FAILED;
        } catch (RuntimeException exception) {
            log.warn("이메일 인증 outbox payload 검증이 예상하지 못하게 실패했습니다. deliveryId="
                    + delivery.deliveryId()
                    + ", exceptionType=" + exception.getClass().getSimpleName());
            markInvalidPayload(delivery);
            return DispatchOutcome.FAILED;
        }

        try {
            deliveryPort.deliver(new EmailVerificationDelivery(
                    delivery.deliveryId(),
                    delivery.protectionContext().accountId(),
                    plainPayload.email(),
                    plainPayload.verificationToken(),
                    delivery.protectionContext().expiresAt(),
                    delivery.purpose()
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "이메일 인증 메일 전달에 실패했습니다. deliveryId="
                            + delivery.deliveryId()
                            + ", attemptCount=" + delivery.attemptCount()
                            + ", exceptionType=" + exception.getClass().getSimpleName()
            );
            recordRetryableFailure(delivery, DELIVERY_FAILURE_CODE);
            return DispatchOutcome.FAILED;
        }

        boolean marked = outboxPort.markDelivered(
                delivery.deliveryId(),
                delivery.leaseToken(),
                clock.instant()
        );
        return marked ? DispatchOutcome.DELIVERED : DispatchOutcome.SUPERSEDED;
    }

    private void recordRetryableFailure(
            EmailVerificationOutboxDelivery delivery,
            String errorCode
    ) {
        Instant failedAt = clock.instant();
        if (delivery.attemptCount() >= MAXIMUM_ATTEMPTS) {
            outboxPort.markFailed(
                    delivery.deliveryId(),
                    delivery.leaseToken(),
                    failedAt,
                    errorCode
            );
            return;
        }
        outboxPort.markRetry(
                delivery.deliveryId(),
                delivery.leaseToken(),
                failedAt.plus(retryPolicy.delayAfterAttempt(delivery.attemptCount())),
                errorCode
        );
    }

    private void markInvalidPayload(EmailVerificationOutboxDelivery delivery) {
        outboxPort.markFailed(
                delivery.deliveryId(),
                delivery.leaseToken(),
                clock.instant(),
                INVALID_PAYLOAD_CODE
        );
    }

    private enum DispatchOutcome {
        DELIVERED,
        SUPERSEDED,
        FAILED
    }
}
