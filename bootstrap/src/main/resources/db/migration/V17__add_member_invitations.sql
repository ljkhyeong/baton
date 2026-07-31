CREATE TABLE member_invitations (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    issued_by_account_id BINARY(16) NOT NULL,
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_account_id BINARY(16) NULL,
    consumed_at DATETIME(6) NULL,
    consumed_by_account_id BINARY(16) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_invitations_idempotency (idempotency_key_hash),
    UNIQUE KEY uk_member_invitations_token (token_hash),
    KEY idx_member_invitations_team_open (
        team_id,
        consumed_at,
        revoked_at,
        expires_at
    ),
    KEY idx_member_invitations_member_team (member_id, team_id),
    KEY idx_member_invitations_issuer (issued_by_account_id),
    KEY idx_member_invitations_revoker (revoked_by_account_id),
    KEY idx_member_invitations_consumer (consumed_by_account_id),
    CONSTRAINT fk_member_invitations_member_team
        FOREIGN KEY (member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_member_invitations_issuer
        FOREIGN KEY (issued_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_member_invitations_revoker
        FOREIGN KEY (revoked_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_member_invitations_consumer
        FOREIGN KEY (consumed_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_member_invitations_expiry
        CHECK (issued_at < expires_at),
    CONSTRAINT chk_member_invitations_revocation
        CHECK (
            (revoked_at IS NULL AND revoked_by_account_id IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_by_account_id IS NOT NULL)
        ),
    CONSTRAINT chk_member_invitations_consumption
        CHECK (
            (consumed_at IS NULL AND consumed_by_account_id IS NULL)
            OR (consumed_at IS NOT NULL AND consumed_by_account_id IS NOT NULL)
        ),
    CONSTRAINT chk_member_invitations_terminal_state
        CHECK (revoked_at IS NULL OR consumed_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
