START TRANSACTION;

UPDATE teams
SET access_key_hash = LOWER(HEX(RANDOM_BYTES(32))),
    last_access_key_change_idempotency_hash = NULL,
    version = version + 1;

SELECT
    ROW_COUNT(),
    (SELECT COUNT(*) FROM teams),
    (
        SELECT COUNT(*)
        FROM teams
        WHERE access_key_hash NOT REGEXP '^[0-9a-f]{64}$'
    ),
    (
        SELECT COUNT(*)
        FROM teams
        WHERE last_access_key_change_idempotency_hash IS NOT NULL
    );

COMMIT;
