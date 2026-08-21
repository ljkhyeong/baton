package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.watch", name = "enabled")
class WatchMonitorScheduler {

    private static final Log log = LogFactory.getLog(WatchMonitorScheduler.class);

    private final DispatchWatchMonitorOutboxUseCase dispatchWatchMonitorOutbox;
    private final ReconcileWatchMonitorsUseCase reconcileWatchMonitors;

    WatchMonitorScheduler(
            DispatchWatchMonitorOutboxUseCase dispatchWatchMonitorOutbox,
            ReconcileWatchMonitorsUseCase reconcileWatchMonitors
    ) {
        this.dispatchWatchMonitorOutbox = dispatchWatchMonitorOutbox;
        this.reconcileWatchMonitors = reconcileWatchMonitors;
    }

    @Scheduled(
            fixedDelayString = "${baton.watch.dispatch-interval:PT10S}",
            initialDelayString = "${baton.watch.dispatch-interval:PT10S}",
            scheduler = "watchTaskScheduler"
    )
    void dispatchPending() {
        DispatchWatchMonitorOutboxUseCase.DispatchResult result =
                dispatchWatchMonitorOutbox.dispatchPending();
        if (result.hasFailures()) {
            log.warn("WATCH outbox 전달에 실패가 있습니다. claimed="
                    + result.claimedCount() + ", delivered=" + result.deliveredCount()
                    + ", failed=" + result.failedCount());
        }
    }

    @Scheduled(
            fixedDelayString = "${baton.watch.reconciliation-interval:PT6H}",
            initialDelayString = "${baton.watch.reconciliation-initial-delay:PT10S}",
            scheduler = "watchTaskScheduler"
    )
    void reconcile() {
        ReconcileWatchMonitorsUseCase.ReconciliationResult result =
                reconcileWatchMonitors.reconcile();
        if (result.appendedCount() > 0) {
            log.info("WATCH reconciliation이 누락된 snapshot을 복구했습니다. candidates="
                    + result.candidateCount() + ", appended=" + result.appendedCount());
        }
    }
}
