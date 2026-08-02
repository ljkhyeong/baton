ALTER TABLE routines
    ADD COLUMN archived_at DATETIME(6) NULL AFTER detail;
