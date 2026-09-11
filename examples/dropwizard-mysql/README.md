# Dropwizard + Guice + MySQL

The order workflow as a Dropwizard 3 service with Guice as Skipper's injector and the Skipper admin
UI mounted on Jersey. MySQL for production shape, or SQLite when you just want to look.

```bash
./gradlew build                                   # WorkflowTest suite on the in-memory store
./gradlew run --args="server config-sqlite.yml"   # no database needed
./gradlew run --args="server config.yml"          # MySQL at $MYSQL_URL (default localhost:3306/orders)
scripts/smoke.sh config-sqlite.yml                # boots the service and checks the API and admin UI
```

Then open <http://localhost:8080/skipper/admin/> for the dashboard, the instance list, each
instance's checkpoints and timers, and the persisted approval signals. The REST API is the same
shape as the Spring Boot example:

```bash
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"o-1","customerId":"bob","sku":"LAPTOP-9","quantity":1,"amountCents":149900}'
curl -X POST 'localhost:8080/orders/o-1/approve?decision=true'
curl localhost:8080/orders/o-1
```

For MySQL without an existing server:

```bash
docker run -d --name orders-mysql -p 3306:3306 -e MYSQL_DATABASE=orders \
  -e MYSQL_USER=orders -e MYSQL_PASSWORD=orders -e MYSQL_ROOT_PASSWORD=root mysql:8.4
```

## Where to look

| File | Shows |
| --- | --- |
| `skipper/GuiceSkipperInjector.java` | The whole Guice adapter: `getInstance` and `injectMembers` |
| `skipper/SkipperInternalsModule.java` | The bindings Guice needs for Skipper's own `@Inject` fields, and why |
| `skipper/SkipperModule.java` | `SkipperRuntime` as a Guice singleton, on MySQL or SQLite per the YAML |
| `skipper/SkipperManaged.java` | Scheduler start and stop on the Dropwizard lifecycle |
| `OrdersApplication.java` | Flyway applying Skipper's migrations with the `tablePrefix` placeholder; registering `AdminResource` with Jersey |
| `web/OrderResource.java` | JAX-RS resource that starts instances `.runAsync().detached()`, reads them back, signals approval |
| `scripts/smoke.sh` | What CI checks: the REST flow and the admin UI's HTML and JSON endpoints |

## Things this example had to work around

- **Dropwizard 3, not 4 or 5.** `AdminResource` is written against `javax.ws.rs`; Dropwizard 3 is the
  last line on that namespace.
- **Guice needs Skipper's internal bindings.** `Workflow` and `Actions` declare `@Inject` fields for
  `ActionExecutor`, `SkipperEngine`, `ContextPropagator` and a `@Named("DefaultRetryStrategy")
  RetryStrategy`. `SimpleInjector` skips them and the engine fills them itself; Guice errors out and,
  worse, tries to construct the internal types, producing fifteen missing-binding errors. Without
  `SkipperInternalsModule` every request fails with `Guice configuration errors: No implementation
  for CheckpointMode annotated with @Named("DefaultCheckpointMode") was bound` and so on.
- **Flyway is pinned to 5.2.4.** `skipper-core` brings 5.0.7, whose API predates `Flyway.configure()`
  (the call the Storage guide shows). 5.2.4 has both that API and the constructor Skipper's SQLite
  store still uses, and supports MySQL 8. Anything newer breaks the SQLite store and `WorkflowTest`.
- **Applying migrations on every start is idempotent**, so the example does it in `run()`. The guide's
  advice to run them once ahead of the first deploy still stands for a fleet.
- **Jersey logs two warnings about `AdminResource` on startup** (empty path annotation; does not
  implement any provider interfaces). Cosmetic; the UI works.
