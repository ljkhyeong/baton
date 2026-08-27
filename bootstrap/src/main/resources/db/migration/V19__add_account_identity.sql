CREATE TABLE accounts (
    id BINARY(16) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT chk_accounts_updated_at CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_identities (
    id BINARY(16) NOT NULL,
    account_id BINARY(16) NOT NULL,
    provider VARCHAR(20)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    provider_subject VARCHAR(320)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_bin
        NOT NULL,
    email_snapshot VARCHAR(320)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_bin
        NULL,
    email_verified BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_authenticated_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_identities_provider_subject (provider, provider_subject),
    UNIQUE KEY uk_account_identities_account_provider (account_id, provider),
    KEY idx_account_identities_account (account_id),
    CONSTRAINT fk_account_identities_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_account_identities_provider CHECK (
        provider IN ('GOOGLE', 'NAVER', 'LOCAL_EMAIL')
    ),
    CONSTRAINT chk_account_identities_email_verified CHECK (
        email_verified IN (FALSE, TRUE)
        AND (email_snapshot IS NOT NULL OR email_verified = FALSE)
    ),
    CONSTRAINT chk_account_identities_local_email CHECK (
        provider <> 'LOCAL_EMAIL'
        OR (
            email_snapshot = provider_subject
            AND provider_subject = LOWER(provider_subject)
        )
    ),
    CONSTRAINT chk_account_identities_authentication_time CHECK (
        last_authenticated_at IS NULL OR last_authenticated_at >= created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE local_credentials (
    identity_id BINARY(16) NOT NULL,
    password_hash VARCHAR(255)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_bin
        NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (identity_id),
    CONSTRAINT fk_local_credentials_identity
        FOREIGN KEY (identity_id) REFERENCES account_identities (id),
    CONSTRAINT chk_local_credentials_password_hash CHECK (
        CHAR_LENGTH(password_hash) > 0
    ),
    CONSTRAINT chk_local_credentials_updated_at CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_verification_challenges (
    id BINARY(16) NOT NULL,
    identity_id BINARY(16) NOT NULL,
    token_hash CHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_verification_challenges_identity (identity_id),
    UNIQUE KEY uk_email_verification_challenges_token_hash (token_hash),
    CONSTRAINT fk_email_verification_challenges_identity
        FOREIGN KEY (identity_id) REFERENCES account_identities (id),
    CONSTRAINT chk_email_verification_challenges_token_hash CHECK (
        token_hash REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_email_verification_challenges_expiry CHECK (
        expires_at > created_at
    ),
    CONSTRAINT chk_email_verification_challenges_consumed_at CHECK (
        consumed_at IS NULL OR consumed_at >= created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
