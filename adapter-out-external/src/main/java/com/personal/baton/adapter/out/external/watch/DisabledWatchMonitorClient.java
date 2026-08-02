package com.personal.baton.adapter.out.external.watch;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import java.util.Objects;

public final class DisabledWatchMonitorClient implements WatchMonitorClient {

    @Override
    public SynchronizationResult synchronize(WatchMonitorDelivery delivery) {
        Objects.requireNonNull(delivery, "WATCH delivery는 필수입니다");
        return SynchronizationResult.retryable("WATCH_DISABLED");
    }
}
