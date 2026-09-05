CREATE TABLE resource_verifications (
    id BINARY(16) NOT NULL PRIMARY KEY,
    resource_id BINARY(16) NOT NULL,
    resource_version BIGINT NOT NULL,
    account_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    member_name VARCHAR(100) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    status VARCHAR(20) NOT NULL,
    note VARCHAR(500) NULL,
    verified_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_resource_verifications_resource FOREIGN KEY (resource_id) REFERENCES role_resources(id),
    CONSTRAINT fk_resource_verifications_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_resource_verifications_member FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT chk_resource_verifications_status CHECK (status IN ('CONFIRMED', 'NEEDS_UPDATE')),
    INDEX ix_resource_verifications_history (resource_id, verified_at DESC, id DESC)
);
