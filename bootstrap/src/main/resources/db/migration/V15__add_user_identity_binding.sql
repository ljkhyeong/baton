CREATE TABLE user_accounts (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE member_identity_bindings (
    member_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    user_account_id BINARY(16) NOT NULL,
    bound_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_identity_bindings_team_account (team_id, user_account_id),
    KEY idx_member_identity_bindings_account (user_account_id),
    CONSTRAINT fk_member_identity_bindings_member_team
        FOREIGN KEY (member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_member_identity_bindings_account
        FOREIGN KEY (user_account_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
