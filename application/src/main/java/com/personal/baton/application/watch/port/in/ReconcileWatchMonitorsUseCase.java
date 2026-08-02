package com.personal.baton.application.watch.port.in;

public interface ReconcileWatchMonitorsUseCase {

    ReconciliationResult reconcile();

    record ReconciliationResult(int candidateCount, int appendedCount) {
    }
}
