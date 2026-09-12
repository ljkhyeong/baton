package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.EmailDeliveryEvent;
import com.personal.baton.application.identity.port.out.EmailDeliveryReceiptPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmailDeliveryReceiptAdapter implements EmailDeliveryReceiptPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEmailDeliveryReceiptAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(long deliveryId, EmailDeliveryEvent event, Instant occurredAt) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO email_delivery_receipts (delivery_id, event, occurred_at)
                SELECT id, ?, ? FROM email_verification_delivery_outbox
                WHERE id = ? AND attempt_count > 0
                ON DUPLICATE KEY UPDATE occurred_at = GREATEST(occurred_at, ?)
                """, event.name(), timestamp, deliveryId, timestamp);
    }
}
