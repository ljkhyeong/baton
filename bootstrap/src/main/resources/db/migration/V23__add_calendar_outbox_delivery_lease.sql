ALTER TABLE calendar_snapshot_outbox
    ADD COLUMN lease_token BINARY(16) NULL AFTER available_at,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER lease_token,
    ADD COLUMN completed_at DATETIME(6) NULL AFTER lease_expires_at,
    ADD COLUMN result_code VARCHAR(64) NULL AFTER completed_at,
    ADD COLUMN last_error_code VARCHAR(64) NULL AFTER result_code,
    ADD CONSTRAINT chk_calendar_snapshot_outbox_delivery_lifecycle CHECK (
        (
            delivery_status = 'PENDING'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NULL
        )
        OR (
            delivery_status = 'PROCESSING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND completed_at IS NULL
        )
        OR (
            delivery_status IN ('DELIVERED', 'FAILED')
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NOT NULL
        )
    );
