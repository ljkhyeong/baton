package com.personal.baton.application.relay.port.out;

import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface RelayOutboxPort {

    void append(RelayOutboxEvent event);

    Optional<RelayOutboxPublication> claim(Instant now, Duration leaseDuration);

    boolean markPublished(RelayOutboxPublication publication, Instant publishedAt);

    boolean reschedule(
            RelayOutboxPublication publication,
            Instant availableAt,
            String errorCode
    );
}
