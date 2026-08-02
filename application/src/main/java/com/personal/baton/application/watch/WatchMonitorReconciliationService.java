package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorReconciliationService implements ReconcileWatchMonitorsUseCase {

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
        List<WatchMonitorCandidate> candidates = outboxPort.findReconciliationCandidates();
        int appendedCount = 0;
        for (WatchMonitorCandidate candidate : candidates) {
            if (changeRecorder.reconcile(candidate)) {
                appendedCount++;
            }
        }
        return new ReconciliationResult(candidates.size(), appendedCount);
    }
}
