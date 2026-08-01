CREATE TABLE watch_monitor_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    resource_reference VARCHAR(128) NOT NULL,
    monitoring_state VARCHAR(16) NOT NULL,
    target_url VARCHAR(2048) NULL,
    occurred_at DATETIME(6) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_token BINARY(16) NULL,
    lease_expires_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    result_code VARCHAR(64) NULL,
    last_error_code VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_watch_monitor_outbox_event (event_id),
    KEY idx_watch_monitor_outbox_resource_revision (resource_id, id),
    KEY idx_watch_monitor_outbox_pending_claim (delivery_status, available_at, id),
    KEY idx_watch_monitor_outbox_expired_claim (delivery_status, lease_expires_at, id),
    CONSTRAINT chk_watch_monitor_outbox_monitoring_state CHECK (
        monitoring_state IN ('ACTIVE', 'INACTIVE')
    ),
    CONSTRAINT chk_watch_monitor_outbox_target CHECK (
        (monitoring_state = 'ACTIVE' AND target_url IS NOT NULL)
        OR (monitoring_state = 'INACTIVE' AND target_url IS NULL)
    ),
    CONSTRAINT chk_watch_monitor_outbox_delivery_status CHECK (
        delivery_status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')
    ),
    CONSTRAINT chk_watch_monitor_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_watch_monitor_outbox_lease CHECK (
        (
            delivery_status = 'PROCESSING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR (
            delivery_status <> 'PROCESSING'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT chk_watch_monitor_outbox_completion CHECK (
        (
            delivery_status IN ('DELIVERED', 'FAILED')
            AND completed_at IS NOT NULL
        )
        OR (
            delivery_status IN ('PENDING', 'PROCESSING')
            AND completed_at IS NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
