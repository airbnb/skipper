# Java + Gradle + SQLite

The smallest complete Skipper application: plain Java 17, Gradle, no framework, a file-backed SQLite
store. Start here if you are new to Skipper.

```bash
./gradlew build     # compiles and runs the WorkflowTest suite (6 tests, a few seconds)
./gradlew run       # runs three orders against ./orders.db and prints what happened
```

`run` prints something like:

```
order-8aebdad8-small  -> OrderResult{FULFILLED, tracking=trk-1}
order-8aebdad8-large  -> WAITING, stage=AWAITING_APPROVAL
order-8aebdad8-large  -> OrderResult{FULFILLED, tracking=trk-2}
order-8aebdad8-hazmat -> COMPENSATION_COMPLETED
inventory: [RESERVE res-1 2 x BOOK-1, RESERVE res-2 1 x LAPTOP-9, RESERVE res-3 1 x HAZMAT-7, RELEASE res-3]
payments:  [CHARGE pay-1 alice $39, CHARGE pay-2 bob $1499, CHARGE pay-3 carol $120, REFUND pay-3]
```

Run it again and the earlier instances are still in `orders.db`. The WARN lines from
`com.airbnb.skipper` on the hazardous order are the engine logging a failure the workflow handles
by design.

## Where to look

| File | Shows |
| --- | --- |
| `OrderWorkflow.java` | `@WorkflowMethod`, `@StateField`, `waitUntil` for the approval park, `@SignalMethod`, `@QueryMethod` |
| `InventoryActions.java`, `PaymentActions.java` | `@Execute` with a matching `@Compensate` |
| `ShippingActions.java` | A per-action retry strategy and wrapping a transient failure in `RetryableError` |
| `SkipperSetup.java` | `SkipperConfig` with the SQLite factories and `SimpleInjector` bindings for the actions' `@Inject` fields |
| `Main.java` | The invocation builder with a callback handler, and polling the instance status for a parked workflow |
| `src/test/.../OrderWorkflowTest.java` | `WorkflowTest`, `@Bind` fakes, `expectWorkflowToWait`, `clock.fastForward`, `fastForwardUntilWorkflowCompletes`, waiting for `COMPENSATION_COMPLETED` |

## Things this example had to work around

- **The future does not complete while the workflow is parked.** `placeOrder(...).get()` on the large
  order would hang, then fail after `resultPollingTimeLimit`. `Main` polls the persisted status and
  reads the outcome through the `result()` query instead.
- **`skipper-testutils` declares JUnit as `compileOnly`**, so the project brings its own
  `junit-jupiter`.
- **Input and output types need `equals`/`hashCode` and a no-arg constructor.** Skipper round-trips
  every argument through JSON and compares before the first run. Java records are not supported by
  the Jackson line Skipper compiles against.
