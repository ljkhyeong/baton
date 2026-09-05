ALTER TABLE decisions
    ADD COLUMN text_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN_TEXT',
    ADD CONSTRAINT chk_decisions_text_format CHECK (text_format IN ('PLAIN_TEXT', 'MARKDOWN'));
