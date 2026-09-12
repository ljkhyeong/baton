CREATE TABLE email_delivery_receipts (
    delivery_id BIGINT NOT NULL,
    event VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id, event),
    INDEX idx_email_delivery_receipts_occurred_at (occurred_at),
    CONSTRAINT fk_email_delivery_receipts_outbox FOREIGN KEY (delivery_id)
        REFERENCES email_verification_delivery_outbox (id) ON DELETE CASCADE,
    CONSTRAINT chk_email_delivery_receipts_event CHECK (event IN (
        'DELIVERED', 'SOFT_BOUNCE', 'HARD_BOUNCE', 'BLOCKED',
        'INVALID_EMAIL', 'ERROR', 'DEFERRED', 'SPAM'
    ))
);
