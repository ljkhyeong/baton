CREATE TABLE watch_health_event_inbox (
    event_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    resource_reference VARCHAR(128) NOT NULL,
    source_revision BIGINT NOT NULL,
    attempt_id BINARY(16) NULL,
    previous_health VARCHAR(16) NOT NULL,
    current_health VARCHAR(16) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    changed_at_nano_remainder SMALLINT NOT NULL,
    payload_fingerprint BINARY(32) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_watch_health_event_inbox_resource_revision (
        resource_id,
        source_revision,
        changed_at,
        event_id
    ),
    CONSTRAINT chk_watch_health_event_inbox_type CHECK (
        event_type = 'RESOURCE_HEALTH_CHANGED'
    ),
    CONSTRAINT chk_watch_health_event_inbox_revision CHECK (source_revision >= 0),
    CONSTRAINT chk_watch_health_event_inbox_previous_health CHECK (
        previous_health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN')
    ),
    CONSTRAINT chk_watch_health_event_inbox_current_health CHECK (
        current_health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN')
    ),
    CONSTRAINT chk_watch_health_event_inbox_transition CHECK (
        previous_health <> current_health
    ),
    CONSTRAINT chk_watch_health_event_inbox_nano_remainder CHECK (
        changed_at_nano_remainder BETWEEN 0 AND 999
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
