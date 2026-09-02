ALTER TABLE accounts
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE email_verification_challenges
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'REGISTRATION';
