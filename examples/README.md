# Examples

Complete, standalone projects that run Skipper on the JVM stacks people actually ship. Each directory
is its own build against the **published** Skipper artifacts on Maven Central, the way a service
would consume them, so what you see is what an adopter gets. CircleCI builds, tests and runs every
one of them on each push (the `examples` job), so they cannot quietly rot.

All five build the same small application, an order-fulfilment workflow, so you can diff two
directories and see only what the stack changes:

- reserve stock, charge the customer, hand the parcel to a carrier, as three checkpointed actions;
- orders over a threshold park until a signal approves them or a day passes;
- a carrier outage is a retryable error with its own backoff;
- a refused shipment fails the workflow and compensates the charge and the reservation in reverse.

| Directory | Stack | Store | What it adds |
| --- | --- | --- | --- |
| [`java-gradle-sqlite/`](java-gradle-sqlite/) | Java 17, Gradle | file-backed SQLite | The onboarding path: wiring, `SimpleInjector`, callbacks, and a `WorkflowTest` suite. Start here. |
| [`java-maven-sqlite/`](java-maven-sqlite/) | Java 17, Maven | file-backed SQLite | The same sources under a `pom.xml`. |
| [`kotlin-gradle-sqlite/`](kotlin-gradle-sqlite/) | Kotlin 2.4, Gradle | file-backed SQLite | `suspend` workflows and actions, data classes, the AllOpen plugin, and a state machine on `skipper-state-machine`. |
| [`spring-boot-mysql/`](spring-boot-mysql/) | Spring Boot 4, Java 17 | MySQL | A REST service: Spring as Skipper's injector, Flyway applying Skipper's schema, Testcontainers test, Docker Compose. |
| [`dropwizard-mysql/`](dropwizard-mysql/) | Dropwizard 3, Guice 6, Java 17 | MySQL (or SQLite) | The admin UI mounted on JAX-RS, Guice as Skipper's injector, a smoke test over HTTP. |

## Running one

Every directory has a README with the exact commands. The short version:

```bash
cd examples/java-gradle-sqlite && ./gradlew build run
```

The SQLite examples need nothing but a JDK (the Gradle builds download one if needed). The two
MySQL examples need Docker: Spring Boot's test starts MySQL through Testcontainers and its
`compose.yaml` runs the whole thing; the Dropwizard service also runs on SQLite with
`config-sqlite.yml` when you only want to click around the admin UI.

## What the examples teach that the guides do not

Building these against the published artifacts surfaced a few behaviours worth knowing before you
pick a stack. Each example's README has the details and the exact error text.

- **The admin UI is a `javax.ws.rs` resource.** It mounts on Dropwizard 3 and any Jersey 2 stack. It
  does not mount on Spring Boot 4, Dropwizard 4+, or anything else on the `jakarta.ws.rs` namespace.
- **Skipper's SQLite store needs Flyway 5.** It calls a Flyway constructor removed in Flyway 6. On a
  Spring Boot classpath, where the BOM forces Flyway 12, the in-memory `WorkflowTest` harness fails
  with `NoSuchMethodError`; the MySQL store is unaffected because it never calls Flyway. Dropwizard
  pins Flyway 5.2.4 so both work.
- **Actions are field-injected, never constructor-injected.** Skipper creates each `Actions` class as
  a recording proxy through its no-arg constructor and then asks the injector to fill its members.
  Workflows and callback handlers *can* take constructor arguments through Spring or Guice.
- **Guice needs bindings for Skipper's internals.** `Workflow` and `Actions` carry `@Inject` fields
  for a few engine components that `SimpleInjector` ignores and the engine fills itself. Guice does
  not ignore them; the Dropwizard example's `SkipperInternalsModule` hands it the runtime's instances.
- **A suspend or blocking call on a workflow that parks does not wait forever.** It gives up after
  `resultPollingTimeLimit` (30 s on Skipper's clock) with a `TimeoutException`. Services start
  long-running instances `.runAsync().detached()` and read the outcome through a `@QueryMethod`.
- **Skipper's MySQL migrations are templated.** They need the Flyway placeholder `tablePrefix`
  (`skipper_` by default) or they fail to parse. Both MySQL examples set it.

## Keeping them current

Each build pins `skipperVersion` (or `skipper.version` in the POM) in one place; bump all five when
a release lands. The CI job runs on the machine executor because of Docker, so it is the slowest
job in the workflow; that is the price of examples that provably work.
