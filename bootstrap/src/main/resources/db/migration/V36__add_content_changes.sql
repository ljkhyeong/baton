CREATE TABLE content_changes (
    id BINARY(16) PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    record_kind VARCHAR(20) NOT NULL,
    record_id BINARY(16) NOT NULL,
    actor_account_id BINARY(16) NULL,
    actor_name VARCHAR(100) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_content_changes_team FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT fk_content_changes_season FOREIGN KEY (season_id) REFERENCES seasons(id),
    CONSTRAINT chk_content_changes_kind CHECK (record_kind IN ('DECISION', 'ROLE_RESOURCE')),
    INDEX idx_content_changes_record (team_id, season_id, record_kind, record_id, changed_at DESC, id DESC)
);
CREATE TABLE content_change_fields (
    change_id BINARY(16) NOT NULL,
    field_position INT NOT NULL,
    field_name VARCHAR(50) NOT NULL,
    before_value TEXT NULL,
    after_value TEXT NULL,
    PRIMARY KEY (change_id, field_position),
    CONSTRAINT fk_content_change_fields_change FOREIGN KEY (change_id) REFERENCES content_changes(id)
);
