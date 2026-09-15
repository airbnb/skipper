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

Several instances of your service can share one MySQL database. Leases keep any task from being
processed twice, but by default every instance polls the whole scheduler queue and they compete
for the same rows. Enable **task partitioning** so each instance fetches only its own share:

```kotlin
val config = SkipperConfig.forService("my-service").apply {
  workflowStore = MySqlWorkflowStore.Factory()
  scheduler = MySqlScheduler.Factory()
  clusterMembershipManager = JdbcClusterMembershipManager.MySqlFactory()
  mySqlDataSource = dataSource
  clusterMemberName = System.getenv("HOSTNAME") // unique per instance; defaults to the hostname
}
```

How membership, bucket ranges, and failover work, and how to watch it in the admin UI, is covered
in **[Scaling Out & Task Partitioning](/docs/scaling-out/)**.

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
