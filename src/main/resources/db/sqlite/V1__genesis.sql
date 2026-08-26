-- SQLite-dialect schema for Skipper's five tables, parameterized by the configurable table-name
-- prefix (default skipper_).
--
-- This lives in a SQLite-specific Flyway location (db/sqlite) so it never collides with the
-- MySQL-dialect scripts under db/migration. It is applied by JdbcTransactionManager.SqliteFactory
-- when it builds the SQLite DataSource, so the in-memory default self-bootstraps with zero external
-- infrastructure. Flyway tracks applied migrations, so re-running migrate() is a no-op.
--
-- MySQL -> SQLite type mapping:
--   varchar(n) / text                       -> TEXT
--   tinyint(1) (boolean)                     -> INTEGER
--   int                                      -> INTEGER
--   bigint NOT NULL AUTO_INCREMENT (pk)      -> INTEGER PRIMARY KEY AUTOINCREMENT (aliases rowid,
--                                               so getGeneratedKeys() returns it)
--   datetime(3)                              -> INTEGER (xerial sqlite-jdbc with date_class=INTEGER
--                                               round-trips java.sql.Timestamp as epoch-millis long)
-- ENGINE=InnoDB / DEFAULT CHARSET clauses are dropped and backtick quoting removed. Composite
-- primary keys and named secondary indexes are preserved exactly.

CREATE TABLE IF NOT EXISTS ${tablePrefix}workflow_instances (
  workflow_id TEXT NOT NULL,
  owner TEXT NOT NULL,
  workflow_class TEXT NOT NULL,
  workflow_method TEXT NOT NULL,
  input TEXT DEFAULT NULL,
  state TEXT DEFAULT NULL,
  result_value TEXT DEFAULT NULL,
  result_error TEXT DEFAULT NULL,
  result_is_error INTEGER NOT NULL DEFAULT 0,
  result_is_done INTEGER NOT NULL DEFAULT 0,
  result_is_async INTEGER NOT NULL DEFAULT 0,
  callback_handler TEXT DEFAULT NULL,
  status TEXT NOT NULL,
  request_context TEXT DEFAULT NULL,
  extra_request_data TEXT DEFAULT NULL,
  timeout_time INTEGER DEFAULT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  parent_workflow_id TEXT DEFAULT NULL,
  PRIMARY KEY (owner, workflow_id)
);

CREATE INDEX IF NOT EXISTS ${tablePrefix}workflow_instance_index_by_owner_and_status
  ON ${tablePrefix}workflow_instances (owner, status, workflow_class, workflow_method);

CREATE TABLE IF NOT EXISTS ${tablePrefix}action_checkpoints (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner TEXT NOT NULL,
  workflow_id TEXT NOT NULL,
  action_class TEXT NOT NULL,
  action_method TEXT NOT NULL,
  iteration INTEGER NOT NULL,
  execution_start_time INTEGER NOT NULL,
  execution_end_time INTEGER DEFAULT NULL,
  is_success INTEGER NOT NULL DEFAULT 0,
  result TEXT DEFAULT NULL,
  result_is_async INTEGER NOT NULL DEFAULT 0,
  error TEXT DEFAULT NULL,
  is_transient INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  input TEXT DEFAULT NULL,
  checkpoint_name TEXT DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS ${tablePrefix}action_checkpoint_index_by_workflow_id
  ON ${tablePrefix}action_checkpoints (owner, workflow_id);

CREATE TABLE IF NOT EXISTS ${tablePrefix}timers (
  timer_id TEXT NOT NULL,
  owner TEXT NOT NULL,
  workflow_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  status TEXT NOT NULL,
  duration_in_secs INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (owner, workflow_id, timer_id)
);

CREATE TABLE IF NOT EXISTS ${tablePrefix}scheduler_tasks (
  task_id TEXT NOT NULL,
  owner TEXT NOT NULL,
  run_after INTEGER NOT NULL,
  payload TEXT DEFAULT NULL,
  type TEXT NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0,
  dedup_token TEXT NOT NULL,
  status TEXT NOT NULL,
  status_message TEXT DEFAULT NULL,
  execution_timeout_secs INTEGER NOT NULL DEFAULT 0,
  should_refresh_payload INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (owner, task_id)
);

CREATE INDEX IF NOT EXISTS ${tablePrefix}scheduler_task_index_by_owner_and_status
  ON ${tablePrefix}scheduler_tasks (owner, status, run_after);

CREATE TABLE IF NOT EXISTS ${tablePrefix}persisted_signals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner TEXT NOT NULL,
  workflow_id TEXT NOT NULL,
  signal_method TEXT NOT NULL,
  input TEXT DEFAULT NULL,
  request_context TEXT DEFAULT NULL,
  status TEXT NOT NULL,
  error TEXT DEFAULT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ${tablePrefix}persisted_signal_index_by_workflow_id
  ON ${tablePrefix}persisted_signals (owner, workflow_id);
