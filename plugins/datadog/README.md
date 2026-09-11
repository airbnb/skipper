# skipper-metrics-datadog

A Skipper `Metrics` implementation that reports through a DogStatsD `StatsDClient`
([java-dogstatsd-client](https://github.com/DataDog/java-dogstatsd-client)).

```kotlin
dependencies {
    implementation("com.airbnb.skipper:skipper-metrics-datadog:<version>")
}
```

```kotlin
val statsd = NonBlockingStatsDClientBuilder().hostname("localhost").port(8125).build()
val config = SkipperConfig.forService("my-service").apply {
    metrics = ComponentFactory { DatadogMetrics(statsd) }
}
```

## Mapping

| Skipper | Datadog |
|---|---|
| name segments `a`, `b` | `skipper.a.b` (prefix configurable) |
| tags `{k: v}` | `k:v` |
| counter | `count` |
| timer | `distribution`, milliseconds (or `histogram`, via `timerMode`) |
| histogram | `distribution` (or `histogram`, via `histogramMode`) |
| gauge | `gauge`, sampled every 10 s by a daemon thread (`gaugeInterval`) |

`DatadogMetrics` is `AutoCloseable`: closing it stops the gauge sampler. It never closes the
`StatsDClient`, which you own and may share with the rest of the service.
