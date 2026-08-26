ALTER TABLE `${tablePrefix}workflow_instances` ADD COLUMN `parent_workflow_id` varchar(255) NULL DEFAULT NULL;
