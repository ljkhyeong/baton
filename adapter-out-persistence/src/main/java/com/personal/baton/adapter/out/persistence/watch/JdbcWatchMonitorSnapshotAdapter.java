package com.personal.baton.adapter.out.persistence.watch;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorSnapshotPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWatchMonitorSnapshotAdapter implements WatchMonitorSnapshotPort {
    private final JdbcTemplate jdbc;

    public JdbcWatchMonitorSnapshotAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true)
    public Optional<WatchMonitorSnapshot> findLatestMonitor(UUID resourceId) {
        return jdbc.query("""
                SELECT id, resource_reference, monitoring_state, target_url
                FROM watch_monitor_outbox
                WHERE resource_id = UUID_TO_BIN(?)
                ORDER BY id DESC LIMIT 1
                """, (row, index) -> new WatchMonitorSnapshot(row.getLong("id"),
                row.getString("resource_reference"),
                WatchMonitoringState.valueOf(row.getString("monitoring_state")),
                row.getString("target_url")), resourceId.toString()).stream().findFirst();
    }
}
