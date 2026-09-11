# Java + Maven + SQLite

The same application as [`../java-gradle-sqlite`](../java-gradle-sqlite/), byte for byte in `src/`,
under a Maven build. Diff the two directories and only the build files differ.

```bash
./mvnw verify                 # compiles and runs the WorkflowTest suite
./mvnw compile exec:java      # runs three orders against ./orders.db
```

`mvnw` is the script-only Maven Wrapper: it downloads Maven 3.9 on first use, so only a JDK 17+ is
needed. A locally installed `mvn` works the same.

The POM pins `skipper.version` in one property. Points specific to Maven:

- `skipper-testutils` is a test-scoped dependency and marks JUnit optional, so the POM declares
  `junit-jupiter` itself; Surefire 3.x picks it up without configuration.
- The `exec-maven-plugin` entry exists only so `mvn compile exec:java` runs `Main`; a real service
  would package a jar instead.

See the Gradle example's README for what the code demonstrates and the behaviours it works around.
