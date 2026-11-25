# skipper-metrics-prometheus

A Skipper `Metrics` implementation that registers collectors with a `PrometheusRegistry` from the
1.x Prometheus Java client ([client_java](https://github.com/prometheus/client_java),
`prometheus-metrics-core`).

```kotlin
dependencies {
    implementation("com.airbnb.skipper:skipper-metrics-prometheus:<version>")
    // plus whichever exporter serves your scrape endpoint, e.g.
    // implementation("io.prometheus:prometheus-metrics-exporter-httpserver:<client version>")
}
```

```kotlin
val config = SkipperConfig.forService("my-service").apply {
    metrics = ComponentFactory { PrometheusMetrics(PrometheusRegistry.defaultRegistry) }
}
```

## Mapping

| Skipper | Prometheus |
|---|---|
| name segments `a`, `b` | `skipper_a_b` (prefix configurable; unsafe characters become `_`) |
| tags `{k: v}` | label `k="v"` |
| counter | counter (`skipper_a_b_total` on exposition) |
| timer | histogram in seconds, `skipper_a_b_seconds`, buckets `timerBuckets` |
| histogram | histogram, buckets `histogramBuckets` |
| gauge | callback gauge, evaluated on every scrape |

Prometheus fixes the label names of a metric at registration. Skipper reports each metric with a
consistent tag set, so the first call fixes the labels; a later call with a different tag set is
mapped onto them (unknown labels dropped, missing ones empty) with a one-time warning instead of
failing the engine.

## Grafana dashboard

[`grafana/skipper-dashboard.json`](grafana/skipper-dashboard.json) is a ready-made Grafana dashboard
over these metrics: workflow and action throughput, errors and latency, scheduler backlog,
dead-letter queue and saturation, workflow store latency and errors, timers, compensation, and task
partitioning.

To import it, open **Dashboards → New → Import** in Grafana, upload the file (or paste its
contents), and pick your Prometheus data source from the **Data source** drop-down on the dashboard.
It is built for Grafana 10 or newer (tested on 13) and uses only the time series and stat panels and the
Prometheus data source. To provision
it instead, drop the file into a
[dashboard provider](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards)
directory as is.

The dashboard makes three assumptions about your setup:

1. Metrics use the default `skipper` prefix. With a custom prefix, replace `skipper_` throughout
   the file before importing.
2. Scrapes carry the standard `job` and `instance` labels. They feed the **Job** and **Instance**
   filters at the top; **Workflow** filters the workflow panels by workflow class.
3. Storage is the bundled MySQL or SQLite store and scheduler. Their metrics differ only in name
   (`skipper_mysqlWorkflowStore_*`, `skipper_sqliteWorkflowStore_*`), and the storage panels match
   both. A custom store reports under its own names and needs its own panels.

Latency panels are quantiles estimated from histogram buckets, so they are only as precise as the
`timerBuckets` passed to `PrometheusMetrics`.
