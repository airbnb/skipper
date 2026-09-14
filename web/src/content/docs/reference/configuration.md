---
title: Configuration
description: SkipperConfig, the SkipperRuntime providers, and per-invocation WorkflowOptions.
section: Reference
order: 33
---

See [Quickstart](/docs/quickstart/) and [Storage Backends](/docs/storage/).

## `SkipperConfig`

| Member | Description |
|---|---|
| `SkipperConfig.forService(name)` | Create a config with a unique service name. |
| `workflowStore` / `scheduler` | The storage and scheduler factories. Default to the embedded SQLite store and scheduler (`SqliteWorkflowStore.Factory()` / `SqliteScheduler.Factory()`, in `com.airbnb.skipper.internal.storage.sqlite` and `...internal.scheduler.sqlite`). Pass a file path to either for a durable on-disk database. |
| `sqliteDataSource` | Optional JDBC `DataSource` for the SQLite backend; `null` means an ephemeral in-memory database. |
| `mySqlDataSource` | JDBC `DataSource` for the MySQL adapter. |
| `clusterMembershipManager` | `ClusterMembershipManager` factory. Defaults to a single-member manager that leaves task partitioning off; set `JdbcClusterMembershipManager.MySqlFactory()` (or `.SqliteFactory(path)`) so instances sharing a store each fetch a disjoint partition of the ready tasks — see [Storage](/docs/storage/#running-multiple-instances). |
| `clusterHeartBeatInterval` | How often a cluster-aware membership manager refreshes this instance's heartbeat (default 10 s). Members silent for 60 s are dropped. |
| `clusterMemberName` | This instance's identifier in the cluster membership table; `null` (the default) uses the local hostname. Set it when several instances run on one host. |
| `gracefulShutdownTimeout` | How long `stop()` waits for in-flight tasks before forcing shutdown (default 30 s). |
| `featureGate` | `FeatureGate` factory. The default enables every key except the `DISABLE_*` kill switches; see the `FeatureGate.Keys` KDoc for what each gates. |
| `injector` | `SkipperInjector` used to instantiate your workflow, action, and callback classes; `SimpleInjector.builder()` lets you bind the collaborators actions need. |
| `defaultRetryStrategy` | Default retry strategy for actions that don't specify one. |
| `defaultCheckpointMode` | When action checkpoints are flushed (eventual vs immediate). |
| `exceptionClassifier` | Global `ExceptionClassifier` factory. |
| `metrics` | `Metrics` implementation factory (no-op by default). |
| `tenant` | Namespace key for this service's workflows, tasks, and cluster rows (default `"default"`). |
| `shouldIsolateTenant` | Predicate `(rawTenant) -> Boolean`; return `true` to append a stable per-config suffix to `tenant`, isolating deployments that share one store. |

## Tenant isolation

Every workflow, scheduled task, and cluster-membership row Skipper writes is namespaced by
`tenant` (default `"default"`). When several deployments share one backing store — for example,
multiple developers running ephemeral instances against the same database — give each its own
namespace by setting `shouldIsolateTenant`. When the predicate returns `true`, Skipper appends a
stable, unique per-config suffix to `tenant` so the deployments don't collide:

```kotlin
config.shouldIsolateTenant = { isDevEnvironment }
```

```java
config.setShouldIsolateTenant(rawTenant -> isDevEnvironment);
```

The predicate receives the raw (unsuffixed) tenant and is evaluated on every read, so it always
reflects the current value. The suffix is generated once per config instance and then stays
stable, so reads are consistent for the life of the config.

## `SkipperRuntime`

| Member | Description |
|---|---|
| `SkipperRuntime(config)` | Wires the engine from a config (uses the config's injector — no Guice required). |
| `runtime.workflowFactory.get()` | The `IWorkflowFactory` used to start workflows. |
| `runtime.workflowsService.get()` | Bulk instance management. |
| `runtime.skipperSchedulerManager.get()` | The scheduler — call `start()` to drive workflows. |
| `runtime.adminResource.get()` | The admin UI JAX-RS resource. |

## `WorkflowOptions`

Per-invocation settings passed to the builder's `.workflowOptions(...)`:
`executionTimeout` (max time to completion) and `allowQueryOnNonExistentWorkflow`.
