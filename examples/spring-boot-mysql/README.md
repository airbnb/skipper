# Spring Boot + MySQL

The order workflow as a Spring Boot 4 REST service on MySQL: Spring's container is Skipper's
injector, Flyway (Spring-managed) applies Skipper's schema, the scheduler follows the application
lifecycle, and Testcontainers runs the whole stack in the test suite. Docker is required.

```bash
./gradlew build                            # boots MySQL in Docker and drives three orders over HTTP
./gradlew bootJar && docker compose up --build   # the service on :8080 and its MySQL, production-shaped
docker compose up mysql                    # MySQL only; then ./gradlew bootRun on the host
```

The Dockerfile copies the jar the host built rather than building inside the image, so the
container runs exactly what `./gradlew build` tested, including any `-PskipperVersion` override.

```bash
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"o-1","customerId":"bob","sku":"LAPTOP-9","quantity":1,"amountCents":149900}'
curl localhost:8080/orders/o-1                        # "instanceStatus":"WAITING","stage":"AWAITING_APPROVAL"
curl -X POST 'localhost:8080/orders/o-1/approve?decision=true'
curl localhost:8080/orders/o-1                        # "instanceStatus":"COMPLETED", result FULFILLED
```

## Where to look

| File | Shows |
| --- | --- |
| `skipper/SpringSkipperInjector.java` | The whole Spring adapter: `createBean` for workflows and callback handlers, `autowireBean` for actions |
| `skipper/SkipperConfiguration.java` | `SkipperRuntime` on the app's `DataSource` with the MySQL store and scheduler; a `SmartLifecycle` that starts and stops the scheduler |
| `application.yaml` | Flyway pointed at Skipper's bundled migrations with the `tablePrefix` placeholder |
| `web/OrderController.java` | Start `.runAsync().detached()`, read status and outcome by id, deliver approval as a signal |
| `InventoryActions.java` and friends | `@Autowired` fields on actions |
| `src/test/.../OrdersApplicationTest.java` | `@ServiceConnection` MySQL container, end-to-end over HTTP |
| `compose.yaml`, `Dockerfile` | The production-shaped local run |

## Things this example had to work around

- **No admin UI.** `AdminResource` is a `javax.ws.rs` resource; Spring Boot 4 is on `jakarta.ws.rs`,
  so a Jersey starter would not see its annotations. Use the Dropwizard example for the UI, or run
  a small Jersey 2 sidecar.
- **The in-memory `WorkflowTest` harness does not run on this classpath.** Spring Boot's BOM forces
  Flyway 12, and Skipper's SQLite store calls a constructor removed in Flyway 6:

  ```
  java.lang.NoSuchMethodError: org.flywaydb.core.Flyway: method 'void <init>()' not found
      at com.airbnb.skipper.internal.storage.JdbcTransactionManager$SqliteFactory$Companion.migrateSqliteSchema(JdbcTransactionManager.kt:276)
  ```

  The MySQL store never calls Flyway, so the service itself is fine. Test through MySQL, as
  `OrdersApplicationTest` does.
- **Actions use `@Autowired`, not `@Inject`.** Spring 6+ recognises `jakarta.inject.Inject` and its
  own annotations; the `javax.inject.Inject` that Skipper's `SimpleInjector` reads is ignored.
- **Skipper's migrations share Flyway's default location.** They live at `classpath:db/migration`
  inside `skipper-core`, numbered V1 to V5. A service with its own migrations in the same location
  would collide; give yours a different location and list both under `spring.flyway.locations`,
  or a different history table.
- **The workflow method is `void`.** A detached invocation requires it, so the outcome is exposed
  through a `@StateField` and a `@QueryMethod` rather than a return value.
