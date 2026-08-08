CREATE TABLE relay_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contract_version INT NOT NULL,
    event_id BINARY(16) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL,
    subject_reference VARCHAR(128) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    publication_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_token BINARY(16) NULL,
    lease_expires_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_relay_event_outbox_event (event_id),
    KEY idx_relay_event_outbox_pending_claim (publication_status, available_at, id),
    KEY idx_relay_event_outbox_expired_claim (publication_status, lease_expires_at, id),
    CONSTRAINT chk_relay_event_outbox_contract_version CHECK (contract_version = 1),
    CONSTRAINT chk_relay_event_outbox_event_version CHECK (event_version > 0),
    CONSTRAINT chk_relay_event_outbox_publication_status CHECK (
        publication_status IN ('PENDING', 'PUBLISHING', 'PUBLISHED')
    ),
    CONSTRAINT chk_relay_event_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_relay_event_outbox_lease CHECK (
        (
            publication_status = 'PUBLISHING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR (
            publication_status <> 'PUBLISHING'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT chk_relay_event_outbox_publication CHECK (
        (
            publication_status = 'PUBLISHED'
            AND published_at IS NOT NULL
        )
        OR (
            publication_status <> 'PUBLISHED'
            AND published_at IS NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
