package com.personal.baton.application.watch.port.out;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface WatchMonitorSnapshotPort {
    Optional<WatchMonitorSnapshot> findLatestMonitor(UUID resourceId);
}
