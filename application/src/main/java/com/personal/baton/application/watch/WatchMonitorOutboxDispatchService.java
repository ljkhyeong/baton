package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import com.personal.baton.application.watch.port.out.WatchMonitorClient.SynchronizationResult;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorOutboxDispatchService implements DispatchWatchMonitorOutboxUseCase {

    private static final Log log = LogFactory.getLog(WatchMonitorOutboxDispatchService.class);
    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(1);

    private final WatchMonitorOutboxPort outboxPort;
    private final WatchMonitorClient client;
    private final Clock clock;
    private final WatchMonitorRetryPolicy retryPolicy;

    public WatchMonitorOutboxDispatchService(
            WatchMonitorOutboxPort outboxPort,
            WatchMonitorClient client,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.client = client;
        this.clock = clock;
        this.retryPolicy = new WatchMonitorRetryPolicy();
    }

    @Override
    public DispatchResult dispatchPending() {
        Instant claimedAt = Instant.now(clock);
        List<WatchMonitorDelivery> deliveries = outboxPort.claimPending(
                DEFAULT_BATCH_SIZE,
                claimedAt,
                DEFAULT_LEASE_DURATION
        );
        int deliveredCount = 0;
        int failedCount = 0;
        for (WatchMonitorDelivery delivery : deliveries) {
            if (dispatchOne(delivery)) {
                deliveredCount++;
            } else {
                failedCount++;
            }
        }
        return new DispatchResult(deliveries.size(), deliveredCount, failedCount);
    }

    private boolean dispatchOne(WatchMonitorDelivery delivery) {
        SynchronizationResult result;
        try {
            result = client.synchronize(delivery);
        } catch (RuntimeException exception) {
            log.error(
                    "WATCH monitor 동기화 호출이 예상하지 못하게 실패했습니다. sourceRevision="
                            + delivery.sourceRevision()
                            + ", exceptionType=" + exception.getClass().getSimpleName()
            );
            result = SynchronizationResult.retryable("UNEXPECTED_CLIENT_FAILURE");
        }

        Instant completedAt = Instant.now(clock);
        return switch (result.outcome()) {
            case DELIVERED, STALE -> outboxPort.markDelivered(
                    delivery.sourceRevision(),
                    delivery.leaseToken(),
                    completedAt,
                    result.code()
            );
            case INVALID_TARGET -> {
                outboxPort.markInvalidTargetAndAppendInactive(
                        delivery.sourceRevision(),
                        delivery.leaseToken(),
                        UUID.randomUUID(),
                        completedAt
                );
                yield false;
            }
            case RETRYABLE_FAILURE -> {
                Instant availableAt = completedAt.plus(
                        retryPolicy.delayAfterAttempt(delivery.attemptCount())
                );
                outboxPort.markRetry(
                        delivery.sourceRevision(),
                        delivery.leaseToken(),
                        availableAt,
                        normalizedErrorCode(result.code())
                );
                yield false;
            }
            case PERMANENT_FAILURE -> {
                outboxPort.markFailed(
                        delivery.sourceRevision(),
                        delivery.leaseToken(),
                        completedAt,
                        normalizedErrorCode(result.code())
                );
                yield false;
            }
        };
    }

    private String normalizedErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "WATCH_SYNC_FAILED";
        }
        return errorCode.length() <= 64 ? errorCode : errorCode.substring(0, 64);
    }
}
