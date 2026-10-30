CREATE TABLE `${tablePrefix}scheduler_tasks` (
  `task_id` varchar(255) NOT NULL,
  `owner` varchar(255) NOT NULL,
  `run_after` datetime(3) NOT NULL,
  `payload` text DEFAULT NULL,
  `type` varchar(255) NOT NULL,
  `retry_count` int NOT NULL DEFAULT '0',
  `dedup_token` varchar(255) NOT NULL,
  `status` varchar(50) NOT NULL,
  `status_message` varchar(255) DEFAULT NULL,
  `execution_timeout_secs` bigint NOT NULL DEFAULT '0',
  `should_refresh_payload` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`owner`, `task_id`),
  INDEX `${tablePrefix}scheduler_task_index_by_owner_and_status` (`owner`, `status`, `run_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
