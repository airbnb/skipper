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
partitioning. It is built for Grafana 10 or newer (tested on 13) and uses only the time series and
stat panels and the Prometheus data source.

To import it:

1. In Grafana, open **Dashboards**, then **New → Import**.
2. Upload `skipper-dashboard.json` (or paste its contents) and click **Load**, then **Import**.
3. Pick your Prometheus data source in the **Data source** drop-down at the top of the dashboard.

To provision it instead, copy the file unchanged into a directory served by a Grafana
[dashboard provider](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards).

It expects the default `skipper` metric prefix, the standard `job` and `instance` scrape labels,
and the bundled MySQL or SQLite store and scheduler. The
[observability guide](https://skipper.airbnb.tech/docs/observability/#grafana-dashboard) walks
through each row, suggests alert rules, and covers what to change for a custom prefix or store.
