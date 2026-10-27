-- SQLite-dialect counterpart of db/migration/V7__add_parent_workflow_id_index.sql.
--
-- Lets child workflows be counted per parent (optionally by status) without scanning every
-- instance of the owner.

CREATE INDEX IF NOT EXISTS ${tablePrefix}workflow_instance_index_by_parent
  ON ${tablePrefix}workflow_instances (owner, parent_workflow_id, status);
