ALTER TABLE members
    MODIFY COLUMN name VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_bin
        NOT NULL,
    ADD CONSTRAINT uk_members_team_name UNIQUE (team_id, name);

ALTER TABLE content_creation_idempotency
    DROP CHECK chk_content_creation_idempotency_operation;

ALTER TABLE content_creation_idempotency
    ADD CONSTRAINT chk_content_creation_idempotency_operation CHECK (
        operation IN (
            'MEMBER',
            'ROLE',
            'ROUTINE',
            'ROUND',
            'DECISION',
            'HANDOFF_ITEM',
            'ROLE_RESOURCE'
        )
    );
