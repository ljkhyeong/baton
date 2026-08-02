CREATE TABLE teams (
    id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    access_key_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seasons (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    PRIMARY KEY (id),
    KEY idx_seasons_team_id (team_id),
    CONSTRAINT fk_seasons_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT chk_seasons_date_range CHECK (start_date <= end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE members (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_members_team_id (team_id),
    CONSTRAINT fk_members_team FOREIGN KEY (team_id) REFERENCES teams (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE roles (
    id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    current_member_id BINARY(16) NULL,
    next_member_id BINARY(16) NULL,
    assignment_start_date DATE NULL,
    assignment_end_date DATE NULL,
    risk VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_team_name (team_id, name),
    KEY idx_roles_current_member_id (current_member_id),
    KEY idx_roles_next_member_id (next_member_id),
    CONSTRAINT fk_roles_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_roles_current_member FOREIGN KEY (current_member_id) REFERENCES members (id),
    CONSTRAINT fk_roles_next_member FOREIGN KEY (next_member_id) REFERENCES members (id),
    CONSTRAINT chk_roles_assignment_range CHECK (
        assignment_start_date IS NULL OR assignment_end_date IS NULL OR assignment_start_date <= assignment_end_date
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role_responsibilities (
    role_id BINARY(16) NOT NULL,
    sort_order INT NOT NULL,
    responsibility VARCHAR(500) NOT NULL,
    PRIMARY KEY (role_id, sort_order),
    CONSTRAINT fk_role_responsibilities_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE routines (
    id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    due_label VARCHAR(100) NOT NULL,
    owner_role_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_routines_season_id (season_id),
    KEY idx_routines_owner_role_id (owner_role_id),
    CONSTRAINT fk_routines_season FOREIGN KEY (season_id) REFERENCES seasons (id),
    CONSTRAINT fk_routines_owner_role FOREIGN KEY (owner_role_id) REFERENCES roles (id),
    CONSTRAINT chk_routines_phase CHECK (phase IN ('BEFORE', 'DURING', 'AFTER')),
    CONSTRAINT chk_routines_status CHECK (status IN ('WAITING', 'DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE decisions (
    id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    alternative VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    author_member_id BINARY(16) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_decisions_season_created_at (season_id, created_at),
    KEY idx_decisions_author_member_id (author_member_id),
    CONSTRAINT fk_decisions_season FOREIGN KEY (season_id) REFERENCES seasons (id),
    CONSTRAINT fk_decisions_author_member FOREIGN KEY (author_member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE decision_roles (
    decision_id BINARY(16) NOT NULL,
    sort_order INT NOT NULL,
    role_id BINARY(16) NOT NULL,
    PRIMARY KEY (decision_id, sort_order),
    UNIQUE KEY uk_decision_roles_decision_role (decision_id, role_id),
    KEY idx_decision_roles_role_id (role_id),
    CONSTRAINT fk_decision_roles_decision FOREIGN KEY (decision_id) REFERENCES decisions (id),
    CONSTRAINT fk_decision_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE handoff_items (
    id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    label VARCHAR(500) NOT NULL,
    category VARCHAR(24) NOT NULL,
    completed BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    KEY idx_handoff_items_role_id (role_id),
    CONSTRAINT fk_handoff_items_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT chk_handoff_items_category CHECK (
        category IN ('RESPONSIBILITY', 'ROUTINE', 'RESOURCE', 'ADVICE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
