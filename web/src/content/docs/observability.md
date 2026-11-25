---
title: Observability & Admin UI
description: Enable the built-in admin UI to inspect and recover workflows, and wire up metrics to Prometheus or your own backend to monitor the engine.
section: Guides
order: 20
---

Skipper ships with a built-in **admin UI** for inspecting and recovering workflow instances,
plus a pluggable metrics hook for monitoring the engine itself.

## Enabling the Admin UI

The admin UI is a standard JAX-RS resource — `AdminResource`, served under the path
`/skipper/admin`. Obtain it from your `SkipperRuntime` (see the
**[Quickstart](/docs/quickstart/)**) and register it with your application's JAX-RS / HTTP
server alongside your other resources:

```kotlin
val runtime = SkipperRuntime(config)

// AdminResource is a JAX-RS resource — register it with your server's JAX-RS runtime
// (Jersey, Dropwizard, RESTEasy, …).
val admin = runtime.adminResource.get()
jaxrs.register(admin)
```

```java
SkipperRuntime runtime = new SkipperRuntime(config);

// AdminResource is a JAX-RS resource — register it with your server's JAX-RS runtime
// (Jersey, Dropwizard, RESTEasy, …).
AdminResource admin = runtime.getAdminResource().get();
jaxrs.register(admin);
```

Because it is a plain JAX-RS resource, it drops into any JAX-RS-compatible stack — there is
nothing Skipper-specific about how you mount it. Its endpoints serialize their own JSON, so the
host needs no JSON provider (and no particular `ObjectMapper` configuration) for the UI to work.

Then open the UI in a browser:

```
http://<your-service-host>/skipper/admin/
```

For a service running remotely, forward its port to your machine first, then open the path
locally.

## What the Admin UI gives you

The UI — and the JSON API behind it — lets you:

- View dashboard stats and search workflow instances by type and status.
- Inspect a single instance's status and history — `GET /skipper/admin/workflows/{id}`. Its
  **timeline** lays out everything that happened on one axis, like a browser's network
  waterfall: each action and every retry attempt, the backoff between attempts and when the
  next one is due, timer waits, signals, child workflows, and the workflow timeout. Idle
  stretches are compressed by default so short actions and long waits are both readable; a
  linear overview strip keeps the real proportions.
- Cancel a running instance — `POST /skipper/admin/workflows/{id}/cancel`.
- Review workflows that exhausted their retries and the scheduler **dead-letter queue**, and
  requeue stuck tasks.
- See the scheduler **cluster**: the live members and the range of task-ID buckets each one
  fetches from, and whether [task partitioning](/docs/scaling-out/) is
  active on this instance — `GET /skipper/admin/cluster`.
- List and **replay durable [signals](/docs/signals-and-queries/)**:

```
GET  /skipper/admin/workflows/{id}/signals
POST /skipper/admin/workflows/{id}/signals/{signalId}/replay
```

These endpoints back the same operations available programmatically through
**[`WorkflowsService`](/docs/instance-management/)**.

## Metrics

Skipper reports engine metrics through a pluggable `Metrics` interface. It is a **no-op by
default**, so to collect metrics, supply an implementation on the config before creating the
runtime. A Prometheus backend ships as a separate artifact, so `skipper-core` never drags a metrics
client onto your classpath; or implement `Metrics` yourself against any other registry.

Every metric is named as a dotted path of a component and an operation
(`mysqlWorkflowStore.persistSignal`, `schedulerManager.handledTasks`) and may carry tags such as
`result` and `error`.

### Prometheus

`skipper-metrics-prometheus` registers collectors with a `PrometheusRegistry` from the 1.x
`prometheus-metrics-core` client. Names become `skipper_<component>_<operation>`, tags become
labels, counters get the usual `_total` suffix, timers are histograms in seconds
(`..._seconds`), and gauges read their value on every scrape. Expose the registry through the
scrape endpoint you already have, for example `prometheus-metrics-exporter-httpserver`.

```kotlin
// build.gradle.kts: implementation("com.airbnb.skipper:skipper-metrics-prometheus:<version>")
config.metrics = ComponentFactory { PrometheusMetrics(PrometheusRegistry.defaultRegistry) }
```

```java
config.setMetrics(cfg -> new PrometheusMetrics(PrometheusRegistry.defaultRegistry));
```

Histogram bucket boundaries for timers and for value histograms are constructor parameters if the
defaults (1 ms to 60 s, and powers of four from 1 to about 4 million) do not fit your workload.

### Grafana dashboard

A ready-made Grafana dashboard for these metrics ships next to the plugin:
[`skipper-dashboard.json`](https://github.com/airbnb/skipper/blob/main/plugins/prometheus/grafana/skipper-dashboard.json).
It is built for Grafana 10 or newer and uses only the time series and stat panels and the
Prometheus data source, so there is nothing else to install.

#### Before you import

The dashboard reads what Prometheus has scraped from your service, so two things must already be
in place:

1. Your service installs `PrometheusMetrics` as shown above and serves the registry on a scrape
   endpoint.
2. Prometheus scrapes that endpoint, and Grafana has that Prometheus as a data source.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: my-service
    static_configs:
      - targets: ["my-service-host:9400"]
```

The `job` and `instance` labels Prometheus attaches to every scrape are what the dashboard's
**Job** and **Instance** filters select on.

#### Import through the Grafana UI

1. Download
   [`skipper-dashboard.json`](https://raw.githubusercontent.com/airbnb/skipper/main/plugins/prometheus/grafana/skipper-dashboard.json).
2. In Grafana, open **Dashboards**, then **New → Import**.
3. Upload the file, or paste its contents into the JSON box, and click **Load**.
4. Choose a folder if you want one and click **Import**.
5. On the dashboard, pick your Prometheus data source in the **Data source** drop-down at the top
   left. With a single Prometheus data source it is selected for you.

#### Provision it as code

To have Grafana load the dashboard from disk instead, point a
[dashboard provider](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards)
at a directory and copy the file into it unchanged:

```yaml
# <grafana provisioning dir>/dashboards/skipper.yaml
apiVersion: 1
providers:
  - name: skipper
    type: file
    options:
      path: /var/lib/grafana/dashboards/skipper
```

The dashboard chooses its data source through a dashboard variable, so the file has no data source
ID to rewrite for either route.

#### Using the dashboard

The filters at the top narrow every panel: **Job** and **Instance** select which processes to look
at, and **Workflow** limits the workflow panels to chosen workflow classes. Each panel has a
description behind the **i** icon in its title saying what it counts.

| Row | What it answers |
|---|---|
| Overview | Is the engine healthy right now: starts, completions, failures, action error ratio, task backlog, dead-letter queue, scheduler saturation, and how many instances are reporting. |
| Workflows | Which workflow classes are starting, completing, failing, retrying or timing out, and how many signals are sent. |
| Actions | Action throughput, which actions fail, and how long each takes (p50, p95, p99). |
| Scheduler | Whether the fleet keeps up: backlog, task throughput, outcomes of handled tasks, per-instance concurrency against the limit, lease renewals, and latency of scheduler calls to the task table. |
| Workflow store | Rate, errors and p99 latency of each store operation, and the size of persisted payloads. |
| Timers, compensation and callbacks | Timers firing, compensation outcomes, and callback handlers that threw. |
| Cluster and task partitioning | The task-ID range each member owns and the health of membership heartbeats. Collapsed; relevant with [task partitioning](/docs/scaling-out/). |
| Cancellation, versioning and shutdown | In-flight cancellations, version gate violations and unexpected engine errors. Collapsed. |

Start with the Overview row. A growing **Task backlog** means tasks are due faster than the fleet
handles them: check **Saturation** and the Scheduler row. Anything above zero in **Dead letters**
is a workflow that stopped making progress until it is requeued from the admin UI. A rising
**Failures** or **Action errors** value points you to the Workflows and Actions rows, which break
the same numbers down by workflow class and by action.

The same queries work as alert rules. Two that are worth having from day one:

```promql
# A task is in the dead-letter queue
max(skipper_schedulerManager_failedTasksCount) > 0

# The backlog has been above 1000 due tasks
max(skipper_schedulerManager_backloggedTasksCount) > 1000
```

#### If panels are empty or need adapting

- **Every panel says "No data".** Check that the **Data source** drop-down points at the Prometheus
  that scrapes your service, and that `skipper_schedulerManager_maxTasks` returns a series there.
  The **Job** and **Instance** filters are filled from that metric.
- **Some panels are empty.** A series appears only once the engine has reported it, so panels for
  errors, timers, compensation or partitioning stay empty until that happens for the first time.
- **You changed the metric prefix.** The dashboard expects the default `skipper` prefix. Replace
  `skipper_` throughout the file with your prefix before importing.
- **You use your own store or scheduler.** The storage panels match the bundled MySQL and SQLite
  implementations (`skipper_mysqlWorkflowStore_*`, `skipper_sqliteWorkflowStore_*` and the
  matching scheduler names). A custom implementation reports under its own names and needs its own
  panels.
- **Latency looks coarse.** Latency panels are quantiles estimated from histogram buckets, so they
  are only as precise as the `timerBuckets` passed to `PrometheusMetrics`.

### Your own backend

Implement `Metrics` and install it the same way:

```kotlin
config.metrics = ComponentFactory { MyMetrics() }
```

```java
config.setMetrics(cfg -> new MyMetrics());
```

Your implementation receives metrics covering actions, the scheduler, storage, and overall
workflow throughput — forward them to your existing pipeline and dashboards to track:

- Workflow counts by status (running, waiting, completed, errored).
- Action execution and failure rates.
- Scheduler and storage latency and error rates.

Watching workflow status counts and the size of the dead-letter queue is usually the fastest
way to spot a problem in production.
