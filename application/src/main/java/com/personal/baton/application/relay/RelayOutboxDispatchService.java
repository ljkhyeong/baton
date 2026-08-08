package com.personal.baton.application.relay;

import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelayOutboxDispatchService implements DispatchRelayOutboxUseCase {

    private static final Log log = LogFactory.getLog(RelayOutboxDispatchService.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);

    private final RelayOutboxPort outboxPort;
    private final RelayEventPublisher publisher;
    private final Clock clock;
    private final RelayOutboxRetryPolicy retryPolicy;

    public RelayOutboxDispatchService(
            RelayOutboxPort outboxPort,
            RelayEventPublisher publisher,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.publisher = publisher;
        this.clock = clock;
        this.retryPolicy = new RelayOutboxRetryPolicy();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispatchResult dispatchPending() {
        Optional<RelayOutboxPublication> claimed = outboxPort.claim(
                Instant.now(clock),
                LEASE_DURATION
        );
        if (claimed.isEmpty()) {
            return new DispatchResult(0, 0, 0);
        }
        return dispatchOne(claimed.orElseThrow());
    }

    private DispatchResult dispatchOne(RelayOutboxPublication publication) {
        RelayPublishResult result;
        try {
            result = publisher.publish(publication);
        } catch (RuntimeException exception) {
            log.error("RELAY event 발행이 예상하지 못하게 실패했습니다. exceptionType="
                    + exception.getClass().getSimpleName());
            result = RelayPublishResult.retryable("UNEXPECTED_PUBLISH_FAILURE");
        }

        Instant completedAt = Instant.now(clock);
        if (result.outcome() == RelayPublishResult.Outcome.CONFIRMED) {
            boolean marked = outboxPort.markPublished(publication, completedAt);
            return new DispatchResult(1, marked ? 1 : 0, marked ? 0 : 1);
        }

        Instant availableAt = completedAt.plus(
                retryPolicy.delayAfterAttempt(publication.attemptCount())
        );
        outboxPort.reschedule(
                publication,
                availableAt,
                normalizedErrorCode(result.code())
        );
        return new DispatchResult(1, 0, 1);
    }

    private String normalizedErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "RELAY_PUBLISH_FAILED";
        }
        return errorCode.length() <= 64 ? errorCode : errorCode.substring(0, 64);
    }
}
