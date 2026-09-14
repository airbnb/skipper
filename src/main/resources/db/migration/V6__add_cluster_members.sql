CREATE TABLE `${tablePrefix}cluster_members` (
  `owner` varchar(255) NOT NULL,
  `cluster_name` varchar(255) NOT NULL,
  `member_id` varchar(255) NOT NULL,
  `last_heartbeat_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`owner`, `cluster_name`, `member_id`),
  INDEX `${tablePrefix}cluster_member_index_by_cluster_and_heartbeat` (`owner`, `cluster_name`, `last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
-- DDL ENDS HERE. STOP COPYING.
-- Update the schema migrations table to record this migration
INSERT INTO `${tablePrefix}schema_migrations` (`migration_id`, `created_at`) VALUES (6, NOW());
