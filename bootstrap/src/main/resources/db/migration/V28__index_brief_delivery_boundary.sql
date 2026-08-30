ALTER TABLE brief_continuity_outbox
    ADD KEY idx_brief_continuity_outbox_delivery_boundary (
        workspace_id,
        season_id,
        id,
        delivery_status
    );
