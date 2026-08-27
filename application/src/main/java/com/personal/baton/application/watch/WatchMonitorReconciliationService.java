package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorReconciliationService implements ReconcileWatchMonitorsUseCase {

    static final int RECONCILIATION_PAGE_SIZE = 100;

    private final WatchMonitorOutboxPort outboxPort;
    private final WatchMonitorChangeRecorder changeRecorder;

    public WatchMonitorReconciliationService(
            WatchMonitorOutboxPort outboxPort,
            WatchMonitorChangeRecorder changeRecorder
    ) {
        this.outboxPort = outboxPort;
        this.changeRecorder = changeRecorder;
    }

    @Override
    public ReconciliationResult reconcile() {
        UUID afterResourceId = null;
        int candidateCount = 0;
        int appendedCount = 0;

        while (true) {
            List<WatchMonitorCandidate> candidates = outboxPort.findReconciliationCandidates(
                    afterResourceId,
                    RECONCILIATION_PAGE_SIZE
            );
            if (candidates.isEmpty()) {
                break;
            }

            candidateCount += candidates.size();
            for (WatchMonitorCandidate candidate : candidates) {
                if (changeRecorder.reconcile(candidate)) {
                    appendedCount++;
                }
            }
            afterResourceId = candidates.getLast().resourceId();
            if (candidates.size() < RECONCILIATION_PAGE_SIZE) {
                break;
            }
        }
        return new ReconciliationResult(candidateCount, appendedCount);
    }
}
