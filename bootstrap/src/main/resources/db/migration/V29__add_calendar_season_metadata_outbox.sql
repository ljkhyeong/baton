CREATE TABLE calendar_season_metadata_outbox (
    id INT NOT NULL AUTO_INCREMENT,
    season_id BINARY(16) NOT NULL,
    display_name VARCHAR(512) NOT NULL,
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
    KEY idx_calendar_season_metadata_revision (season_id, id),
    KEY idx_calendar_season_metadata_pending (delivery_status, available_at, id),
    CONSTRAINT chk_calendar_season_metadata_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_calendar_season_metadata_lifecycle CHECK (
        (delivery_status = 'PENDING'
            AND lease_token IS NULL AND lease_expires_at IS NULL AND completed_at IS NULL)
        OR (delivery_status = 'PROCESSING'
            AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
        OR (delivery_status IN ('DELIVERED', 'FAILED')
            AND lease_token IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
