ALTER TABLE `notification_preferences`
  ADD COLUMN `resource_review_enabled` tinyint(1) NOT NULL DEFAULT '1' AFTER `handoff_enabled`;
