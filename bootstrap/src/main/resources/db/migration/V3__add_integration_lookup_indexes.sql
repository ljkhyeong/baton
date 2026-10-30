-- 운영 지표가 30초마다 읽는 마지막 전달 성공 시각을 전체 스캔 없이 찾는다.
ALTER TABLE `brief_continuity_outbox`
  ADD KEY `idx_brief_continuity_outbox_last_success` (`delivery_status`,`completed_at`);

ALTER TABLE `calendar_snapshot_outbox`
  ADD KEY `idx_calendar_snapshot_outbox_last_success` (`delivery_status`,`completed_at`);

ALTER TABLE `calendar_season_metadata_outbox`
  ADD KEY `idx_calendar_season_metadata_last_success` (`delivery_status`,`completed_at`);

ALTER TABLE `watch_monitor_outbox`
  ADD KEY `idx_watch_monitor_outbox_last_success` (`delivery_status`,`completed_at`);

ALTER TABLE `email_verification_delivery_outbox`
  ADD KEY `idx_email_verification_outbox_last_success` (`delivery_status`,`completed_at`);

-- 운영 지표의 마지막 접수 시각을 찾는다.
ALTER TABLE `watch_health_event_inbox`
  ADD KEY `idx_watch_health_event_inbox_accepted_at` (`accepted_at`);

-- 팀 기준 멤버십 조회는 기존 인덱스의 선두 열을 쓰지 못한다.
ALTER TABLE `account_team_memberships`
  ADD KEY `idx_account_team_memberships_team` (`team_id`,`member_id`);
