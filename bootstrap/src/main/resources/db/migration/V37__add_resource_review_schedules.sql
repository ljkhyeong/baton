CREATE TABLE resource_review_schedules (
    resource_id BINARY(16) PRIMARY KEY,
    interval_days INT NULL,
    next_review_on DATE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_resource_review_schedule_resource FOREIGN KEY (resource_id) REFERENCES role_resources(id),
    CONSTRAINT chk_resource_review_schedule_interval CHECK (
        (interval_days IS NULL AND next_review_on IS NULL)
        OR (interval_days IS NOT NULL AND interval_days BETWEEN 1 AND 365 AND next_review_on IS NOT NULL)
    )
);
