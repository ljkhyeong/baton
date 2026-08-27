CREATE TABLE email_verification_delivery_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    identity_id BINARY(16) NOT NULL,
    payload_ciphertext VARCHAR(4096)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NULL,
    payload_nonce CHAR(16)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NULL,
    challenge_token_hash CHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NULL,
    expires_at DATETIME(6) NOT NULL,
    delivery_status VARCHAR(20)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_token BINARY(16) NULL,
    lease_expires_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    last_error_code VARCHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_email_verification_outbox_claim (
        delivery_status,
        available_at,
        lease_expires_at,
        id
    ),
    KEY idx_email_verification_outbox_identity (identity_id, id),
    CONSTRAINT fk_email_verification_outbox_identity
        FOREIGN KEY (identity_id) REFERENCES account_identities (id),
    CONSTRAINT chk_email_verification_outbox_status CHECK (
        delivery_status IN (
            'PENDING',
            'PROCESSING',
            'DELIVERED',
            'SUPERSEDED',
            'FAILED'
        )
    ),
    CONSTRAINT chk_email_verification_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_email_verification_outbox_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_email_verification_outbox_available CHECK (available_at >= created_at),
    CONSTRAINT chk_email_verification_outbox_payload CHECK (
        (
            delivery_status IN ('PENDING', 'PROCESSING')
            AND payload_ciphertext REGEXP '^[A-Za-z0-9_-]{16,4096}$'
            AND payload_nonce REGEXP '^[A-Za-z0-9_-]{16}$'
            AND challenge_token_hash REGEXP '^[0-9a-f]{64}$'
        )
        OR (
            delivery_status IN ('DELIVERED', 'SUPERSEDED', 'FAILED')
            AND payload_ciphertext IS NULL
            AND payload_nonce IS NULL
            AND challenge_token_hash IS NULL
        )
    ),
    CONSTRAINT chk_email_verification_outbox_lease CHECK (
        (
            delivery_status = 'PROCESSING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND completed_at IS NULL
        )
        OR (
            delivery_status = 'PENDING'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NULL
        )
        OR (
            delivery_status IN ('DELIVERED', 'SUPERSEDED', 'FAILED')
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
