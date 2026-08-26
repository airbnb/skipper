CREATE TABLE `${tablePrefix}workflow_instances` (
  `workflow_id` varchar(255) NOT NULL,
  `owner` varchar(255) NOT NULL,
  `workflow_class` varchar(255) NOT NULL,
  `workflow_method` varchar(255) NOT NULL,
  `input` text DEFAULT NULL,
  `state` text DEFAULT NULL,
  `result_value` text DEFAULT NULL,
  `result_error` text DEFAULT NULL,
  `result_is_error` tinyint(1) NOT NULL DEFAULT '0',
  `result_is_done` tinyint(1) NOT NULL DEFAULT '0',
  `result_is_async` tinyint(1) NOT NULL DEFAULT '0',
  `callback_handler` varchar(255) DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `request_context` text DEFAULT NULL,
  `extra_request_data` text DEFAULT NULL,
  `timeout_time` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`owner`, `workflow_id`),
  INDEX `${tablePrefix}workflow_instance_index_by_owner_and_status` (`owner`, `status`,`workflow_class`, `workflow_method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `${tablePrefix}action_checkpoints` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner` varchar(255) NOT NULL,
  `workflow_id` varchar(255) NOT NULL,
  `action_class` varchar(255) NOT NULL,
  `action_method` varchar(255) NOT NULL,
  `iteration` int NOT NULL,
  `execution_start_time` datetime(3) NOT NULL,
  `execution_end_time` datetime(3) DEFAULT NULL,
  `is_success` tinyint(1) NOT NULL DEFAULT '0',
  `result` text DEFAULT NULL,
  `result_is_async` tinyint(1) NOT NULL DEFAULT '0',
  `error` text DEFAULT NULL,
  `is_transient` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `${tablePrefix}action_checkpoint_index_by_workflow_id` (`owner`, `workflow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `${tablePrefix}timers` (
  `timer_id` varchar(255) NOT NULL,
  `owner` varchar(255) NOT NULL,
  `workflow_id` varchar(255) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `status` varchar(50) NOT NULL,
  `duration_in_secs` int NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`owner`, `workflow_id`,`timer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

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
  INDEX `${tablePrefix}scheduler_task_index_by_owner_and_status` (`owner`,`status`,`run_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `${tablePrefix}schema_migrations` (
  `migration_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`migration_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
-- DDL ENDS HERE. STOP COPYING.
-- Update the schema migrations table to record this migration
INSERT INTO `${tablePrefix}schema_migrations` (`migration_id`, `created_at`) VALUES (1, NOW());

