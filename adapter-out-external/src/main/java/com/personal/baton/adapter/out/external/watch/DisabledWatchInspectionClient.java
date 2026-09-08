package com.personal.baton.adapter.out.external.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort;

public final class DisabledWatchInspectionClient implements WatchMonitorInspectionPort {
    @Override
    public Inspection inspect(String resourceReference) { return Inspection.unavailable(); }

    @Override
    public CheckRequest requestCheck(String resourceReference) { return CheckRequest.unavailable(); }
}
