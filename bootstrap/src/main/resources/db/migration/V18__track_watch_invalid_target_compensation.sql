ALTER TABLE watch_monitor_outbox
    ADD COLUMN compensation_for_id BIGINT NULL AFTER target_url,
    ADD UNIQUE KEY uk_watch_monitor_outbox_compensation_source (compensation_for_id),
    ADD CONSTRAINT fk_watch_monitor_outbox_compensation_source
        FOREIGN KEY (compensation_for_id) REFERENCES watch_monitor_outbox (id),
    ADD CONSTRAINT chk_watch_monitor_outbox_compensation CHECK (
        compensation_for_id IS NULL
        OR (monitoring_state = 'INACTIVE' AND target_url IS NULL)
    );
