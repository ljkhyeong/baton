ALTER TABLE handoff_items
    ADD COLUMN created_at DATETIME(6) NULL AFTER completed;

ALTER TABLE role_resources
    ADD COLUMN created_at DATETIME(6) NULL AFTER description;
