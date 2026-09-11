# Kotlin + Gradle + SQLite

The order workflow in idiomatic Kotlin, plus a product-return flow written with the
`skipper-state-machine` DSL. Kotlin 2.4, Gradle, a file-backed SQLite store.

```bash
./gradlew build     # 9 tests: the workflow and the state machine, each on an in-memory runtime
./gradlew run       # four scenarios against ./orders.db
```

```
order-f0a3377f-small  -> OrderResult(status=FULFILLED, ..., trackingId=trk-1)
order-f0a3377f-large  -> WAITING, stage=AWAITING_APPROVAL
order-f0a3377f-large  -> OrderResult(status=FULFILLED, ..., trackingId=trk-2)
order-f0a3377f-hazmat -> COMPENSATION_COMPLETED
ReturnStateMachine-order-f0a3377f-small -> REFUNDED
```

## Where to look

| File | Shows |
| --- | --- |
| `build.gradle.kts` | The AllOpen plugin on `com.airbnb.skipper.SkipperOpen`, and why `kotlin-reflect` is pinned to the compiler version |
| `OrderWorkflow.kt` | `suspend fun` workflow methods returning the value directly, no `returnType` on the annotation |
| `Actions.kt` | `@Inject lateinit var` collaborators, `suspend` actions with `@Compensate`, a named retry strategy |
| `Model.kt` | Data classes as workflow input and output: equality for free |
| `ReturnStateMachine.kt` | States as an enum, events as a sealed hierarchy, `on<Event>`, `timeout`, `terminal()`, the `workflowId` helper |
| `Main.kt` | Starting a parked workflow in its own coroutine, signalling it, driving the state machine with events |
| `src/test/kotlin/...` | `WorkflowTest` from Kotlin with `runBlocking`, and the same harness driving a state machine |

## Things this example had to work around

- **A suspended caller does not wait indefinitely for a parked workflow.** When the instance parks
  (`waitUntil`, a retry timer), the call keeps polling storage only up to `resultPollingTimeLimit`,
  30 s on Skipper's clock, then throws `TimeoutException: unable to get workflow result within the
  time limit`. Tests that jump the clock past that read the outcome through a `@QueryMethod`; see
  the `start(...)` helper in `OrderWorkflowTest.kt`. A real service would start such instances
  `.runAsync().detached()` and never await them.
- **`@Bind` and `@Inject` target fields.** Kotlin puts them on the backing field automatically, so
  `@Bind(to = X::class) val fake = ...` and `@Inject lateinit var dep: X` both work as written.
- **`skipper-state-machine` pulls Guice, Dropwizard Views and FreeMarker onto the runtime
  classpath** for its admin page. Nothing here uses them, but they are there.
