package com.personal.baton.application.watch;

public record WatchMonitorSnapshot(
        long sourceRevision,
        String resourceReference,
        WatchMonitoringState monitoringState,
        String targetUrl
) {
}
