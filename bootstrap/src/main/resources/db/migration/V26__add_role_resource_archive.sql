ALTER TABLE role_resources
    ADD COLUMN archived_at DATETIME(6) NULL AFTER created_at;
