-- SQLite-dialect counterpart of db/migration/V6__add_cluster_members.sql.
--
-- One row per live Skipper instance in a cluster, refreshed by the JDBC cluster membership
-- manager's heartbeat. Members whose last_heartbeat_at is older than the liveness threshold are
-- treated as gone. Same type mapping as V1__genesis.sql (datetime(3) -> INTEGER epoch-millis).

CREATE TABLE IF NOT EXISTS ${tablePrefix}cluster_members (
  owner TEXT NOT NULL,
  cluster_name TEXT NOT NULL,
  member_id TEXT NOT NULL,
  last_heartbeat_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (owner, cluster_name, member_id)
);

CREATE INDEX IF NOT EXISTS ${tablePrefix}cluster_member_index_by_cluster_and_heartbeat
  ON ${tablePrefix}cluster_members (owner, cluster_name, last_heartbeat_at);
