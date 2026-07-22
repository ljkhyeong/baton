ALTER TABLE content_creation_idempotency
    DROP CHECK chk_content_creation_idempotency_operation;

ALTER TABLE content_creation_idempotency
    ADD CONSTRAINT chk_content_creation_idempotency_operation CHECK (
        operation IN ('ROLE', 'ROUTINE', 'ROUND', 'DECISION', 'HANDOFF_ITEM', 'ROLE_RESOURCE')
    );

CREATE TABLE role_resources (
    id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    description VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_role_resources_role_id (role_id),
    CONSTRAINT fk_role_resources_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
