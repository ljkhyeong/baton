ALTER TABLE seasons
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul' AFTER previous_season_id,
    ADD COLUMN round_schedule_first_meeting_date DATE NULL AFTER time_zone,
    ADD COLUMN round_schedule_meeting_time TIME(6) NULL AFTER round_schedule_first_meeting_date,
    ADD COLUMN round_schedule_recurrence VARCHAR(16) NULL AFTER round_schedule_meeting_time,
    ADD COLUMN round_schedule_generation_lead_days INT NULL AFTER round_schedule_recurrence,
    ADD COLUMN round_schedule_enabled BOOLEAN NULL AFTER round_schedule_generation_lead_days,
    ADD COLUMN round_schedule_next_occurrence_date DATE NULL AFTER round_schedule_enabled,
    ADD KEY idx_seasons_round_schedule_scan (round_schedule_enabled, ended_at, id),
    ADD CONSTRAINT chk_seasons_round_schedule_recurrence CHECK (
        round_schedule_recurrence IS NULL
        OR round_schedule_recurrence IN ('WEEKLY', 'BIWEEKLY')
    ),
    ADD CONSTRAINT chk_seasons_round_schedule_lead_days CHECK (
        round_schedule_generation_lead_days IS NULL
        OR round_schedule_generation_lead_days BETWEEN 0 AND 30
    ),
    ADD CONSTRAINT chk_seasons_round_schedule_enabled CHECK (
        round_schedule_enabled IS NULL
        OR round_schedule_enabled IN (FALSE, TRUE)
    ),
    ADD CONSTRAINT chk_seasons_round_schedule_complete CHECK (
        (
            round_schedule_first_meeting_date IS NULL
            AND round_schedule_meeting_time IS NULL
            AND round_schedule_recurrence IS NULL
            AND round_schedule_generation_lead_days IS NULL
            AND round_schedule_enabled IS NULL
            AND round_schedule_next_occurrence_date IS NULL
        )
        OR (
            round_schedule_first_meeting_date IS NOT NULL
            AND round_schedule_meeting_time IS NOT NULL
            AND round_schedule_recurrence IS NOT NULL
            AND round_schedule_generation_lead_days IS NOT NULL
            AND round_schedule_enabled IS NOT NULL
            AND round_schedule_next_occurrence_date IS NOT NULL
        )
    ),
    ADD CONSTRAINT chk_seasons_round_schedule_dates CHECK (
        round_schedule_first_meeting_date IS NULL
        OR (
            round_schedule_first_meeting_date BETWEEN start_date AND end_date
            AND round_schedule_next_occurrence_date >= round_schedule_first_meeting_date
        )
    );

ALTER TABLE routines
    ADD COLUMN deadline_day_offset INT NULL AFTER due_label,
    ADD COLUMN deadline_time TIME(6) NULL AFTER deadline_day_offset,
    ADD CONSTRAINT chk_routines_deadline_rule_complete CHECK (
        (deadline_day_offset IS NULL AND deadline_time IS NULL)
        OR (
            deadline_day_offset IS NOT NULL
            AND deadline_time IS NOT NULL
            AND deadline_day_offset BETWEEN -30 AND 30
        )
    );

ALTER TABLE season_rounds
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER meeting_date,
    ADD COLUMN scheduled_occurrence_date DATE NULL AFTER origin,
    ADD COLUMN scheduled_at DATETIME(6) NULL AFTER scheduled_occurrence_date,
    ADD UNIQUE KEY uk_season_rounds_season_occurrence (season_id, scheduled_occurrence_date),
    ADD CONSTRAINT chk_season_rounds_origin CHECK (
        origin IN ('MANUAL', 'AUTOMATIC')
    ),
    ADD CONSTRAINT chk_season_rounds_schedule_metadata CHECK (
        (
            origin = 'MANUAL'
            AND scheduled_occurrence_date IS NULL
            AND scheduled_at IS NULL
        )
        OR (
            origin = 'AUTOMATIC'
            AND scheduled_occurrence_date IS NOT NULL
            AND scheduled_at IS NOT NULL
        )
    );

ALTER TABLE routine_executions
    ADD COLUMN deadline_day_offset INT NULL AFTER due_label,
    ADD COLUMN deadline_time TIME(6) NULL AFTER deadline_day_offset,
    ADD COLUMN deadline_at DATETIME(6) NULL AFTER deadline_time,
    ADD CONSTRAINT chk_routine_executions_deadline_rule_complete CHECK (
        (deadline_day_offset IS NULL AND deadline_time IS NULL AND deadline_at IS NULL)
        OR (
            deadline_day_offset IS NOT NULL
            AND deadline_time IS NOT NULL
            AND deadline_at IS NOT NULL
            AND deadline_day_offset BETWEEN -30 AND 30
        )
    );
