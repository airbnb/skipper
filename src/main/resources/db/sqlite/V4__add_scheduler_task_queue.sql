-- SQLite-dialect counterpart of db/migration/V8__add_scheduler_task_queue.sql.
--
-- The fair-share queue of each scheduler task, and the index the per-queue fetch reads. Rows
-- written before this migration land in the 'default' queue.

ALTER TABLE ${tablePrefix}scheduler_tasks ADD COLUMN queue_name TEXT NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS ${tablePrefix}scheduler_task_index_by_owner_and_queue
  ON ${tablePrefix}scheduler_tasks (owner, queue_name, status, run_after);
