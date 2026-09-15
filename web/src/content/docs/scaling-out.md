---
title: Scaling Out & Task Partitioning
description: Run several Skipper instances against one database without contention — how cluster membership and bucket partitioning divide the scheduler queue between instances.
section: Guides
order: 18
---

One Skipper instance handles a service's workflows on its own. When you run several instances
against a shared database, each of them polls the same scheduler queue for ready tasks. Leases
keep any task from being processed twice, but the instances still race for the same rows, and the
losers do work for nothing. **Task partitioning** removes that contention: every instance fetches
only the tasks that hash into its own share of the queue.

This page explains how the mechanism works, how to enable it, and how to see it running. It
applies to the MySQL and SQLite backends; a single instance on the embedded default does not need
it.

## How it works

Partitioning has two parts: knowing who is in the cluster, and dividing the queue between them.

**Cluster membership.** Each instance registers itself as a *member* of its tenant's cluster and
heartbeats a row in the `<prefix>cluster_members` table every `clusterHeartBeatInterval`
(10 seconds by default). The live member list is every row heartbeated within the last
60 seconds, sorted by member id. Because every instance reads the same table and sorts the same
way, they all agree on the list without talking to each other.

**Bucket partitioning.** Task IDs are hashed with CRC32 into 1000 buckets, the same hash on every
backend. The 1000 buckets are split evenly across the sorted member list; when they do not divide,
the first members take one extra. Member *n* of *N* owns a contiguous half-open range
`[start, end)`, and its scheduler fetches only ready tasks whose bucket falls in that range. With
three members the ranges are `[0, 334)`, `[334, 667)` and `[667, 1000)`.

MySQL applies the bucket predicate in SQL. SQLite reads a window of ready candidates, filters
them by bucket in memory, and leases the survivors; the result is the same set of eligible tasks.

**Membership changes.** The list is recomputed before every fetch from a view refreshed once per
heartbeat interval, so ranges follow the cluster:

- A new instance is in the list as soon as `SkipperRuntime` starts it: its first heartbeat runs
  synchronously, before it fetches anything.
- A gracefully stopped instance deletes its row on `stop()`, and the survivors absorb its range at
  once.
- An instance that crashes keeps its row until the heartbeat is 60 seconds old, then drops out.
  Tasks in its range wait at most that long, plus one heartbeat interval, before another member
  picks them up.

During any of these transitions two members may briefly compute overlapping ranges. That is
safe: partitioning only decides *which* tasks an instance looks at, while the per-task lease
still guarantees a task runs once. The cost of overlap is a little contention, never a duplicate
or a lost task.

**Fallbacks.** An instance fetches from the whole queue, exactly as it does without partitioning,
whenever it cannot compute a partition: the `task_partitioning` feature key is off, the instance
is not a registered member, no member is live yet, or its own row is not in the list. So the
feature degrades to today's behavior rather than stalling.

## Enabling it

Two conditions turn partitioning on: the `task_partitioning` feature key, which the default
`FeatureGate` enables, and a cluster-aware membership manager. The default membership manager is
a single-member no-op that never registers, so nothing changes until you opt in.

Apply the `V6__add_cluster_members` MySQL migration along with the others (see
[Storage](/docs/storage/#creating-the-schema)), then configure the JDBC membership manager next
to the MySQL store and scheduler:

```kotlin
val config = SkipperConfig.forService("my-service").apply {
  workflowStore = MySqlWorkflowStore.Factory()
  scheduler = MySqlScheduler.Factory()
  clusterMembershipManager = JdbcClusterMembershipManager.MySqlFactory()
  mySqlDataSource = dataSource
  clusterMemberName = System.getenv("HOSTNAME") // unique per instance; defaults to the hostname
}
```

```java
SkipperConfig config = SkipperConfig.forService("my-service");
config.setWorkflowStore(new MySqlWorkflowStore.Factory());
config.setScheduler(new MySqlScheduler.Factory());
config.setClusterMembershipManager(new JdbcClusterMembershipManager.MySqlFactory());
config.setMySqlDataSource(dataSource);
config.setClusterMemberName(System.getenv("HOSTNAME")); // unique per instance; defaults to the hostname
```

For a file-backed SQLite database shared by several processes on one host, use
`JdbcClusterMembershipManager.SqliteFactory(path)` with the same path as the SQLite store and
scheduler. The SQLite schema is created automatically, migration included.

Three settings matter:

| Setting | Default | Notes |
|---|---|---|
| `clusterMembershipManager` | single-member no-op | Set `JdbcClusterMembershipManager.MySqlFactory()` or `.SqliteFactory(path)`. `SkipperRuntime` rejects a manager that points at a different database than the scheduler. |
| `clusterMemberName` | local hostname | Must be unique among instances sharing a store and tenant. Set it explicitly (pod name, container id) when several instances run on one host, otherwise they collide on one member row and all fetch the same range. |
| `clusterHeartBeatInterval` | 10 s | How often a member heartbeats and refreshes its view of the membership. The 60 s liveness window is fixed. |

Membership is scoped by [`tenant`](/docs/reference/configuration/#tenant-isolation), like every
other row Skipper writes, so deployments sharing a database but using different tenants form
separate clusters.

## Seeing it run

**Admin UI.** The **Cluster** page of the [admin UI](/docs/observability/) lists the live members
with each one's bucket range and share, marks the member that served the request, and states
whether partitioning is active on that instance. When it is not, the page says why the instance is
fetching from the whole queue. The same data is available as JSON from `GET /skipper/admin/cluster`.

**Metrics.** Through the configured [`Metrics`](/docs/observability/#metrics) backend:

| Metric | Meaning |
|---|---|
| `schedulerManager.fetchedTasks{partitioned=true}` | A partitioned fetch ran. If this stays at zero while `schedulerManager.fetchedTasks` grows, partitioning is not active. |
| `schedulerManager.fetchErrors{error=partitioning_fallback}` | A partitioned fetch failed and the instance fell back to the whole queue for that round. |
| `schedulerManager.clusterMemberStartRange` / `clusterMemberEndRange` | Gauges of this member's current `[start, end)` range, tagged with `memberId` and `clusterName`. Both read 0 while the member is absent from the live list. |
| `jdbcClusterMembershipManager.heartbeat` / `getActiveMembers` | Timers for the heartbeat write and the membership read. |
| `sqliteScheduler.leaseLockContention` | SQLite only: a lease attempt skipped because another instance held the table lock. Expected to be low and non-zero under load. |

## Operational notes

- **Rolling deploys.** Because a graceful stop removes the member row, a rolling restart hands
  ranges over immediately at each step. Make sure your host calls `SkipperRuntime.stop()` on
  shutdown; a killed process behaves like a crash and its range waits out the liveness window.
- **Uneven work.** Buckets are split evenly, not tasks. Task IDs are UUIDs by default and hash
  uniformly, so shares are even in practice; if you supply your own IDs, keep them varied.
- **Stale members after a crash.** Expect up to a minute of reduced throughput for the crashed
  member's range, not lost tasks. Lower `clusterHeartBeatInterval` does not shorten this window;
  the 60 s threshold is what matters.
- **SQLite specifics.** Several processes sharing one SQLite file contend for the database lock.
  Skipper retries transient lock errors on the fetch path, but throughput is bounded by SQLite's
  single-writer model; use MySQL for multi-instance production deployments.
