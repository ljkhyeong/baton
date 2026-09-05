CREATE TABLE notification_preferences (
    account_id BINARY(16) PRIMARY KEY,
    deadline_soon_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    overdue_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    handoff_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deadline_lead_hours INT NOT NULL DEFAULT 24,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_notification_preferences_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_notification_preferences_lead CHECK (deadline_lead_hours BETWEEN 1 AND 168)
);
