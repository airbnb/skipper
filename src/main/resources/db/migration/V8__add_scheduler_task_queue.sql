-- The fair-share queue of each scheduler task, and the index the per-queue fetch reads.
-- Rows written before this migration, and by processes still running the previous version, land in
-- the 'default' queue.
ALTER TABLE `${tablePrefix}scheduler_tasks`
  ADD COLUMN `queue_name` varchar(255) NOT NULL DEFAULT 'default',
  ADD INDEX `${tablePrefix}scheduler_task_index_by_owner_and_queue` (`owner`, `queue_name`, `status`, `run_after`);
-- DDL ENDS HERE. STOP COPYING.
-- Update the schema migrations table to record this migration
INSERT INTO `${tablePrefix}schema_migrations` (`migration_id`, `created_at`) VALUES (8, NOW());
