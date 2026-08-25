CREATE TABLE calendar_snapshot_outbox (
    id INT NOT NULL AUTO_INCREMENT,
    event_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    calendar_status VARCHAR(16) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    description TEXT NULL,
    location VARCHAR(512) NULL,
    time_type VARCHAR(32) NOT NULL,
    at_instant DATETIME(6) NULL,
    at_local DATETIME(6) NULL,
    zone_id VARCHAR(255) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    source_updated_at DATETIME(6) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_calendar_snapshot_outbox_event (event_id),
    KEY idx_calendar_snapshot_outbox_source_revision (source_item_id, id),
    KEY idx_calendar_snapshot_outbox_pending (delivery_status, available_at, id),
    CONSTRAINT chk_calendar_snapshot_outbox_status CHECK (
        calendar_status IN ('ACTIVE', 'CANCELLED')
    ),
    CONSTRAINT chk_calendar_snapshot_outbox_time CHECK (
        (
            time_type = 'UTC_POINT'
            AND at_instant IS NOT NULL
            AND at_local IS NULL
            AND zone_id IS NULL
            AND start_date IS NULL
            AND end_date IS NULL
        )
        OR (
            time_type = 'ZONED_LOCAL_POINT'
            AND at_instant IS NULL
            AND at_local IS NOT NULL
            AND zone_id IS NOT NULL
            AND start_date IS NULL
            AND end_date IS NULL
        )
        OR (
            time_type = 'ALL_DAY'
            AND at_instant IS NULL
            AND at_local IS NULL
            AND zone_id IS NULL
            AND start_date IS NOT NULL
            AND end_date IS NOT NULL
            AND start_date < end_date
        )
    ),
    CONSTRAINT chk_calendar_snapshot_outbox_delivery_status CHECK (
        delivery_status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')
    ),
    CONSTRAINT chk_calendar_snapshot_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_calendar_snapshot_outbox_source_time CHECK (
        source_updated_at <= occurred_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
