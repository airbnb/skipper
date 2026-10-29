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
| `clusterMembershipManager` | `ClusterMembershipManager` factory. Defaults to a single-member manager that leaves task partitioning off; set `JdbcClusterMembershipManager.MySqlFactory()` (or `.SqliteFactory(path)`) so instances sharing a store each fetch a disjoint partition of the ready tasks — see [Scaling Out & Task Partitioning](/docs/scaling-out/). |
| `clusterHeartBeatInterval` | How often a cluster-aware membership manager refreshes this instance's heartbeat (default 10 s). Members silent for 60 s are dropped. |
| `clusterMemberName` | This instance's identifier in the cluster membership table; `null` (the default) uses the local hostname. Set it when several instances run on one host. |
| `schedulerTaskLeaseDuration` | How long a task is leased to the instance running it (default 8 min). If the lease runs out, the task is run again. See [Long-running workflow runs](#long-running-workflow-runs). |
| `schedulerTaskExecutionTimeout` | How long the scheduler waits for one task, such as a workflow reaching its next wait, before it stops waiting and stops renewing the task's lease (default 5 min, counted in whole seconds). Set it above the longest a workflow runs between waits. See [Long-running workflow runs](#long-running-workflow-runs). |
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

## Long-running workflow runs

A workflow runs as one scheduler task from one wait to the next, including the actions it calls on
the way. Timers and compensations also run as scheduler tasks, so the settings below apply to them
too. While a task runs, the instance holds a lease on it for `schedulerTaskLeaseDuration`. With the
`automatic_lease_renewal` feature gate on (the default), the lease is renewed shortly before it runs
out, for as long as the scheduler is waiting on the task.

The scheduler waits at most `schedulerTaskExecutionTimeout`. After that it stops waiting and stops
renewing the lease, but the run is not cancelled. When the lease runs out, up to
`schedulerTaskLeaseDuration` later, the task runs again. This happens even if the first run has
finished by then. If it has not, the two runs overlap. So:

- set `schedulerTaskExecutionTimeout` above the longest a workflow runs between waits;
- keep `automatic_lease_renewal` on: without it the lease is never renewed, and any run longer than
  `schedulerTaskLeaseDuration` is run again whatever the timeout.

```kotlin
config.schedulerTaskExecutionTimeout = Duration.ofMinutes(30)
```

```java
config.setSchedulerTaskExecutionTimeout(Duration.ofMinutes(30));
```

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
