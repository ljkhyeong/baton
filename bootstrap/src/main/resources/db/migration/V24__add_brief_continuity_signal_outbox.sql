CREATE TABLE brief_continuity_scope (
    season_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    PRIMARY KEY (season_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE brief_continuity_signal (
    signal_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    signal_type VARCHAR(48) NOT NULL,
    subject_id BINARY(16) NOT NULL,
    source_severity VARCHAR(16) NOT NULL,
    signal_state VARCHAR(16) NOT NULL,
    latest_revision BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (signal_id),
    UNIQUE KEY uk_brief_continuity_signal_identity (
        season_id,
        signal_type,
        subject_id
    ),
    CONSTRAINT chk_brief_continuity_signal_type CHECK (
        signal_type IN (
            'ROLE_UNASSIGNED',
            'ROLE_SUCCESSOR_MISSING',
            'ROLE_PREPARATION_INCOMPLETE',
            'ROUTINE_REPEATEDLY_OVERDUE',
            'HANDOFF_INCOMPLETE'
        )
    ),
    CONSTRAINT chk_brief_continuity_signal_severity CHECK (
        source_severity IN ('CRITICAL', 'WARNING')
    ),
    CONSTRAINT chk_brief_continuity_signal_state CHECK (
        signal_state IN ('ACTIVE', 'RESOLVED')
    ),
    CONSTRAINT chk_brief_continuity_signal_revision CHECK (latest_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE brief_continuity_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BINARY(16) NOT NULL,
    signal_id BINARY(16) NOT NULL,
    workspace_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    event_version INT NOT NULL,
    source_severity VARCHAR(16) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    aggregate_revision BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    event_state VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_brief_continuity_outbox_event (event_id),
    UNIQUE KEY uk_brief_continuity_outbox_signal_revision (signal_id, aggregate_revision),
    CONSTRAINT chk_brief_continuity_outbox_type CHECK (
        event_type IN (
            'ROLE_UNASSIGNED',
            'ROLE_SUCCESSOR_MISSING',
            'ROLE_PREPARATION_INCOMPLETE',
            'ROUTINE_REPEATEDLY_OVERDUE',
            'HANDOFF_INCOMPLETE'
        )
    ),
    CONSTRAINT chk_brief_continuity_outbox_version CHECK (event_version = 2),
    CONSTRAINT chk_brief_continuity_outbox_severity CHECK (
        source_severity IN ('CRITICAL', 'WARNING')
    ),
    CONSTRAINT chk_brief_continuity_outbox_reference CHECK (
        source_reference LIKE 'baton-continuity:%'
    ),
    CONSTRAINT chk_brief_continuity_outbox_revision CHECK (aggregate_revision > 0),
    CONSTRAINT chk_brief_continuity_outbox_state CHECK (
        event_state IN ('ACTIVE', 'RESOLVED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
