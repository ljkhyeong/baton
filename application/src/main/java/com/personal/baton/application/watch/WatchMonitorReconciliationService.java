package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorReconciliationService implements ReconcileWatchMonitorsUseCase {

    static final int RECONCILIATION_PAGE_SIZE = 100;

    private static final Log log = LogFactory.getLog(WatchMonitorReconciliationService.class);

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
        List<UUID> failedResourceIds = new ArrayList<>();

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
                try {
                    if (changeRecorder.reconcile(candidate)) {
                        appendedCount++;
                    }
                } catch (RuntimeException exception) {
                    failedResourceIds.add(candidate.resourceId());
                    log.error(
                            "WATCH 상태 조정에 실패했습니다. resourceId=" + candidate.resourceId(),
                            exception
                    );
                }
            }
            afterResourceId = candidates.getLast().resourceId();
            if (candidates.size() < RECONCILIATION_PAGE_SIZE) {
                break;
            }
        }
        return new ReconciliationResult(
                candidateCount,
                appendedCount,
                List.copyOf(failedResourceIds)
        );
    }
}
