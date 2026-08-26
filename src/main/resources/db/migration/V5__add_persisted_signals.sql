CREATE TABLE `${tablePrefix}persisted_signals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner` varchar(255) NOT NULL,
  `workflow_id` varchar(255) NOT NULL,
  `signal_method` varchar(255) NOT NULL,
  `input` text DEFAULT NULL,
  `request_context` text DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `error` text DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `${tablePrefix}persisted_signal_index_by_workflow_id` (`owner`, `workflow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
-- DDL ENDS HERE. STOP COPYING.
-- Update the schema migrations table to record this migration
INSERT INTO `${tablePrefix}schema_migrations` (`migration_id`, `created_at`) VALUES (5, NOW());
