ALTER TABLE brief_continuity_outbox
    ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at DATETIME(6) NULL,
    ADD COLUMN lease_token BINARY(16) NULL,
    ADD COLUMN lease_expires_at DATETIME(6) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN result_code VARCHAR(64) NULL,
    ADD COLUMN last_error_code VARCHAR(64) NULL;

UPDATE brief_continuity_outbox
SET available_at = occurred_at;

ALTER TABLE brief_continuity_outbox
    MODIFY COLUMN available_at DATETIME(6) NOT NULL,
    ADD KEY idx_brief_continuity_outbox_pending_claim (
        delivery_status,
        available_at,
        id
    ),
    ADD KEY idx_brief_continuity_outbox_expired_claim (
        delivery_status,
        lease_expires_at,
        id
    ),
    ADD CONSTRAINT chk_brief_continuity_outbox_delivery_status CHECK (
        delivery_status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')
    ),
    ADD CONSTRAINT chk_brief_continuity_outbox_attempt_count CHECK (
        attempt_count >= 0
    ),
    ADD CONSTRAINT chk_brief_continuity_outbox_lease CHECK (
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
    ADD CONSTRAINT chk_brief_continuity_outbox_completion CHECK (
        (
            delivery_status IN ('DELIVERED', 'FAILED')
            AND completed_at IS NOT NULL
        )
        OR (
            delivery_status IN ('PENDING', 'PROCESSING')
            AND completed_at IS NULL
        )
    );
