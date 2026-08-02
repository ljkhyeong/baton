ALTER TABLE members
    ADD UNIQUE KEY uk_members_id_team (id, team_id);

CREATE TABLE role_handoffs (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    from_member_id BINARY(16) NOT NULL,
    to_member_id BINARY(16) NOT NULL,
    outgoing_assignment_start_date DATE NULL,
    outgoing_assignment_end_date DATE NULL,
    incoming_assignment_start_date DATE NOT NULL,
    incoming_assignment_end_date DATE NULL,
    status VARCHAR(16) NOT NULL,
    prepared_at DATETIME(6) NOT NULL,
    transferred_at DATETIME(6) NULL,
    accepted_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    transferred_by_member_id BINARY(16) NULL,
    accepted_by_member_id BINARY(16) NULL,
    cancelled_by_member_id BINARY(16) NULL,
    snapshot_item_count INT NULL,
    snapshot_incomplete_item_count INT NULL,
    snapshot_resource_count INT NULL,
    warning_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    active_role_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('PREPARING', 'TRANSFERRED') THEN role_id
                ELSE NULL
            END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_handoffs_active_role (active_role_id),
    KEY idx_role_handoffs_role_prepared (role_id, prepared_at DESC, id),
    KEY idx_role_handoffs_season_status (season_id, status, id),
    KEY idx_role_handoffs_from_member_team (from_member_id, team_id),
    KEY idx_role_handoffs_to_member_team (to_member_id, team_id),
    KEY idx_role_handoffs_transferred_by_team (transferred_by_member_id, team_id),
    KEY idx_role_handoffs_accepted_by_team (accepted_by_member_id, team_id),
    KEY idx_role_handoffs_cancelled_by_team (cancelled_by_member_id, team_id),
    CONSTRAINT fk_role_handoffs_team
        FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_role_handoffs_season_team
        FOREIGN KEY (season_id, team_id) REFERENCES seasons (id, team_id),
    CONSTRAINT fk_role_handoffs_role_season
        FOREIGN KEY (role_id, season_id) REFERENCES roles (id, season_id),
    CONSTRAINT fk_role_handoffs_from_member_team
        FOREIGN KEY (from_member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_role_handoffs_to_member_team
        FOREIGN KEY (to_member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_role_handoffs_transferred_by_member_team
        FOREIGN KEY (transferred_by_member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_role_handoffs_accepted_by_member_team
        FOREIGN KEY (accepted_by_member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT fk_role_handoffs_cancelled_by_member_team
        FOREIGN KEY (cancelled_by_member_id, team_id) REFERENCES members (id, team_id),
    CONSTRAINT chk_role_handoffs_distinct_members CHECK (
        from_member_id <> to_member_id
    ),
    CONSTRAINT chk_role_handoffs_outgoing_assignment_range CHECK (
        outgoing_assignment_start_date IS NULL
        OR outgoing_assignment_end_date IS NULL
        OR outgoing_assignment_start_date <= outgoing_assignment_end_date
    ),
    CONSTRAINT chk_role_handoffs_incoming_assignment_range CHECK (
        incoming_assignment_end_date IS NULL
        OR incoming_assignment_start_date <= incoming_assignment_end_date
    ),
    CONSTRAINT chk_role_handoffs_snapshot_counts CHECK (
        (
            snapshot_item_count IS NULL
            AND snapshot_incomplete_item_count IS NULL
            AND snapshot_resource_count IS NULL
        )
        OR (
            snapshot_item_count >= 0
            AND snapshot_incomplete_item_count BETWEEN 0 AND snapshot_item_count
            AND snapshot_resource_count >= 0
        )
    ),
    CONSTRAINT chk_role_handoffs_status CHECK (
        status IN ('PREPARING', 'TRANSFERRED', 'ACCEPTED', 'CANCELLED')
    ),
    CONSTRAINT chk_role_handoffs_lifecycle CHECK (
        (
            status = 'PREPARING'
            AND transferred_at IS NULL
            AND accepted_at IS NULL
            AND cancelled_at IS NULL
            AND transferred_by_member_id IS NULL
            AND accepted_by_member_id IS NULL
            AND cancelled_by_member_id IS NULL
            AND snapshot_item_count IS NULL
            AND snapshot_incomplete_item_count IS NULL
            AND snapshot_resource_count IS NULL
            AND warning_acknowledged = FALSE
        )
        OR (
            status = 'TRANSFERRED'
            AND transferred_at IS NOT NULL
            AND accepted_at IS NULL
            AND cancelled_at IS NULL
            AND transferred_by_member_id = from_member_id
            AND accepted_by_member_id IS NULL
            AND cancelled_by_member_id IS NULL
            AND snapshot_item_count IS NOT NULL
            AND snapshot_incomplete_item_count IS NOT NULL
            AND snapshot_resource_count IS NOT NULL
        )
        OR (
            status = 'ACCEPTED'
            AND transferred_at IS NOT NULL
            AND accepted_at IS NOT NULL
            AND cancelled_at IS NULL
            AND transferred_by_member_id = from_member_id
            AND accepted_by_member_id = to_member_id
            AND cancelled_by_member_id IS NULL
            AND snapshot_item_count IS NOT NULL
            AND snapshot_incomplete_item_count IS NOT NULL
            AND snapshot_resource_count IS NOT NULL
        )
        OR (
            status = 'CANCELLED'
            AND accepted_at IS NULL
            AND cancelled_at IS NOT NULL
            AND accepted_by_member_id IS NULL
            AND cancelled_by_member_id = from_member_id
            AND (
                (
                    transferred_at IS NULL
                    AND transferred_by_member_id IS NULL
                    AND snapshot_item_count IS NULL
                    AND snapshot_incomplete_item_count IS NULL
                    AND snapshot_resource_count IS NULL
                    AND warning_acknowledged = FALSE
                )
                OR (
                    transferred_at IS NOT NULL
                    AND transferred_by_member_id = from_member_id
                    AND snapshot_item_count IS NOT NULL
                    AND snapshot_incomplete_item_count IS NOT NULL
                    AND snapshot_resource_count IS NOT NULL
                )
            )
        )
    ),
    CONSTRAINT chk_role_handoffs_timeline CHECK (
        (transferred_at IS NULL OR transferred_at >= prepared_at)
        AND (accepted_at IS NULL OR accepted_at >= transferred_at)
        AND (cancelled_at IS NULL OR cancelled_at >= COALESCE(transferred_at, prepared_at))
    ),
    CONSTRAINT chk_role_handoffs_warning_acknowledgement CHECK (
        transferred_at IS NULL
        OR (
            (
                snapshot_item_count > 0
                AND snapshot_incomplete_item_count = 0
                AND snapshot_resource_count > 0
            )
            OR warning_acknowledged = TRUE
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            'ROLE_RESOURCE',
            'ROLE_HANDOFF'
        )
    );
