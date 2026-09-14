# Skipper plugins

Optional modules layered on `skipper-core`. Each is its own Maven Central artifact so adopters take
only the integration they need and core stays free of backend-specific dependencies.

| Directory | Artifact | What it is |
|---|---|---|
| [`prometheus/`](prometheus) | `com.airbnb.skipper:skipper-metrics-prometheus` | `Metrics` implementation registering collectors with the Prometheus Java client |

Every plugin builds and is published with the root Gradle build (`./gradlew build` covers them), on
the same version as `skipper-core`. Usage is documented in the
[observability guide](https://skipper.airbnb.tech/docs/observability/#metrics).
