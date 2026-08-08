package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.relay.publisher", name = "enabled")
class RelayOutboxScheduler {

    private static final Log log = LogFactory.getLog(RelayOutboxScheduler.class);

    private final DispatchRelayOutboxUseCase dispatchRelayOutbox;

    RelayOutboxScheduler(DispatchRelayOutboxUseCase dispatchRelayOutbox) {
        this.dispatchRelayOutbox = dispatchRelayOutbox;
    }

    @Scheduled(
            fixedDelayString = "${baton.relay.publisher.dispatch-interval:PT10S}",
            initialDelayString = "${baton.relay.publisher.dispatch-interval:PT10S}",
            scheduler = "relayPublisherTaskScheduler"
    )
    void dispatchPending() {
        DispatchRelayOutboxUseCase.DispatchResult result = dispatchRelayOutbox.dispatchPending();
        if (result.hasFailures()) {
            log.warn("RELAY outbox 발행에 미확정 결과가 있습니다. claimed="
                    + result.claimedCount() + ", published=" + result.publishedCount()
                    + ", retry=" + result.retryCount());
        }
    }
}
