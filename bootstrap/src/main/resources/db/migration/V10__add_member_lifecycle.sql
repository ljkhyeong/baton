ALTER TABLE members
    ADD COLUMN deactivated_at DATETIME(6) NULL AFTER name,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER deactivated_at;
