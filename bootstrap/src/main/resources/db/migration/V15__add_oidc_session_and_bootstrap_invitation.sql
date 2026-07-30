CREATE TABLE oidc_external_identities (
    id BINARY(16) NOT NULL,
    identity_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_account_id BINARY(16) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oidc_external_identities_key_hash (identity_key_hash),
    KEY idx_oidc_external_identities_account (user_account_id),
    CONSTRAINT fk_oidc_external_identities_account
        FOREIGN KEY (user_account_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE member_identity_bindings
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' AFTER bound_at,
    ADD COLUMN owner_team_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN role = 'OWNER' THEN team_id ELSE NULL END
        ) STORED,
    ADD CONSTRAINT chk_member_identity_bindings_role
        CHECK (role IN ('MEMBER', 'OWNER')),
    ADD UNIQUE KEY uk_member_identity_bindings_team_owner (owner_team_id);

CREATE TABLE owner_bootstrap_invitations (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    consumed_by_account_id BINARY(16) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_bootstrap_invitations_idempotency (idempotency_key_hash),
    UNIQUE KEY uk_owner_bootstrap_invitations_token (token_hash),
    KEY idx_owner_bootstrap_invitations_member_team (member_id, team_id),
    KEY idx_owner_bootstrap_invitations_consumed_account (consumed_by_account_id),
    CONSTRAINT fk_owner_bootstrap_invitations_member_team
        FOREIGN KEY (member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_owner_bootstrap_invitations_consumed_account
        FOREIGN KEY (consumed_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_owner_bootstrap_invitations_expiry
        CHECK (issued_at < expires_at),
    CONSTRAINT chk_owner_bootstrap_invitations_consumption
        CHECK (
            (consumed_at IS NULL AND consumed_by_account_id IS NULL)
            OR (consumed_at IS NOT NULL AND consumed_by_account_id IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK
        PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID)
        ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
