---
title: Storage Backends
description: Where Skipper persists workflow state — an embedded SQLite store by default, MySQL for production, behind pluggable interfaces.
section: Guides
order: 17
---

Skipper persists workflow state and schedules work through two pluggable interfaces: a
**workflow store** and a **scheduler**. Both are set on your `SkipperConfig`, so you can run
Skipper on the embedded default to start, then point it at your production database — without
changing any workflow code.

## The default: embedded SQLite

Out of the box, Skipper uses an **embedded SQLite** store. With no configuration it runs
fully **in-memory**, so you can build and run workflows with zero setup:

```kotlin
val config = SkipperConfig.forService("my-service") // in-memory SQLite, nothing else needed
```

```java
SkipperConfig config = SkipperConfig.forService("my-service"); // in-memory SQLite, nothing else needed
```

The in-memory store keeps no data after the process exits, which is exactly what you want for
getting started, local development, and tests. For a durable single-node store, back SQLite
with a file instead. Give both factories the same path so the store and the scheduler share one
database:

```kotlin
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore

val config = SkipperConfig.forService("my-service").apply {
  workflowStore = SqliteWorkflowStore.Factory("skipper.db")
  scheduler = SqliteScheduler.Factory("skipper.db")
}
```

```java
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;

SkipperConfig config = SkipperConfig.forService("my-service");
config.setWorkflowStore(new SqliteWorkflowStore.Factory("skipper.db"));
config.setScheduler(new SqliteScheduler.Factory("skipper.db"));
```

If you already manage a `javax.sql.DataSource` (for pooling, or to point at a path decided at
runtime), set `sqliteDataSource` on the config instead and keep the no-argument factories:

```kotlin
config.sqliteDataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:skipper.db" }
```

```java
SQLiteDataSource ds = new SQLiteDataSource();
ds.setUrl("jdbc:sqlite:skipper.db");
config.setSqliteDataSource(ds);
```

The SQLite backend creates and migrates its own schema on startup (from `db/sqlite` in the jar),
so there is no manual migration step in either mode.

SQLite is single-node by design. To run multiple Skipper instances against shared state — the
typical production setup — use MySQL.

## MySQL (production)

The MySQL adapter stores Skipper's tables in the same database your application already uses,
so Skipper adds **no new critical dependency** and supports running multiple instances against
shared state. Set the factories and provide a JDBC `DataSource`:

```kotlin
val config = SkipperConfig.forService("my-service").apply {
  workflowStore = MySqlWorkflowStore.Factory()
  scheduler = MySqlScheduler.Factory()
  mySqlDataSource = dataSource
}
```

```java
SkipperConfig config = SkipperConfig.forService("my-service");
config.setWorkflowStore(new MySqlWorkflowStore.Factory());
config.setScheduler(new MySqlScheduler.Factory());
config.setMySqlDataSource(dataSource);
```

### Creating the schema

The MySQL adapter requires its schema to exist. Skipper's schema ships as versioned **Flyway
migrations** bundled in the jar under `db/migration` (they create Skipper's tables under a
configurable prefix — `skipper_*` by default, or `tempo_*` for deployments that keep the legacy
names, set via `SkipperConfig.tablePrefix`).
Apply them to your database **once, before the first run** — Skipper does not run them
automatically. The simplest way is to point Flyway at the bundled migrations on the classpath:

```kotlin
Flyway.configure()
  .dataSource(dataSource)
  .locations("classpath:db/migration") // migrations bundled in the Skipper jar
  .load()
  .migrate()
```

If you manage schema changes with your own tooling, apply the `V*__*.sql` files from
`db/migration` in order instead.

### Running multiple instances

Several instances of your service can share one MySQL database. Each fetches ready tasks and takes
a lease on them, so they never process a task twice, but they do compete for the same rows.
Enable **task partitioning** to remove that contention: each instance registers itself in the
`skipper_cluster_members` table and heartbeats it, every instance derives the same ordered member
list, and each fetches only the tasks whose ID hashes into its own bucket range.

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

A new instance is in the member list as soon as it starts (its first heartbeat runs synchronously),
and a gracefully stopped one removes itself, so its buckets are redistributed at once. An instance
that crashes drops out once its heartbeat is older than 60 seconds; heartbeats run every
`clusterHeartBeatInterval` (10 seconds by default), and each instance refreshes its view of the
membership at that cadence. Partitioning is gated by the
`task_partitioning` feature key, which the default `FeatureGate` enables; an instance that cannot
determine its partition falls back to fetching from the whole queue, so it degrades to today's
behavior rather than stalling. The same manager is available for a file-backed SQLite database
shared by processes on one host as `JdbcClusterMembershipManager.SqliteFactory(path)`.

**Migrations are immutable.** Every schema change ships as a new migration file. Because the
library runs against each adopting service's database, a code change that references a new
column must wait until that migration has been applied everywhere it runs — otherwise the
deployed code references a column that doesn't yet exist. Apply the migration first, then
deploy the code.

> Configuration such as the store, scheduler, retry strategy, and checkpoint mode is set on
> `SkipperConfig` directly. `SkipperRuntime(config)` then wires the engine from it — see the
> **[Quickstart](/docs/quickstart/)**.

## Serialization

Workflow state, action results, signals, and errors are persisted as serialized blobs.
Types must be serializable — a primitive, or a POJO/data class that Jackson can serialize
(no top-level generics). Before persisting an argument, Skipper checks it by serializing it,
reading it back, and comparing the two with `equals()`, so **the type must implement `equals()`
and `hashCode()` over its serialized fields**. Kotlin data classes do this automatically; a
Java class needs them written out (or Lombok's `@Value`/`@Data`). Java records are not
supported, because the bundled Jackson predates record support. Serialization is kept
backwards compatible so that data written by an older version can still be read after an
upgrade. See **[Troubleshooting](/docs/troubleshooting/)** if you hit a serialization error.
