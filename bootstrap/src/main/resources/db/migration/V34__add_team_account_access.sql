ALTER TABLE teams ADD COLUMN account_access_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account_team_memberships ADD COLUMN permission VARCHAR(16) NULL,
    ADD CONSTRAINT chk_membership_permission CHECK (permission IN ('ADMIN', 'MEMBER', 'VIEWER'));
CREATE TABLE team_invitations (
    id BINARY(16) NOT NULL PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    permission VARCHAR(16) NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    accepted_by BINARY(16) NULL,
    revoked_at DATETIME(6) NULL,
    UNIQUE KEY uk_team_invitation_token (token_hash),
    INDEX ix_team_invitation_history (team_id, created_at DESC),
    CONSTRAINT fk_team_invitation_team FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT fk_team_invitation_member FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT fk_team_invitation_creator FOREIGN KEY (created_by) REFERENCES accounts(id),
    CONSTRAINT fk_team_invitation_acceptor FOREIGN KEY (accepted_by) REFERENCES accounts(id),
    CONSTRAINT chk_team_invitation_permission CHECK (permission IN ('ADMIN', 'MEMBER', 'VIEWER'))
);
CREATE TABLE team_access_audit (
    id BINARY(16) NOT NULL PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    actor_account_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    action VARCHAR(30) NOT NULL,
    previous_permission VARCHAR(16) NULL,
    permission VARCHAR(16) NULL,
    changed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_team_access_audit_team FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT fk_team_access_audit_actor FOREIGN KEY (actor_account_id) REFERENCES accounts(id),
    CONSTRAINT fk_team_access_audit_member FOREIGN KEY (member_id) REFERENCES members(id),
    INDEX ix_team_access_audit_history (team_id, changed_at DESC, id DESC)
);
