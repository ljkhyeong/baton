CREATE TABLE calendar_subscriptions (
    account_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    season_id BINARY(16) NOT NULL,
    subscription_id BINARY(16) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revocation_pending BOOLEAN NOT NULL DEFAULT FALSE,
    operation_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    PRIMARY KEY (account_id, season_id),
    UNIQUE KEY uk_calendar_subscription_id (subscription_id),
    KEY idx_calendar_subscription_revocation (revocation_pending, lease_until),
    CONSTRAINT fk_calendar_subscription_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_calendar_subscription_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_calendar_subscription_season FOREIGN KEY (season_id) REFERENCES seasons (id)
);
