CREATE TABLE brief_edition_generation_execution (
    execution_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    week_start DATE NOT NULL,
    zone_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    delivery_watermark BIGINT NOT NULL,
    execution_status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lease_token BINARY(16) NULL,
    lease_expires_at DATETIME(6) NULL,
    edition_id BINARY(16) NULL,
    edition_generation BIGINT NULL,
    source_cursor BIGINT NULL,
    edition_etag VARCHAR(128) NULL,
    created_new BOOLEAN NULL,
    result_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (execution_id),
    UNIQUE KEY uk_brief_edition_generation_boundary (
        team_id,
        season_id,
        week_start,
        zone_id,
        delivery_watermark
    ),
    KEY idx_brief_edition_generation_lease (
        execution_status,
        lease_expires_at
    ),
    CONSTRAINT fk_brief_edition_generation_team
        FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT fk_brief_edition_generation_season
        FOREIGN KEY (season_id) REFERENCES seasons(id),
    CONSTRAINT chk_brief_edition_generation_watermark CHECK (
        delivery_watermark >= 0
    ),
    CONSTRAINT chk_brief_edition_generation_status CHECK (
        execution_status IN (
            'PENDING',
            'PROCESSING',
            'SUCCEEDED',
            'RETRYABLE_FAILURE',
            'PERMANENT_FAILURE'
        )
    ),
    CONSTRAINT chk_brief_edition_generation_attempt CHECK (
        attempt_count >= 0
    ),
    CONSTRAINT chk_brief_edition_generation_lease CHECK (
        (
            execution_status = 'PROCESSING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR (
            execution_status <> 'PROCESSING'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT chk_brief_edition_generation_result CHECK (
        (
            execution_status = 'SUCCEEDED'
            AND edition_id IS NOT NULL
            AND edition_generation IS NOT NULL
            AND source_cursor IS NOT NULL
            AND edition_etag IS NOT NULL
            AND created_new IS NOT NULL
        )
        OR execution_status <> 'SUCCEEDED'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

