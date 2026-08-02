ALTER TABLE content_creation_idempotency
    DROP CHECK chk_content_creation_idempotency_operation;

ALTER TABLE content_creation_idempotency
    ADD CONSTRAINT chk_content_creation_idempotency_operation CHECK (
        operation IN ('ROLE', 'ROUTINE', 'ROUND', 'DECISION', 'HANDOFF_ITEM')
    );

CREATE TABLE season_rounds (
    id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    meeting_date DATE NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_season_rounds_season_name (season_id, name),
    CONSTRAINT fk_season_rounds_season FOREIGN KEY (season_id) REFERENCES seasons (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE routine_executions (
    id BINARY(16) NOT NULL,
    season_round_id BINARY(16) NOT NULL,
    routine_id BINARY(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    due_label VARCHAR(100) NOT NULL,
    owner_role_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_routine_executions_round_routine (season_round_id, routine_id),
    KEY idx_routine_executions_routine_id (routine_id),
    KEY idx_routine_executions_owner_role_id (owner_role_id),
    CONSTRAINT fk_routine_executions_round FOREIGN KEY (season_round_id) REFERENCES season_rounds (id),
    CONSTRAINT fk_routine_executions_routine FOREIGN KEY (routine_id) REFERENCES routines (id),
    CONSTRAINT fk_routine_executions_owner_role FOREIGN KEY (owner_role_id) REFERENCES roles (id),
    CONSTRAINT chk_routine_executions_phase CHECK (phase IN ('BEFORE', 'DURING', 'AFTER')),
    CONSTRAINT chk_routine_executions_status CHECK (status IN ('WAITING', 'DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO season_rounds (id, season_id, name, meeting_date)
SELECT UUID_TO_BIN(UUID()), season.id, '회차 도입 이전 기록', NULL
FROM seasons season
WHERE EXISTS (
    SELECT 1
    FROM routines routine
    WHERE routine.season_id = season.id
);

INSERT INTO routine_executions (
    id,
    season_round_id,
    routine_id,
    title,
    phase,
    due_label,
    owner_role_id,
    status,
    detail,
    version
)
SELECT
    UUID_TO_BIN(UUID()),
    season_round.id,
    routine.id,
    routine.title,
    routine.phase,
    routine.due_label,
    routine.owner_role_id,
    routine.status,
    routine.detail,
    0
FROM routines routine
JOIN season_rounds season_round
    ON season_round.season_id = routine.season_id
    AND season_round.name = '회차 도입 이전 기록';

ALTER TABLE routines
    DROP CHECK chk_routines_status;

ALTER TABLE routines
    DROP COLUMN status;
