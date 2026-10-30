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
  PRIMARY KEY (`owner`, `workflow_id`, `timer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
