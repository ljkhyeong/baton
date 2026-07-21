ALTER TABLE teams
    ADD COLUMN idempotency_key_hash CHAR(64) NULL AFTER access_key_hash,
    ADD COLUMN creation_request_fingerprint CHAR(64) NULL AFTER idempotency_key_hash,
    ADD COLUMN creation_season_id BINARY(16) NULL AFTER creation_request_fingerprint,
    ADD COLUMN last_access_key_change_idempotency_hash CHAR(64) NULL AFTER creation_season_id,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER last_access_key_change_idempotency_hash,
    ADD UNIQUE KEY uk_teams_idempotency_key_hash (idempotency_key_hash),
    ADD CONSTRAINT chk_teams_creation_request_metadata CHECK (
        (
            idempotency_key_hash IS NULL
            AND creation_request_fingerprint IS NULL
            AND creation_season_id IS NULL
        )
        OR (
            idempotency_key_hash IS NOT NULL
            AND creation_request_fingerprint IS NOT NULL
            AND creation_season_id IS NOT NULL
        )
    );

CREATE TABLE access_key_change_history (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_access_key_change_history_team_hash (team_id, idempotency_hash),
    CONSTRAINT fk_access_key_change_history_team FOREIGN KEY (team_id) REFERENCES teams (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
