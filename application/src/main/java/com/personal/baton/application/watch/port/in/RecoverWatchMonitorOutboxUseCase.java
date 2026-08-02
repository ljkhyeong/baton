package com.personal.baton.application.watch.port.in;

public interface RecoverWatchMonitorOutboxUseCase {

    void validateSourceNamespace();

    int requeueOperationalFailures();
}
