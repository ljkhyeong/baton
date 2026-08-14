package com.personal.baton.adapter.out.external.watch;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;

public final class DisabledWatchMonitorClient implements WatchMonitorClient {

    @Override
    public SynchronizationResult synchronize(WatchMonitorDelivery delivery) {
        return SynchronizationResult.retryable("WATCH_DISABLED");
    }
}
