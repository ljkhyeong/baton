package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.NotificationReadReceiptPort;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationReadReceiptAdapter implements NotificationReadReceiptPort {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcNotificationReadReceiptAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public Set<UUID> findRead(UUID accountId, Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) return Set.of();
        var ids = notificationIds.stream().map(id -> ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).toList();
        return new HashSet<>(jdbc.query("""
                SELECT BIN_TO_UUID(notification_id) AS id FROM notification_read_receipts
                WHERE account_id = UUID_TO_BIN(:accountId) AND notification_id IN (:ids)
                """, Map.of("accountId", accountId.toString(), "ids", ids),
                (row, index) -> UUID.fromString(row.getString("id"))));
    }
    @Override
    public void markRead(UUID accountId, UUID notificationId, Instant readAt) {
        jdbc.update("""
                INSERT INTO notification_read_receipts (account_id, notification_id, read_at)
                VALUES (UUID_TO_BIN(:accountId), UUID_TO_BIN(:id), :readAt)
                ON DUPLICATE KEY UPDATE read_at = notification_read_receipts.read_at
                """, Map.of("accountId", accountId.toString(), "id", notificationId.toString(),
                "readAt", LocalDateTime.ofInstant(readAt, ZoneOffset.UTC)));
    }
}
