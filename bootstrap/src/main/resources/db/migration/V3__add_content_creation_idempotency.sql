CREATE TABLE content_creation_idempotency (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_creation_idempotency_team_hash (team_id, idempotency_hash),
    KEY idx_content_creation_idempotency_season (season_id),
    CONSTRAINT fk_content_creation_idempotency_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_content_creation_idempotency_season FOREIGN KEY (season_id) REFERENCES seasons (id),
    CONSTRAINT chk_content_creation_idempotency_operation CHECK (
        operation IN ('ROLE', 'ROUTINE', 'DECISION', 'HANDOFF_ITEM')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
