package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.DispatchCalendarOutboxUseCase;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class CalendarOutboxDispatchService implements DispatchCalendarOutboxUseCase {

    private static final Log log = LogFactory.getLog(CalendarOutboxDispatchService.class);
    private static final int BATCH_SIZE = 1;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration MAXIMUM_RETRY_DELAY = Duration.ofHours(1);

    private final CalendarOutboxPort outboxPort;
    private final CalendarSnapshotClient client;
    private final CalendarSeasonMetadataClient metadataClient;
    private final CalendarCaptureState captureState;
    private final Clock clock;

    public CalendarOutboxDispatchService(
            CalendarOutboxPort outboxPort,
            CalendarSnapshotClient client,
            CalendarSeasonMetadataClient metadataClient,
            CalendarCaptureState captureState,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.client = client;
        this.metadataClient = metadataClient;
        this.captureState = captureState;
        this.clock = clock;
    }

    @Override
    public DispatchResult dispatchPending() {
        DispatchResult schedules = dispatchBatch(false);
        if (!captureState.seasonMetadataEnabled()) {
            return schedules;
        }
        DispatchResult metadata = dispatchBatch(true);
        return new DispatchResult(
                schedules.claimedCount() + metadata.claimedCount(),
                schedules.deliveredCount() + metadata.deliveredCount(),
                schedules.failedCount() + metadata.failedCount()
        );
    }

    private DispatchResult dispatchBatch(boolean seasonMetadata) {
        List<CalendarDelivery> deliveries = outboxPort.claimPending(
                BATCH_SIZE,
                clock.instant(),
                LEASE_DURATION,
                seasonMetadata
        );
        int deliveredCount = 0;
        int failedCount = 0;
        for (CalendarDelivery delivery : deliveries) {
            if (dispatchOne(delivery)) {
                deliveredCount++;
            } else {
                failedCount++;
            }
        }
        return new DispatchResult(deliveries.size(), deliveredCount, failedCount);
    }

    private boolean dispatchOne(CalendarDelivery delivery) {
        DeliveryResult result;
        try {
            result = switch (delivery.payload()) {
                case CalendarSnapshot snapshot -> client.deliver(snapshot);
                case CalendarSeasonMetadata metadata -> metadataClient.deliver(metadata);
            };
        } catch (RuntimeException exception) {
            log.error(
                    "CAL 전달이 예상하지 못하게 실패했습니다. revision="
                            + delivery.payload().revision()
                            + ", exceptionType=" + exception.getClass().getSimpleName()
            );
            result = DeliveryResult.retryable("UNEXPECTED_CLIENT_FAILURE");
        }

        Instant completedAt = clock.instant();
        return switch (result.outcome()) {
            case DELIVERED -> outboxPort.markDelivered(
                    delivery.payload(),
                    delivery.leaseToken(),
                    completedAt,
                    normalizedCode(result.code(), "CAL_DELIVERED")
            );
            case RETRYABLE_FAILURE -> {
                outboxPort.markRetry(
                        delivery.payload(),
                        delivery.leaseToken(),
                        completedAt.plus(retryDelay(delivery.attemptCount())),
                        normalizedCode(result.code(), "CAL_DELIVERY_FAILED")
                );
                yield false;
            }
            case PERMANENT_FAILURE -> {
                outboxPort.markFailed(
                        delivery.payload(),
                        delivery.leaseToken(),
                        completedAt,
                        normalizedCode(result.code(), "CAL_DELIVERY_REJECTED")
                );
                yield false;
            }
        };
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.min(attemptCount - 1, 16);
        long seconds = Math.multiplyExact(
                INITIAL_RETRY_DELAY.toSeconds(),
                1L << exponent
        );
        return Duration.ofSeconds(Math.min(seconds, MAXIMUM_RETRY_DELAY.toSeconds()));
    }

    private String normalizedCode(String code, String fallback) {
        if (code == null || code.isBlank()) {
            return fallback;
        }
        return code.length() <= 64 ? code : code.substring(0, 64);
    }
}
