ALTER TABLE `${tablePrefix}workflow_instances`
  ADD INDEX `${tablePrefix}workflow_instance_index_by_parent` (`owner`, `parent_workflow_id`, `status`);
-- DDL ENDS HERE. STOP COPYING.
-- Update the schema migrations table to record this migration
INSERT INTO `${tablePrefix}schema_migrations` (`migration_id`, `created_at`) VALUES (7, NOW());
