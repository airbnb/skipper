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
