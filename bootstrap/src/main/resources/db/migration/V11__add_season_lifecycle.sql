ALTER TABLE seasons
    ADD COLUMN ended_at DATETIME(6) NULL AFTER end_date,
    ADD COLUMN previous_season_id BINARY(16) NULL AFTER ended_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER previous_season_id;

UPDATE seasons season_record
JOIN (
    SELECT ranked_season.id
    FROM (
        SELECT
            season.id,
            ROW_NUMBER() OVER (
                PARTITION BY season.team_id
                ORDER BY season.start_date DESC, season.id DESC
            ) AS team_position
        FROM seasons season
    ) ranked_season
    WHERE ranked_season.team_position > 1
) ended_season
    ON ended_season.id = season_record.id
SET season_record.ended_at = UTC_TIMESTAMP(6);

ALTER TABLE seasons
    ADD COLUMN active_team_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN ended_at IS NULL THEN team_id ELSE NULL END
        ) STORED AFTER version,
    ADD UNIQUE KEY uk_seasons_team_name (team_id, name),
    ADD UNIQUE KEY uk_seasons_previous_season (previous_season_id),
    ADD UNIQUE KEY uk_seasons_active_team (active_team_id),
    ADD UNIQUE KEY uk_seasons_id_team (id, team_id);

ALTER TABLE seasons
    ADD CONSTRAINT fk_seasons_previous_season_team
        FOREIGN KEY (previous_season_id, team_id) REFERENCES seasons (id, team_id);

ALTER TABLE roles
    ADD COLUMN season_id BINARY(16) NULL AFTER team_id,
    ADD COLUMN previous_role_id BINARY(16) NULL AFTER season_id;

UPDATE roles role_record
JOIN teams team
    ON team.id = role_record.team_id
SET role_record.season_id = COALESCE(
    team.creation_season_id,
    (
        SELECT season.id
        FROM seasons season
        WHERE season.team_id = team.id
        ORDER BY season.start_date, season.id
        LIMIT 1
    )
);

ALTER TABLE roles
    DROP INDEX uk_roles_team_name,
    ADD KEY idx_roles_team_id (team_id);

CREATE TEMPORARY TABLE role_season_migration_map (
    source_role_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    target_role_id BINARY(16) NOT NULL,
    PRIMARY KEY (source_role_id, season_id),
    UNIQUE KEY uk_role_season_migration_target (target_role_id)
) ENGINE=InnoDB;

INSERT INTO role_season_migration_map (
    source_role_id,
    season_id,
    target_role_id
)
SELECT
    role_record.id,
    season.id,
    CASE
        WHEN role_record.season_id = season.id THEN role_record.id
        ELSE UUID_TO_BIN(UUID())
    END
FROM roles role_record
JOIN seasons season
    ON season.team_id = role_record.team_id;

INSERT INTO roles (
    id,
    team_id,
    season_id,
    previous_role_id,
    name,
    purpose,
    current_member_id,
    next_member_id,
    assignment_start_date,
    assignment_end_date,
    risk,
    version
)
SELECT
    migration_map.target_role_id,
    source_role.team_id,
    migration_map.season_id,
    source_role.id,
    source_role.name,
    source_role.purpose,
    source_role.current_member_id,
    source_role.next_member_id,
    source_role.assignment_start_date,
    source_role.assignment_end_date,
    source_role.risk,
    source_role.version
FROM role_season_migration_map migration_map
JOIN roles source_role
    ON source_role.id = migration_map.source_role_id
WHERE migration_map.target_role_id <> migration_map.source_role_id;

INSERT INTO role_responsibilities (
    role_id,
    sort_order,
    responsibility
)
SELECT
    migration_map.target_role_id,
    responsibility.sort_order,
    responsibility.responsibility
FROM role_season_migration_map migration_map
JOIN role_responsibilities responsibility
    ON responsibility.role_id = migration_map.source_role_id
WHERE migration_map.target_role_id <> migration_map.source_role_id;

UPDATE routines routine
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = routine.owner_role_id
    AND migration_map.season_id = routine.season_id
SET routine.owner_role_id = migration_map.target_role_id;

UPDATE routine_executions execution_record
JOIN season_rounds season_round
    ON season_round.id = execution_record.season_round_id
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = execution_record.owner_role_id
    AND migration_map.season_id = season_round.season_id
SET execution_record.owner_role_id = migration_map.target_role_id;

UPDATE decision_roles decision_role
JOIN decisions decision_record
    ON decision_record.id = decision_role.decision_id
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = decision_role.role_id
    AND migration_map.season_id = decision_record.season_id
SET decision_role.role_id = migration_map.target_role_id;

CREATE TEMPORARY TABLE handoff_season_migration_map (
    source_item_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    target_item_id BINARY(16) NOT NULL,
    target_role_id BINARY(16) NOT NULL,
    PRIMARY KEY (source_item_id, season_id),
    UNIQUE KEY uk_handoff_season_migration_target (target_item_id)
) ENGINE=InnoDB;

INSERT INTO handoff_season_migration_map (
    source_item_id,
    season_id,
    target_item_id,
    target_role_id
)
SELECT
    handoff_item.id,
    migration_map.season_id,
    CASE
        WHEN migration_map.target_role_id = migration_map.source_role_id
            THEN handoff_item.id
        ELSE UUID_TO_BIN(UUID())
    END,
    migration_map.target_role_id
FROM handoff_items handoff_item
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = handoff_item.role_id;

INSERT INTO handoff_items (
    id,
    role_id,
    label,
    category,
    completed,
    version,
    archived_at
)
SELECT
    migration_map.target_item_id,
    migration_map.target_role_id,
    source_item.label,
    source_item.category,
    source_item.completed,
    source_item.version,
    source_item.archived_at
FROM handoff_season_migration_map migration_map
JOIN handoff_items source_item
    ON source_item.id = migration_map.source_item_id
WHERE migration_map.target_item_id <> migration_map.source_item_id;

CREATE TEMPORARY TABLE resource_season_migration_map (
    source_resource_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    target_resource_id BINARY(16) NOT NULL,
    target_role_id BINARY(16) NOT NULL,
    PRIMARY KEY (source_resource_id, season_id),
    UNIQUE KEY uk_resource_season_migration_target (target_resource_id)
) ENGINE=InnoDB;

INSERT INTO resource_season_migration_map (
    source_resource_id,
    season_id,
    target_resource_id,
    target_role_id
)
SELECT
    role_resource.id,
    migration_map.season_id,
    CASE
        WHEN migration_map.target_role_id = migration_map.source_role_id
            THEN role_resource.id
        ELSE UUID_TO_BIN(UUID())
    END,
    migration_map.target_role_id
FROM role_resources role_resource
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = role_resource.role_id;

INSERT INTO role_resources (
    id,
    role_id,
    title,
    url,
    description,
    version
)
SELECT
    migration_map.target_resource_id,
    migration_map.target_role_id,
    source_resource.title,
    source_resource.url,
    source_resource.description,
    source_resource.version
FROM resource_season_migration_map migration_map
JOIN role_resources source_resource
    ON source_resource.id = migration_map.source_resource_id
WHERE migration_map.target_resource_id <> migration_map.source_resource_id;

UPDATE content_creation_idempotency idempotency
JOIN role_season_migration_map migration_map
    ON migration_map.source_role_id = idempotency.resource_id
    AND migration_map.season_id = idempotency.season_id
SET idempotency.resource_id = migration_map.target_role_id
WHERE idempotency.operation = 'ROLE';

UPDATE content_creation_idempotency idempotency
JOIN handoff_season_migration_map migration_map
    ON migration_map.source_item_id = idempotency.resource_id
    AND migration_map.season_id = idempotency.season_id
SET idempotency.resource_id = migration_map.target_item_id
WHERE idempotency.operation = 'HANDOFF_ITEM';

UPDATE content_creation_idempotency idempotency
JOIN resource_season_migration_map migration_map
    ON migration_map.source_resource_id = idempotency.resource_id
    AND migration_map.season_id = idempotency.season_id
SET idempotency.resource_id = migration_map.target_resource_id
WHERE idempotency.operation = 'ROLE_RESOURCE';

ALTER TABLE roles
    MODIFY COLUMN season_id BINARY(16) NOT NULL,
    ADD UNIQUE KEY uk_roles_season_name (season_id, name),
    ADD UNIQUE KEY uk_roles_season_previous_role (season_id, previous_role_id),
    ADD UNIQUE KEY uk_roles_id_season (id, season_id),
    ADD KEY idx_roles_previous_role_id (previous_role_id),
    ADD CONSTRAINT fk_roles_season_team
        FOREIGN KEY (season_id, team_id) REFERENCES seasons (id, team_id),
    ADD CONSTRAINT fk_roles_previous_role
        FOREIGN KEY (previous_role_id) REFERENCES roles (id);

ALTER TABLE routines
    ADD COLUMN previous_routine_id BINARY(16) NULL AFTER season_id,
    DROP FOREIGN KEY fk_routines_owner_role,
    ADD UNIQUE KEY uk_routines_season_previous_routine (season_id, previous_routine_id),
    ADD KEY idx_routines_previous_routine_id (previous_routine_id),
    ADD CONSTRAINT fk_routines_previous_routine
        FOREIGN KEY (previous_routine_id) REFERENCES routines (id),
    ADD CONSTRAINT fk_routines_owner_role_season
        FOREIGN KEY (owner_role_id, season_id) REFERENCES roles (id, season_id);

ALTER TABLE content_creation_idempotency
    DROP CHECK chk_content_creation_idempotency_operation;

ALTER TABLE content_creation_idempotency
    ADD CONSTRAINT chk_content_creation_idempotency_operation CHECK (
        operation IN (
            'MEMBER',
            'SEASON',
            'ROLE',
            'ROUTINE',
            'ROUND',
            'DECISION',
            'HANDOFF_ITEM',
            'ROLE_RESOURCE'
        )
    );

DROP TEMPORARY TABLE resource_season_migration_map;
DROP TEMPORARY TABLE handoff_season_migration_map;
DROP TEMPORARY TABLE role_season_migration_map;
