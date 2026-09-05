CREATE TABLE notification_read_receipts (
    account_id BINARY(16) NOT NULL,
    notification_id BINARY(16) NOT NULL,
    read_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, notification_id),
    CONSTRAINT fk_notification_read_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);
