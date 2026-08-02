package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.in.RecoverWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorOutboxRecoveryService implements RecoverWatchMonitorOutboxUseCase {

    private final WatchMonitorOutboxPort outboxPort;
    private final WatchMonitorSource source;
    private final Clock clock;

    public WatchMonitorOutboxRecoveryService(
            WatchMonitorOutboxPort outboxPort,
            WatchMonitorSource source,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.source = source;
        this.clock = clock;
    }

    @Override
    public void validateSourceNamespace() {
        if (outboxPort.hasMismatchedResourceReferencePrefix(
                source.resourceReferencePrefix()
        )) {
            throw new IllegalStateException(
                    "WATCH source namespace가 기존 outbox resource reference와 다릅니다"
            );
        }
    }

    @Override
    public int requeueOperationalFailures() {
        return outboxPort.requeueOperationalFailures(Instant.now(clock));
    }
}
