INSERT INTO accounts (
    id,
    display_name,
    created_at,
    updated_at,
    version
) VALUES (
    UUID_TO_BIN('10000000-0000-4000-8000-000000000099'),
    'ROUND 풀스택 계정',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    0
);

INSERT INTO account_identities (
    id,
    account_id,
    provider,
    provider_subject,
    email_snapshot,
    email_verified,
    created_at,
    last_authenticated_at,
    version
) VALUES (
    UUID_TO_BIN('11000000-0000-4000-8000-000000000099'),
    UUID_TO_BIN('10000000-0000-4000-8000-000000000099'),
    'LOCAL_EMAIL',
    'round.fullstack@example.test',
    'round.fullstack@example.test',
    TRUE,
    CURRENT_TIMESTAMP(6),
    NULL,
    0
);

INSERT INTO local_credentials (
    identity_id,
    password_hash,
    created_at,
    updated_at,
    version
) VALUES (
    UUID_TO_BIN('11000000-0000-4000-8000-000000000099'),
    '{pbkdf2@SpringSecurity_v5_8}a7348a65997e0183cc3f6192350d0f269f5ba481e205ea3169604a205dc09270660a407f61138069d0bb3131be8ee4d0',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    0
);
