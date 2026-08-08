CREATE TABLE account_team_memberships (
    id BINARY(16) NOT NULL,
    account_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_team_memberships_account_team (account_id, team_id),
    UNIQUE KEY uk_account_team_memberships_member (member_id),
    KEY idx_account_team_memberships_member_team (member_id, team_id),
    CONSTRAINT fk_account_team_memberships_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_account_team_memberships_member_team
        FOREIGN KEY (member_id, team_id) REFERENCES members (id, team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE round_room_tombstones (
    room_id VARCHAR(14)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    created_at DATETIME(6) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    ended_at DATETIME(6) NULL,
    PRIMARY KEY (room_id),
    UNIQUE KEY uk_round_room_tombstones_snapshot (
        room_id,
        team_id,
        season_id,
        resource_id
    ),
    CONSTRAINT chk_round_room_tombstones_room_id CHECK (
        room_id REGEXP '^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$'
    ),
    CONSTRAINT chk_round_room_tombstones_timeline CHECK (
        ended_at IS NULL OR ended_at >= created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE round_room_mappings (
    id BINARY(16) NOT NULL,
    room_id VARCHAR(14)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_round_room_mappings_room (room_id),
    UNIQUE KEY uk_round_room_mappings_resource (resource_id),
    KEY idx_round_room_mappings_season_team (season_id, team_id),
    CONSTRAINT fk_round_room_mappings_tombstone_snapshot
        FOREIGN KEY (room_id, team_id, season_id, resource_id)
        REFERENCES round_room_tombstones (room_id, team_id, season_id, resource_id),
    CONSTRAINT fk_round_room_mappings_season_team
        FOREIGN KEY (season_id, team_id) REFERENCES seasons (id, team_id),
    CONSTRAINT fk_round_room_mappings_resource
        FOREIGN KEY (resource_id) REFERENCES role_resources (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
