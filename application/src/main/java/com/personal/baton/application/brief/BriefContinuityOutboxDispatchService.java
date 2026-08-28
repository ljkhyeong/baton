package com.personal.baton.application.brief;

import com.personal.baton.application.brief.port.in.DispatchBriefContinuityOutboxUseCase;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import com.personal.baton.application.brief.port.out.BriefContinuityClient.DeliveryResult;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class BriefContinuityOutboxDispatchService
        implements DispatchBriefContinuityOutboxUseCase {

    private static final Log log = LogFactory.getLog(
            BriefContinuityOutboxDispatchService.class
    );
    private static final int BATCH_SIZE = 1;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);

    private final BriefContinuityOutboxPort outboxPort;
    private final BriefContinuityClient client;
    private final Clock clock;

    public BriefContinuityOutboxDispatchService(
            BriefContinuityOutboxPort outboxPort,
            BriefContinuityClient client,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public DispatchResult dispatchPending() {
        Instant claimedAt = clock.instant();
        List<BriefContinuityDelivery> deliveries = outboxPort.claimPending(
                BATCH_SIZE,
                claimedAt,
                LEASE_DURATION
        );
        int deliveredCount = 0;
        int failedCount = 0;
        for (BriefContinuityDelivery delivery : deliveries) {
            if (dispatchOne(delivery)) {
                deliveredCount++;
            } else {
                failedCount++;
            }
        }
        return new DispatchResult(deliveries.size(), deliveredCount, failedCount);
    }

    private boolean dispatchOne(BriefContinuityDelivery delivery) {
        DeliveryResult result;
        try {
            result = client.deliver(delivery);
        } catch (RuntimeException exception) {
            log.warn("BRIEF 이벤트 전달이 예상하지 못하게 실패했습니다. aggregateRevision="
                    + delivery.event().aggregateRevision()
                    + ", exceptionType=" + exception.getClass().getSimpleName());
            result = DeliveryResult.retryable("UNEXPECTED_CLIENT_FAILURE");
        }

        Instant completedAt = clock.instant();
        return switch (result.outcome()) {
            case DELIVERED -> outboxPort.markDelivered(
                    delivery.outboxId(),
                    delivery.leaseToken(),
                    completedAt,
                    normalizedCode(result.code(), "BRIEF_DELIVERED")
            );
            case RETRYABLE_FAILURE -> {
                outboxPort.markRetry(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        completedAt,
                        normalizedCode(result.code(), "BRIEF_RETRYABLE_FAILURE")
                );
                yield false;
            }
            case PERMANENT_FAILURE -> {
                outboxPort.markFailed(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        completedAt,
                        normalizedCode(result.code(), "BRIEF_DELIVERY_FAILED")
                );
                yield false;
            }
        };
    }

    private String normalizedCode(String code, String fallback) {
        if (code == null || code.isBlank()) {
            return fallback;
        }
        return code.length() <= 64 ? code : code.substring(0, 64);
    }
}
