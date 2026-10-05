-- 출시 전 V1~V40 마이그레이션을 합친 기준 스키마. 보존할 운영 데이터가 없는 상태에서 만들었다.
-- 이후 스키마 변경은 V2부터 새 마이그레이션으로 추가한다.
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `access_key_change_history` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `idempotency_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_key_change_history_team_hash` (`team_id`,`idempotency_hash`),
  CONSTRAINT `fk_access_key_change_history_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `account_identities` (
  `id` binary(16) NOT NULL,
  `account_id` binary(16) NOT NULL,
  `provider` varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `provider_subject` varchar(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `email_snapshot` varchar(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `email_verified` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_authenticated_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_identities_provider_subject` (`provider`,`provider_subject`),
  UNIQUE KEY `uk_account_identities_account_provider` (`account_id`,`provider`),
  KEY `idx_account_identities_account` (`account_id`),
  CONSTRAINT `fk_account_identities_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `chk_account_identities_authentication_time` CHECK (((`last_authenticated_at` is null) or (`last_authenticated_at` >= `created_at`))),
  CONSTRAINT `chk_account_identities_email_verified` CHECK (((`email_verified` in (false,true)) and ((`email_snapshot` is not null) or (`email_verified` = false)))),
  CONSTRAINT `chk_account_identities_local_email` CHECK (((`provider` <> _latin1'LOCAL_EMAIL') or ((`email_snapshot` = `provider_subject`) and (`provider_subject` = lower(`provider_subject`))))),
  CONSTRAINT `chk_account_identities_provider` CHECK ((`provider` in (_latin1'GOOGLE',_latin1'NAVER',_latin1'LOCAL_EMAIL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `account_team_memberships` (
  `id` binary(16) NOT NULL,
  `account_id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `member_id` binary(16) NOT NULL,
  `claimed_at` datetime(6) NOT NULL,
  `permission` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_team_memberships_account_team` (`account_id`,`team_id`),
  UNIQUE KEY `uk_account_team_memberships_member` (`member_id`),
  KEY `idx_account_team_memberships_member_team` (`member_id`,`team_id`),
  CONSTRAINT `fk_account_team_memberships_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_account_team_memberships_member_team` FOREIGN KEY (`member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `chk_membership_permission` CHECK ((`permission` in (_latin1'ADMIN',_latin1'MEMBER',_latin1'VIEWER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `accounts` (
  `id` binary(16) NOT NULL,
  `display_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `session_version` bigint NOT NULL DEFAULT '0',
  `deactivated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_accounts_updated_at` CHECK ((`updated_at` >= `created_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `brief_continuity_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` binary(16) NOT NULL,
  `signal_id` binary(16) NOT NULL,
  `workspace_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `event_type` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_version` int NOT NULL,
  `source_severity` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_reference` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_revision` bigint NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `event_state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `delivery_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `available_at` datetime(6) NOT NULL,
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `result_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brief_continuity_outbox_event` (`event_id`),
  UNIQUE KEY `uk_brief_continuity_outbox_signal_revision` (`signal_id`,`aggregate_revision`),
  KEY `idx_brief_continuity_outbox_pending_claim` (`delivery_status`,`available_at`,`id`),
  KEY `idx_brief_continuity_outbox_expired_claim` (`delivery_status`,`lease_expires_at`,`id`),
  KEY `idx_brief_continuity_outbox_delivery_boundary` (`workspace_id`,`season_id`,`id`,`delivery_status`),
  CONSTRAINT `chk_brief_continuity_outbox_attempt_count` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_brief_continuity_outbox_completion` CHECK ((((`delivery_status` in (_utf8mb4'DELIVERED',_utf8mb4'FAILED')) and (`completed_at` is not null)) or ((`delivery_status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING')) and (`completed_at` is null)))),
  CONSTRAINT `chk_brief_continuity_outbox_delivery_status` CHECK ((`delivery_status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'DELIVERED',_utf8mb4'FAILED'))),
  CONSTRAINT `chk_brief_continuity_outbox_lease` CHECK ((((`delivery_status` = _utf8mb4'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null)) or ((`delivery_status` <> _utf8mb4'PROCESSING') and (`lease_token` is null) and (`lease_expires_at` is null)))),
  CONSTRAINT `chk_brief_continuity_outbox_reference` CHECK ((`source_reference` like _utf8mb4'baton-continuity:%')),
  CONSTRAINT `chk_brief_continuity_outbox_revision` CHECK ((`aggregate_revision` > 0)),
  CONSTRAINT `chk_brief_continuity_outbox_severity` CHECK ((`source_severity` in (_utf8mb4'CRITICAL',_utf8mb4'WARNING'))),
  CONSTRAINT `chk_brief_continuity_outbox_state` CHECK ((`event_state` in (_utf8mb4'ACTIVE',_utf8mb4'RESOLVED'))),
  CONSTRAINT `chk_brief_continuity_outbox_type` CHECK ((`event_type` in (_utf8mb4'ROLE_UNASSIGNED',_utf8mb4'ROLE_SUCCESSOR_MISSING',_utf8mb4'ROLE_PREPARATION_INCOMPLETE',_utf8mb4'ROUTINE_REPEATEDLY_OVERDUE',_utf8mb4'HANDOFF_INCOMPLETE'))),
  CONSTRAINT `chk_brief_continuity_outbox_version` CHECK ((`event_version` = 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `brief_continuity_scope` (
  `season_id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  PRIMARY KEY (`season_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `brief_continuity_signal` (
  `signal_id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `signal_type` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` binary(16) NOT NULL,
  `source_severity` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `signal_state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `latest_revision` bigint NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`signal_id`),
  UNIQUE KEY `uk_brief_continuity_signal_identity` (`season_id`,`signal_type`,`subject_id`),
  CONSTRAINT `chk_brief_continuity_signal_revision` CHECK ((`latest_revision` > 0)),
  CONSTRAINT `chk_brief_continuity_signal_severity` CHECK ((`source_severity` in (_latin1'CRITICAL',_latin1'WARNING'))),
  CONSTRAINT `chk_brief_continuity_signal_state` CHECK ((`signal_state` in (_latin1'ACTIVE',_latin1'RESOLVED'))),
  CONSTRAINT `chk_brief_continuity_signal_type` CHECK ((`signal_type` in (_latin1'ROLE_UNASSIGNED',_latin1'ROLE_SUCCESSOR_MISSING',_latin1'ROLE_PREPARATION_INCOMPLETE',_latin1'ROUTINE_REPEATEDLY_OVERDUE',_latin1'HANDOFF_INCOMPLETE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `brief_edition_generation_execution` (
  `execution_id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `week_start` date NOT NULL,
  `zone_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `delivery_watermark` bigint NOT NULL,
  `execution_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `edition_id` binary(16) DEFAULT NULL,
  `edition_generation` bigint DEFAULT NULL,
  `source_cursor` bigint DEFAULT NULL,
  `edition_etag` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_new` tinyint(1) DEFAULT NULL,
  `result_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`execution_id`),
  UNIQUE KEY `uk_brief_edition_generation_boundary` (`team_id`,`season_id`,`week_start`,`zone_id`,`delivery_watermark`),
  KEY `idx_brief_edition_generation_lease` (`execution_status`,`lease_expires_at`),
  KEY `fk_brief_edition_generation_season` (`season_id`),
  CONSTRAINT `fk_brief_edition_generation_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `fk_brief_edition_generation_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_brief_edition_generation_attempt` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_brief_edition_generation_lease` CHECK ((((`execution_status` = _latin1'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null)) or ((`execution_status` <> _latin1'PROCESSING') and (`lease_token` is null) and (`lease_expires_at` is null)))),
  CONSTRAINT `chk_brief_edition_generation_result` CHECK ((((`execution_status` = _latin1'SUCCEEDED') and (`edition_id` is not null) and (`edition_generation` is not null) and (`source_cursor` is not null) and (`edition_etag` is not null) and (`created_new` is not null)) or (`execution_status` <> _latin1'SUCCEEDED'))),
  CONSTRAINT `chk_brief_edition_generation_status` CHECK ((`execution_status` in (_latin1'PENDING',_latin1'PROCESSING',_latin1'SUCCEEDED',_latin1'RETRYABLE_FAILURE',_latin1'PERMANENT_FAILURE'))),
  CONSTRAINT `chk_brief_edition_generation_watermark` CHECK ((`delivery_watermark` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `calendar_season_metadata_outbox` (
  `id` int NOT NULL AUTO_INCREMENT,
  `season_id` binary(16) NOT NULL,
  `display_name` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `delivery_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `available_at` datetime(6) NOT NULL,
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `result_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_calendar_season_metadata_revision` (`season_id`,`id`),
  KEY `idx_calendar_season_metadata_pending` (`delivery_status`,`available_at`,`id`),
  CONSTRAINT `chk_calendar_season_metadata_attempt_count` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_calendar_season_metadata_lifecycle` CHECK ((((`delivery_status` = _latin1'PENDING') and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is null)) or ((`delivery_status` = _latin1'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null) and (`completed_at` is null)) or ((`delivery_status` in (_latin1'DELIVERED',_latin1'FAILED')) and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `calendar_snapshot_outbox` (
  `id` int NOT NULL AUTO_INCREMENT,
  `event_id` binary(16) NOT NULL,
  `source_item_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `calendar_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `location` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `time_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `at_instant` datetime(6) DEFAULT NULL,
  `at_local` datetime(6) DEFAULT NULL,
  `zone_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `source_updated_at` datetime(6) NOT NULL,
  `delivery_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `available_at` datetime(6) NOT NULL,
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `result_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_calendar_snapshot_outbox_event` (`event_id`),
  KEY `idx_calendar_snapshot_outbox_source_revision` (`source_item_id`,`id`),
  KEY `idx_calendar_snapshot_outbox_pending` (`delivery_status`,`available_at`,`id`),
  CONSTRAINT `chk_calendar_snapshot_outbox_attempt_count` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_calendar_snapshot_outbox_delivery_lifecycle` CHECK ((((`delivery_status` = _latin1'PENDING') and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is null)) or ((`delivery_status` = _latin1'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null) and (`completed_at` is null)) or ((`delivery_status` in (_latin1'DELIVERED',_latin1'FAILED')) and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is not null)))),
  CONSTRAINT `chk_calendar_snapshot_outbox_delivery_status` CHECK ((`delivery_status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'DELIVERED',_utf8mb4'FAILED'))),
  CONSTRAINT `chk_calendar_snapshot_outbox_source_time` CHECK ((`source_updated_at` <= `occurred_at`)),
  CONSTRAINT `chk_calendar_snapshot_outbox_status` CHECK ((`calendar_status` in (_utf8mb4'ACTIVE',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_calendar_snapshot_outbox_time` CHECK ((((`time_type` = _utf8mb4'UTC_POINT') and (`at_instant` is not null) and (`at_local` is null) and (`zone_id` is null) and (`start_date` is null) and (`end_date` is null)) or ((`time_type` = _utf8mb4'ZONED_LOCAL_POINT') and (`at_instant` is null) and (`at_local` is not null) and (`zone_id` is not null) and (`start_date` is null) and (`end_date` is null)) or ((`time_type` = _utf8mb4'ALL_DAY') and (`at_instant` is null) and (`at_local` is null) and (`zone_id` is null) and (`start_date` is not null) and (`end_date` is not null) and (`start_date` < `end_date`))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `calendar_subscriptions` (
  `account_id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `subscription_id` binary(16) NOT NULL,
  `revoked` tinyint(1) NOT NULL DEFAULT '0',
  `revocation_pending` tinyint(1) NOT NULL DEFAULT '0',
  `operation_token` binary(16) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`account_id`,`season_id`),
  UNIQUE KEY `uk_calendar_subscription_id` (`subscription_id`),
  KEY `idx_calendar_subscription_revocation` (`revocation_pending`,`lease_until`),
  KEY `fk_calendar_subscription_team` (`team_id`),
  KEY `fk_calendar_subscription_season` (`season_id`),
  CONSTRAINT `fk_calendar_subscription_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_calendar_subscription_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `fk_calendar_subscription_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `content_change_fields` (
  `change_id` binary(16) NOT NULL,
  `field_position` int NOT NULL,
  `field_name` varchar(50) NOT NULL,
  `before_value` text,
  `after_value` text,
  PRIMARY KEY (`change_id`,`field_position`),
  CONSTRAINT `fk_content_change_fields_change` FOREIGN KEY (`change_id`) REFERENCES `content_changes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `content_changes` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `record_kind` varchar(20) NOT NULL,
  `record_id` binary(16) NOT NULL,
  `actor_account_id` binary(16) DEFAULT NULL,
  `actor_name` varchar(100) NOT NULL,
  `changed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_content_changes_season` (`season_id`),
  KEY `idx_content_changes_record` (`team_id`,`season_id`,`record_kind`,`record_id`,`changed_at` DESC,`id` DESC),
  CONSTRAINT `fk_content_changes_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `fk_content_changes_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_content_changes_kind` CHECK ((`record_kind` in (_latin1'DECISION',_latin1'ROLE_RESOURCE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `content_creation_idempotency` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `operation` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `idempotency_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_fingerprint` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_creation_idempotency_team_hash` (`team_id`,`idempotency_hash`),
  KEY `idx_content_creation_idempotency_season` (`season_id`),
  CONSTRAINT `fk_content_creation_idempotency_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `fk_content_creation_idempotency_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_content_creation_idempotency_operation` CHECK ((`operation` in (_latin1'MEMBER',_latin1'SEASON',_latin1'ROLE',_latin1'ROUTINE',_latin1'ROUND',_latin1'DECISION',_latin1'HANDOFF_ITEM',_latin1'ROLE_RESOURCE',_latin1'ROLE_HANDOFF')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `decision_roles` (
  `decision_id` binary(16) NOT NULL,
  `sort_order` int NOT NULL,
  `role_id` binary(16) NOT NULL,
  PRIMARY KEY (`decision_id`,`sort_order`),
  UNIQUE KEY `uk_decision_roles_decision_role` (`decision_id`,`role_id`),
  KEY `idx_decision_roles_role_id` (`role_id`),
  CONSTRAINT `fk_decision_roles_decision` FOREIGN KEY (`decision_id`) REFERENCES `decisions` (`id`),
  CONSTRAINT `fk_decision_roles_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `decisions` (
  `id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `alternative` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `author_member_id` binary(16) NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `text_format` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_decisions_season_created_at` (`season_id`,`created_at`),
  KEY `idx_decisions_author_member_id` (`author_member_id`),
  CONSTRAINT `fk_decisions_author_member` FOREIGN KEY (`author_member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_decisions_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `chk_decisions_text_format` CHECK ((`text_format` in (_latin1'PLAIN_TEXT',_latin1'MARKDOWN')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `email_delivery_receipts` (
  `delivery_id` bigint NOT NULL,
  `event` varchar(32) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`delivery_id`,`event`),
  KEY `idx_email_delivery_receipts_occurred_at` (`occurred_at`),
  CONSTRAINT `fk_email_delivery_receipts_outbox` FOREIGN KEY (`delivery_id`) REFERENCES `email_verification_delivery_outbox` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_email_delivery_receipts_event` CHECK ((`event` in (_latin1'DELIVERED',_latin1'SOFT_BOUNCE',_latin1'HARD_BOUNCE',_latin1'BLOCKED',_latin1'INVALID_EMAIL',_latin1'ERROR',_latin1'DEFERRED',_latin1'SPAM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `email_verification_challenges` (
  `id` binary(16) NOT NULL,
  `identity_id` binary(16) NOT NULL,
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `purpose` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email_verification_challenges_identity` (`identity_id`),
  UNIQUE KEY `uk_email_verification_challenges_token_hash` (`token_hash`),
  CONSTRAINT `fk_email_verification_challenges_identity` FOREIGN KEY (`identity_id`) REFERENCES `account_identities` (`id`),
  CONSTRAINT `chk_email_verification_challenges_consumed_at` CHECK (((`consumed_at` is null) or (`consumed_at` >= `created_at`))),
  CONSTRAINT `chk_email_verification_challenges_expiry` CHECK ((`expires_at` > `created_at`)),
  CONSTRAINT `chk_email_verification_challenges_token_hash` CHECK (regexp_like(`token_hash`,_ascii'^[0-9a-f]{64}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `email_verification_delivery_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `identity_id` binary(16) NOT NULL,
  `payload_ciphertext` varchar(4096) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `payload_nonce` char(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `challenge_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `delivery_status` varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `available_at` datetime(6) NOT NULL,
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `last_error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_email_verification_outbox_claim` (`delivery_status`,`available_at`,`lease_expires_at`,`id`),
  KEY `idx_email_verification_outbox_identity` (`identity_id`,`id`),
  CONSTRAINT `fk_email_verification_outbox_identity` FOREIGN KEY (`identity_id`) REFERENCES `account_identities` (`id`),
  CONSTRAINT `chk_email_verification_outbox_attempt` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_email_verification_outbox_available` CHECK ((`available_at` >= `created_at`)),
  CONSTRAINT `chk_email_verification_outbox_expiry` CHECK ((`expires_at` > `created_at`)),
  CONSTRAINT `chk_email_verification_outbox_lease` CHECK ((((`delivery_status` = _latin1'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null) and (`completed_at` is null)) or ((`delivery_status` = _latin1'PENDING') and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is null)) or ((`delivery_status` in (_latin1'DELIVERED',_latin1'SUPERSEDED',_latin1'FAILED')) and (`lease_token` is null) and (`lease_expires_at` is null) and (`completed_at` is not null)))),
  CONSTRAINT `chk_email_verification_outbox_payload` CHECK ((((`delivery_status` in (_latin1'PENDING',_latin1'PROCESSING')) and regexp_like(`payload_ciphertext`,_latin1'^[A-Za-z0-9_-]{16,4096}$') and regexp_like(`payload_nonce`,_latin1'^[A-Za-z0-9_-]{16}$') and regexp_like(`challenge_token_hash`,_latin1'^[0-9a-f]{64}$')) or ((`delivery_status` in (_latin1'DELIVERED',_latin1'SUPERSEDED',_latin1'FAILED')) and (`payload_ciphertext` is null) and (`payload_nonce` is null) and (`challenge_token_hash` is null)))),
  CONSTRAINT `chk_email_verification_outbox_status` CHECK ((`delivery_status` in (_latin1'PENDING',_latin1'PROCESSING',_latin1'DELIVERED',_latin1'SUPERSEDED',_latin1'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `handoff_items` (
  `id` binary(16) NOT NULL,
  `role_id` binary(16) NOT NULL,
  `label` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `completed` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_handoff_items_role_id` (`role_id`),
  CONSTRAINT `fk_handoff_items_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`),
  CONSTRAINT `chk_handoff_items_category` CHECK ((`category` in (_utf8mb4'RESPONSIBILITY',_utf8mb4'ROUTINE',_utf8mb4'RESOURCE',_utf8mb4'ADVICE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `local_credentials` (
  `identity_id` binary(16) NOT NULL,
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`identity_id`),
  CONSTRAINT `fk_local_credentials_identity` FOREIGN KEY (`identity_id`) REFERENCES `account_identities` (`id`),
  CONSTRAINT `chk_local_credentials_password_hash` CHECK ((char_length(`password_hash`) > 0)),
  CONSTRAINT `chk_local_credentials_updated_at` CHECK ((`updated_at` >= `created_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `members` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `deactivated_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_members_team_name` (`team_id`,`name`),
  UNIQUE KEY `uk_members_id_team` (`id`,`team_id`),
  KEY `idx_members_team_id` (`team_id`),
  CONSTRAINT `fk_members_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `notification_preferences` (
  `account_id` binary(16) NOT NULL,
  `deadline_soon_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `overdue_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `handoff_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `deadline_lead_hours` int NOT NULL DEFAULT '24',
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`account_id`),
  CONSTRAINT `fk_notification_preferences_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `chk_notification_preferences_lead` CHECK ((`deadline_lead_hours` between 1 and 168))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notification_read_receipts` (
  `account_id` binary(16) NOT NULL,
  `notification_id` binary(16) NOT NULL,
  `read_at` datetime(6) NOT NULL,
  PRIMARY KEY (`account_id`,`notification_id`),
  CONSTRAINT `fk_notification_read_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `resource_review_schedules` (
  `resource_id` binary(16) NOT NULL,
  `interval_days` int DEFAULT NULL,
  `next_review_on` date DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`resource_id`),
  CONSTRAINT `fk_resource_review_schedule_resource` FOREIGN KEY (`resource_id`) REFERENCES `role_resources` (`id`),
  CONSTRAINT `chk_resource_review_schedule_interval` CHECK ((((`interval_days` is null) and (`next_review_on` is null)) or ((`interval_days` is not null) and (`interval_days` between 1 and 365) and (`next_review_on` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `resource_verifications` (
  `id` binary(16) NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `resource_version` bigint NOT NULL,
  `account_id` binary(16) NOT NULL,
  `member_id` binary(16) NOT NULL,
  `member_name` varchar(100) NOT NULL,
  `url` varchar(2048) NOT NULL,
  `status` varchar(20) NOT NULL,
  `note` varchar(500) DEFAULT NULL,
  `verified_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_resource_verifications_account` (`account_id`),
  KEY `fk_resource_verifications_member` (`member_id`),
  KEY `ix_resource_verifications_history` (`resource_id`,`verified_at` DESC,`id` DESC),
  CONSTRAINT `fk_resource_verifications_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_resource_verifications_member` FOREIGN KEY (`member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_resource_verifications_resource` FOREIGN KEY (`resource_id`) REFERENCES `role_resources` (`id`),
  CONSTRAINT `chk_resource_verifications_status` CHECK ((`status` in (_latin1'CONFIRMED',_latin1'NEEDS_UPDATE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `role_handoffs` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `role_id` binary(16) NOT NULL,
  `from_member_id` binary(16) NOT NULL,
  `to_member_id` binary(16) NOT NULL,
  `outgoing_assignment_start_date` date DEFAULT NULL,
  `outgoing_assignment_end_date` date DEFAULT NULL,
  `incoming_assignment_start_date` date NOT NULL,
  `incoming_assignment_end_date` date DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `prepared_at` datetime(6) NOT NULL,
  `transferred_at` datetime(6) DEFAULT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `transferred_by_member_id` binary(16) DEFAULT NULL,
  `accepted_by_member_id` binary(16) DEFAULT NULL,
  `cancelled_by_member_id` binary(16) DEFAULT NULL,
  `snapshot_item_count` int DEFAULT NULL,
  `snapshot_incomplete_item_count` int DEFAULT NULL,
  `snapshot_resource_count` int DEFAULT NULL,
  `warning_acknowledged` tinyint(1) NOT NULL DEFAULT '0',
  `version` bigint NOT NULL DEFAULT '0',
  `active_role_id` binary(16) GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'PREPARING',_utf8mb4'TRANSFERRED')) then `role_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_handoffs_active_role` (`active_role_id`),
  KEY `idx_role_handoffs_role_prepared` (`role_id`,`prepared_at` DESC,`id`),
  KEY `idx_role_handoffs_season_status` (`season_id`,`status`,`id`),
  KEY `idx_role_handoffs_from_member_team` (`from_member_id`,`team_id`),
  KEY `idx_role_handoffs_to_member_team` (`to_member_id`,`team_id`),
  KEY `idx_role_handoffs_transferred_by_team` (`transferred_by_member_id`,`team_id`),
  KEY `idx_role_handoffs_accepted_by_team` (`accepted_by_member_id`,`team_id`),
  KEY `idx_role_handoffs_cancelled_by_team` (`cancelled_by_member_id`,`team_id`),
  KEY `fk_role_handoffs_team` (`team_id`),
  KEY `fk_role_handoffs_season_team` (`season_id`,`team_id`),
  KEY `fk_role_handoffs_role_season` (`role_id`,`season_id`),
  CONSTRAINT `fk_role_handoffs_accepted_by_member_team` FOREIGN KEY (`accepted_by_member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `fk_role_handoffs_cancelled_by_member_team` FOREIGN KEY (`cancelled_by_member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `fk_role_handoffs_from_member_team` FOREIGN KEY (`from_member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `fk_role_handoffs_role_season` FOREIGN KEY (`role_id`, `season_id`) REFERENCES `roles` (`id`, `season_id`),
  CONSTRAINT `fk_role_handoffs_season_team` FOREIGN KEY (`season_id`, `team_id`) REFERENCES `seasons` (`id`, `team_id`),
  CONSTRAINT `fk_role_handoffs_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `fk_role_handoffs_to_member_team` FOREIGN KEY (`to_member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `fk_role_handoffs_transferred_by_member_team` FOREIGN KEY (`transferred_by_member_id`, `team_id`) REFERENCES `members` (`id`, `team_id`),
  CONSTRAINT `chk_role_handoffs_distinct_members` CHECK ((`from_member_id` <> `to_member_id`)),
  CONSTRAINT `chk_role_handoffs_incoming_assignment_range` CHECK (((`incoming_assignment_end_date` is null) or (`incoming_assignment_start_date` <= `incoming_assignment_end_date`))),
  CONSTRAINT `chk_role_handoffs_lifecycle` CHECK ((((`status` = _latin1'PREPARING') and (`transferred_at` is null) and (`accepted_at` is null) and (`cancelled_at` is null) and (`transferred_by_member_id` is null) and (`accepted_by_member_id` is null) and (`cancelled_by_member_id` is null) and (`snapshot_item_count` is null) and (`snapshot_incomplete_item_count` is null) and (`snapshot_resource_count` is null) and (`warning_acknowledged` = false)) or ((`status` = _latin1'TRANSFERRED') and (`transferred_at` is not null) and (`accepted_at` is null) and (`cancelled_at` is null) and (`transferred_by_member_id` = `from_member_id`) and (`accepted_by_member_id` is null) and (`cancelled_by_member_id` is null) and (`snapshot_item_count` is not null) and (`snapshot_incomplete_item_count` is not null) and (`snapshot_resource_count` is not null)) or ((`status` = _latin1'ACCEPTED') and (`transferred_at` is not null) and (`accepted_at` is not null) and (`cancelled_at` is null) and (`transferred_by_member_id` = `from_member_id`) and (`accepted_by_member_id` = `to_member_id`) and (`cancelled_by_member_id` is null) and (`snapshot_item_count` is not null) and (`snapshot_incomplete_item_count` is not null) and (`snapshot_resource_count` is not null)) or ((`status` = _latin1'CANCELLED') and (`accepted_at` is null) and (`cancelled_at` is not null) and (`accepted_by_member_id` is null) and (`cancelled_by_member_id` = `from_member_id`) and (((`transferred_at` is null) and (`transferred_by_member_id` is null) and (`snapshot_item_count` is null) and (`snapshot_incomplete_item_count` is null) and (`snapshot_resource_count` is null) and (`warning_acknowledged` = false)) or ((`transferred_at` is not null) and (`transferred_by_member_id` = `from_member_id`) and (`snapshot_item_count` is not null) and (`snapshot_incomplete_item_count` is not null) and (`snapshot_resource_count` is not null)))))),
  CONSTRAINT `chk_role_handoffs_outgoing_assignment_range` CHECK (((`outgoing_assignment_start_date` is null) or (`outgoing_assignment_end_date` is null) or (`outgoing_assignment_start_date` <= `outgoing_assignment_end_date`))),
  CONSTRAINT `chk_role_handoffs_snapshot_counts` CHECK ((((`snapshot_item_count` is null) and (`snapshot_incomplete_item_count` is null) and (`snapshot_resource_count` is null)) or ((`snapshot_item_count` >= 0) and (`snapshot_incomplete_item_count` between 0 and `snapshot_item_count`) and (`snapshot_resource_count` >= 0)))),
  CONSTRAINT `chk_role_handoffs_status` CHECK ((`status` in (_latin1'PREPARING',_latin1'TRANSFERRED',_latin1'ACCEPTED',_latin1'CANCELLED'))),
  CONSTRAINT `chk_role_handoffs_timeline` CHECK ((((`transferred_at` is null) or (`transferred_at` >= `prepared_at`)) and ((`accepted_at` is null) or (`accepted_at` >= `transferred_at`)) and ((`cancelled_at` is null) or (`cancelled_at` >= coalesce(`transferred_at`,`prepared_at`))))),
  CONSTRAINT `chk_role_handoffs_warning_acknowledgement` CHECK (((`transferred_at` is null) or ((`snapshot_item_count` > 0) and (`snapshot_incomplete_item_count` = 0) and (`snapshot_resource_count` > 0)) or (`warning_acknowledged` = true)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `role_resources` (
  `id` binary(16) NOT NULL,
  `role_id` binary(16) NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_role_resources_role_id` (`role_id`),
  CONSTRAINT `fk_role_resources_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `role_responsibilities` (
  `role_id` binary(16) NOT NULL,
  `sort_order` int NOT NULL,
  `responsibility` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`role_id`,`sort_order`),
  CONSTRAINT `fk_role_responsibilities_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `roles` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `previous_role_id` binary(16) DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `purpose` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `current_member_id` binary(16) DEFAULT NULL,
  `next_member_id` binary(16) DEFAULT NULL,
  `assignment_start_date` date DEFAULT NULL,
  `assignment_end_date` date DEFAULT NULL,
  `risk` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roles_season_name` (`season_id`,`name`),
  UNIQUE KEY `uk_roles_id_season` (`id`,`season_id`),
  UNIQUE KEY `uk_roles_season_previous_role` (`season_id`,`previous_role_id`),
  KEY `idx_roles_current_member_id` (`current_member_id`),
  KEY `idx_roles_next_member_id` (`next_member_id`),
  KEY `idx_roles_team_id` (`team_id`),
  KEY `idx_roles_previous_role_id` (`previous_role_id`),
  KEY `fk_roles_season_team` (`season_id`,`team_id`),
  CONSTRAINT `fk_roles_current_member` FOREIGN KEY (`current_member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_roles_next_member` FOREIGN KEY (`next_member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_roles_previous_role` FOREIGN KEY (`previous_role_id`) REFERENCES `roles` (`id`),
  CONSTRAINT `fk_roles_season_team` FOREIGN KEY (`season_id`, `team_id`) REFERENCES `seasons` (`id`, `team_id`),
  CONSTRAINT `fk_roles_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_roles_assignment_range` CHECK (((`assignment_start_date` is null) or (`assignment_end_date` is null) or (`assignment_start_date` <= `assignment_end_date`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `round_room_mappings` (
  `id` binary(16) NOT NULL,
  `room_id` varchar(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_round_room_mappings_room` (`room_id`),
  UNIQUE KEY `uk_round_room_mappings_resource` (`resource_id`),
  KEY `idx_round_room_mappings_season_team` (`season_id`,`team_id`),
  KEY `fk_round_room_mappings_tombstone_snapshot` (`room_id`,`team_id`,`season_id`,`resource_id`),
  CONSTRAINT `fk_round_room_mappings_resource` FOREIGN KEY (`resource_id`) REFERENCES `role_resources` (`id`),
  CONSTRAINT `fk_round_room_mappings_season_team` FOREIGN KEY (`season_id`, `team_id`) REFERENCES `seasons` (`id`, `team_id`),
  CONSTRAINT `fk_round_room_mappings_tombstone_snapshot` FOREIGN KEY (`room_id`, `team_id`, `season_id`, `resource_id`) REFERENCES `round_room_tombstones` (`room_id`, `team_id`, `season_id`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `round_room_tombstones` (
  `room_id` varchar(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`room_id`),
  UNIQUE KEY `uk_round_room_tombstones_snapshot` (`room_id`,`team_id`,`season_id`,`resource_id`),
  CONSTRAINT `chk_round_room_tombstones_room_id` CHECK (regexp_like(`room_id`,_latin1'^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$')),
  CONSTRAINT `chk_round_room_tombstones_timeline` CHECK (((`ended_at` is null) or (`ended_at` >= `created_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `routine_executions` (
  `id` binary(16) NOT NULL,
  `season_round_id` binary(16) NOT NULL,
  `routine_id` binary(16) NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `phase` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `due_label` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deadline_day_offset` int DEFAULT NULL,
  `deadline_time` time(6) DEFAULT NULL,
  `deadline_at` datetime(6) DEFAULT NULL,
  `owner_role_id` binary(16) NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `detail` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_routine_executions_round_routine` (`season_round_id`,`routine_id`),
  KEY `idx_routine_executions_routine_id` (`routine_id`),
  KEY `idx_routine_executions_owner_role_id` (`owner_role_id`),
  CONSTRAINT `fk_routine_executions_owner_role` FOREIGN KEY (`owner_role_id`) REFERENCES `roles` (`id`),
  CONSTRAINT `fk_routine_executions_round` FOREIGN KEY (`season_round_id`) REFERENCES `season_rounds` (`id`),
  CONSTRAINT `fk_routine_executions_routine` FOREIGN KEY (`routine_id`) REFERENCES `routines` (`id`),
  CONSTRAINT `chk_routine_executions_deadline_rule_complete` CHECK ((((`deadline_day_offset` is null) and (`deadline_time` is null) and (`deadline_at` is null)) or ((`deadline_day_offset` is not null) and (`deadline_time` is not null) and (`deadline_at` is not null) and (`deadline_day_offset` between -(30) and 30)))),
  CONSTRAINT `chk_routine_executions_phase` CHECK ((`phase` in (_utf8mb4'BEFORE',_utf8mb4'DURING',_utf8mb4'AFTER'))),
  CONSTRAINT `chk_routine_executions_status` CHECK ((`status` in (_utf8mb4'WAITING',_utf8mb4'DONE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `routines` (
  `id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `previous_routine_id` binary(16) DEFAULT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `phase` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `due_label` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deadline_day_offset` int DEFAULT NULL,
  `deadline_time` time(6) DEFAULT NULL,
  `owner_role_id` binary(16) NOT NULL,
  `detail` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_routines_season_previous_routine` (`season_id`,`previous_routine_id`),
  KEY `idx_routines_season_id` (`season_id`),
  KEY `idx_routines_owner_role_id` (`owner_role_id`),
  KEY `idx_routines_previous_routine_id` (`previous_routine_id`),
  KEY `fk_routines_owner_role_season` (`owner_role_id`,`season_id`),
  CONSTRAINT `fk_routines_owner_role_season` FOREIGN KEY (`owner_role_id`, `season_id`) REFERENCES `roles` (`id`, `season_id`),
  CONSTRAINT `fk_routines_previous_routine` FOREIGN KEY (`previous_routine_id`) REFERENCES `routines` (`id`),
  CONSTRAINT `fk_routines_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `chk_routines_deadline_rule_complete` CHECK ((((`deadline_day_offset` is null) and (`deadline_time` is null)) or ((`deadline_day_offset` is not null) and (`deadline_time` is not null) and (`deadline_day_offset` between -(30) and 30)))),
  CONSTRAINT `chk_routines_phase` CHECK ((`phase` in (_utf8mb4'BEFORE',_utf8mb4'DURING',_utf8mb4'AFTER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `season_rounds` (
  `id` binary(16) NOT NULL,
  `season_id` binary(16) NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `meeting_date` date NOT NULL,
  `origin` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scheduled_occurrence_date` date DEFAULT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_season_rounds_season_name` (`season_id`,`name`),
  UNIQUE KEY `uk_season_rounds_season_occurrence` (`season_id`,`scheduled_occurrence_date`),
  CONSTRAINT `fk_season_rounds_season` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`),
  CONSTRAINT `chk_season_rounds_origin` CHECK ((`origin` in (_latin1'MANUAL',_latin1'AUTOMATIC'))),
  CONSTRAINT `chk_season_rounds_schedule_metadata` CHECK ((((`origin` = _latin1'MANUAL') and (`scheduled_occurrence_date` is null) and (`scheduled_at` is null)) or ((`origin` = _latin1'AUTOMATIC') and (`scheduled_occurrence_date` is not null) and (`scheduled_at` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `seasons` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `previous_season_id` binary(16) DEFAULT NULL,
  `time_zone` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `round_schedule_first_meeting_date` date DEFAULT NULL,
  `round_schedule_meeting_time` time(6) DEFAULT NULL,
  `round_schedule_recurrence` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_schedule_generation_lead_days` int DEFAULT NULL,
  `round_schedule_enabled` tinyint(1) DEFAULT NULL,
  `round_schedule_next_occurrence_date` date DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `active_team_id` binary(16) GENERATED ALWAYS AS ((case when (`ended_at` is null) then `team_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seasons_team_name` (`team_id`,`name`),
  UNIQUE KEY `uk_seasons_id_team` (`id`,`team_id`),
  UNIQUE KEY `uk_seasons_previous_season` (`previous_season_id`),
  UNIQUE KEY `uk_seasons_active_team` (`active_team_id`),
  KEY `idx_seasons_team_id` (`team_id`),
  KEY `fk_seasons_previous_season_team` (`previous_season_id`,`team_id`),
  KEY `idx_seasons_round_schedule_scan` (`round_schedule_enabled`,`ended_at`,`id`),
  CONSTRAINT `fk_seasons_previous_season_team` FOREIGN KEY (`previous_season_id`, `team_id`) REFERENCES `seasons` (`id`, `team_id`),
  CONSTRAINT `fk_seasons_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_seasons_date_range` CHECK ((`start_date` <= `end_date`)),
  CONSTRAINT `chk_seasons_round_schedule_complete` CHECK ((((`round_schedule_first_meeting_date` is null) and (`round_schedule_meeting_time` is null) and (`round_schedule_recurrence` is null) and (`round_schedule_generation_lead_days` is null) and (`round_schedule_enabled` is null) and (`round_schedule_next_occurrence_date` is null)) or ((`round_schedule_first_meeting_date` is not null) and (`round_schedule_meeting_time` is not null) and (`round_schedule_recurrence` is not null) and (`round_schedule_generation_lead_days` is not null) and (`round_schedule_enabled` is not null) and (`round_schedule_next_occurrence_date` is not null)))),
  CONSTRAINT `chk_seasons_round_schedule_dates` CHECK (((`round_schedule_first_meeting_date` is null) or ((`round_schedule_first_meeting_date` between `start_date` and `end_date`) and (`round_schedule_next_occurrence_date` >= `round_schedule_first_meeting_date`)))),
  CONSTRAINT `chk_seasons_round_schedule_enabled` CHECK (((`round_schedule_enabled` is null) or (`round_schedule_enabled` in (false,true)))),
  CONSTRAINT `chk_seasons_round_schedule_lead_days` CHECK (((`round_schedule_generation_lead_days` is null) or (`round_schedule_generation_lead_days` between 0 and 30))),
  CONSTRAINT `chk_seasons_round_schedule_recurrence` CHECK (((`round_schedule_recurrence` is null) or (`round_schedule_recurrence` in (_latin1'WEEKLY',_latin1'BIWEEKLY'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `team_access_audit` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `actor_account_id` binary(16) NOT NULL,
  `member_id` binary(16) NOT NULL,
  `action` varchar(30) NOT NULL,
  `previous_permission` varchar(16) DEFAULT NULL,
  `permission` varchar(16) DEFAULT NULL,
  `changed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_team_access_audit_actor` (`actor_account_id`),
  KEY `fk_team_access_audit_member` (`member_id`),
  KEY `ix_team_access_audit_history` (`team_id`,`changed_at` DESC,`id` DESC),
  CONSTRAINT `fk_team_access_audit_actor` FOREIGN KEY (`actor_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_team_access_audit_member` FOREIGN KEY (`member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_team_access_audit_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `team_invitations` (
  `id` binary(16) NOT NULL,
  `team_id` binary(16) NOT NULL,
  `member_id` binary(16) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `permission` varchar(16) NOT NULL,
  `created_by` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  `accepted_by` binary(16) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_invitation_token` (`token_hash`),
  KEY `ix_team_invitation_history` (`team_id`,`created_at` DESC),
  KEY `fk_team_invitation_member` (`member_id`),
  KEY `fk_team_invitation_creator` (`created_by`),
  KEY `fk_team_invitation_acceptor` (`accepted_by`),
  CONSTRAINT `fk_team_invitation_acceptor` FOREIGN KEY (`accepted_by`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_team_invitation_creator` FOREIGN KEY (`created_by`) REFERENCES `accounts` (`id`),
  CONSTRAINT `fk_team_invitation_member` FOREIGN KEY (`member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_team_invitation_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `chk_team_invitation_permission` CHECK ((`permission` in (_latin1'ADMIN',_latin1'MEMBER',_latin1'VIEWER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `teams` (
  `id` binary(16) NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `access_key_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `idempotency_key_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `creation_request_fingerprint` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `creation_season_id` binary(16) DEFAULT NULL,
  `last_access_key_change_idempotency_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `account_access_enabled` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teams_idempotency_key_hash` (`idempotency_key_hash`),
  CONSTRAINT `chk_teams_creation_request_metadata` CHECK ((((`idempotency_key_hash` is null) and (`creation_request_fingerprint` is null) and (`creation_season_id` is null)) or ((`idempotency_key_hash` is not null) and (`creation_request_fingerprint` is not null) and (`creation_season_id` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `watch_health_event_inbox` (
  `event_id` binary(16) NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `event_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_reference` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_revision` bigint NOT NULL,
  `attempt_id` binary(16) DEFAULT NULL,
  `previous_health` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `current_health` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `changed_at` datetime(6) NOT NULL,
  `changed_at_nano_remainder` smallint NOT NULL,
  `payload_fingerprint` binary(32) NOT NULL,
  `accepted_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_watch_health_event_inbox_resource_revision` (`resource_id`,`source_revision`,`changed_at`,`event_id`),
  CONSTRAINT `chk_watch_health_event_inbox_current_health` CHECK ((`current_health` in (_latin1'UNKNOWN',_latin1'HEALTHY',_latin1'DEGRADED',_latin1'BROKEN'))),
  CONSTRAINT `chk_watch_health_event_inbox_nano_remainder` CHECK ((`changed_at_nano_remainder` between 0 and 999)),
  CONSTRAINT `chk_watch_health_event_inbox_previous_health` CHECK ((`previous_health` in (_latin1'UNKNOWN',_latin1'HEALTHY',_latin1'DEGRADED',_latin1'BROKEN'))),
  CONSTRAINT `chk_watch_health_event_inbox_revision` CHECK ((`source_revision` >= 0)),
  CONSTRAINT `chk_watch_health_event_inbox_transition` CHECK ((`previous_health` <> `current_health`)),
  CONSTRAINT `chk_watch_health_event_inbox_type` CHECK ((`event_type` = _latin1'RESOURCE_HEALTH_CHANGED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `watch_monitor_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` binary(16) NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `resource_reference` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `monitoring_state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `compensation_for_id` bigint DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `delivery_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `available_at` datetime(6) NOT NULL,
  `lease_token` binary(16) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `result_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_watch_monitor_outbox_event` (`event_id`),
  UNIQUE KEY `uk_watch_monitor_outbox_compensation_source` (`compensation_for_id`),
  KEY `idx_watch_monitor_outbox_resource_revision` (`resource_id`,`id`),
  KEY `idx_watch_monitor_outbox_pending_claim` (`delivery_status`,`available_at`,`id`),
  KEY `idx_watch_monitor_outbox_expired_claim` (`delivery_status`,`lease_expires_at`,`id`),
  CONSTRAINT `fk_watch_monitor_outbox_compensation_source` FOREIGN KEY (`compensation_for_id`) REFERENCES `watch_monitor_outbox` (`id`),
  CONSTRAINT `chk_watch_monitor_outbox_attempt_count` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `chk_watch_monitor_outbox_compensation` CHECK (((`compensation_for_id` is null) or ((`monitoring_state` = _latin1'INACTIVE') and (`target_url` is null)))),
  CONSTRAINT `chk_watch_monitor_outbox_completion` CHECK ((((`delivery_status` in (_utf8mb4'DELIVERED',_utf8mb4'FAILED')) and (`completed_at` is not null)) or ((`delivery_status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING')) and (`completed_at` is null)))),
  CONSTRAINT `chk_watch_monitor_outbox_delivery_status` CHECK ((`delivery_status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'DELIVERED',_utf8mb4'FAILED'))),
  CONSTRAINT `chk_watch_monitor_outbox_lease` CHECK ((((`delivery_status` = _utf8mb4'PROCESSING') and (`lease_token` is not null) and (`lease_expires_at` is not null)) or ((`delivery_status` <> _utf8mb4'PROCESSING') and (`lease_token` is null) and (`lease_expires_at` is null)))),
  CONSTRAINT `chk_watch_monitor_outbox_monitoring_state` CHECK ((`monitoring_state` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE'))),
  CONSTRAINT `chk_watch_monitor_outbox_target` CHECK ((((`monitoring_state` = _utf8mb4'ACTIVE') and (`target_url` is not null)) or ((`monitoring_state` = _utf8mb4'INACTIVE') and (`target_url` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
