package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.brief.port.in.DispatchBriefContinuityOutboxUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.brief", name = "delivery-enabled")
class BriefContinuityOutboxScheduler {

    private static final Log log = LogFactory.getLog(BriefContinuityOutboxScheduler.class);

    private final DispatchBriefContinuityOutboxUseCase dispatchUseCase;

    BriefContinuityOutboxScheduler(DispatchBriefContinuityOutboxUseCase dispatchUseCase) {
        this.dispatchUseCase = dispatchUseCase;
    }

    @Scheduled(
            fixedDelayString = "${baton.brief.dispatch-interval:PT10S}",
            initialDelayString = "${baton.brief.dispatch-interval:PT10S}",
            scheduler = "briefDeliveryTaskScheduler"
    )
    void dispatchPending() {
        DispatchBriefContinuityOutboxUseCase.DispatchResult result =
                dispatchUseCase.dispatchPending();
        if (result.hasFailures()) {
            log.warn("BRIEF outbox 전달에 실패가 있습니다. claimed="
                    + result.claimedCount() + ", delivered=" + result.deliveredCount()
                    + ", failed=" + result.failedCount());
        }
    }
}
