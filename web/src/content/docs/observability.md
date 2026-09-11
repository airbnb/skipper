---
title: Observability & Admin UI
description: Enable the built-in admin UI to inspect and recover workflows, and wire up metrics to Datadog, Prometheus or your own backend to monitor the engine.
section: Guides
order: 19
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
- Inspect a single instance's status and history — `GET /skipper/admin/workflows/{id}`.
- Cancel a running instance — `POST /skipper/admin/workflows/{id}/cancel`.
- Review workflows that exhausted their retries and the scheduler **dead-letter queue**, and
  requeue stuck tasks.
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
runtime. Two backends ship as separate artifacts, so `skipper-core` never drags a metrics client
onto your classpath; or implement `Metrics` yourself against any other registry.

Every metric is named as a dotted path of a component and an operation
(`mysqlWorkflowStore.persistSignal`, `schedulerManager.handledTasks`) and may carry tags such as
`result` and `error`. Each backend maps those names onto its own conventions.

### Datadog

`skipper-metrics-datadog` reports through a DogStatsD client. Names become
`skipper.<component>.<operation>`, tags become `key:value`, counters are sent as `count`, timers
(in milliseconds) and histograms as `distribution`, and gauges are sampled every 10 seconds on a
daemon thread. You construct and own the `StatsDClient`; call `close()` on the `DatadogMetrics`
at shutdown to stop the gauge sampler.

```kotlin
// build.gradle.kts: implementation("com.airbnb.skipper:skipper-metrics-datadog:<version>")
val statsd = NonBlockingStatsDClientBuilder().hostname("localhost").port(8125).build()
config.metrics = ComponentFactory { DatadogMetrics(statsd) }
```

```java
config.setMetrics(cfg -> new DatadogMetrics(statsd));
```

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
