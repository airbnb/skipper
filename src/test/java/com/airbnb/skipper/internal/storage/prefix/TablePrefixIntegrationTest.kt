package com.airbnb.skipper.internal.storage.prefix

import com.airbnb.skipper.Actions
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.TimerCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.MySqlTestSetupExtension
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.control.Either
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Cross-backend, end-to-end proof that [SkipperConfig.tablePrefix] is applied consistently across
 * the templated Flyway DDL **and** the runtime store/scheduler queries, for both storage backends
 * and both the default (`skipper_`) prefix and the legacy `tempo_` compatibility override.
 *
 * The matrix is 2 backends (real in-memory SQLite, real MySQL) × 2 prefixes
 * (`skipper_`, `tempo_`) = 4 combinations. Each combination:
 *  1. bootstraps a fresh schema with the prefix-aware setup extension **constructed with that
 *     prefix**;
 *  2. builds the store + scheduler from a [TestRuntime] whose [SkipperConfig.tablePrefix] is that
 *     same prefix (so `.Factory().create(config)` threads the prefix into every query);
 *  3. exercises representative CRUD across all five Skipper entities (workflow instance, action
 *     checkpoint, timer, scheduler task, persisted signal) and asserts each round-trips;
 *  4. asserts against the DB catalog that only the `<prefix>*` tables exist and the other prefix's
 *     tables do NOT — the load-bearing negative assertion that the prefix is genuinely applied.
 *
 * ### Why the extensions are instantiated directly instead of via `@ExtendWith`
 * [SqliteTestSetupExtension] / [MySqlTestSetupExtension] extend
 * [com.airbnb.skipper.testutils.OssSetupExtension], whose
 * `beforeAll` runs `setup()` exactly **once per extension class per JVM** and stores the created
 * `DataSource` in a **shared static** (`DB_DATA_SOURCE`). Registering the same extension class
 * twice with different prefixes would therefore bootstrap only the first prefix. Instead, each
 * combination constructs the extension with its prefix, calls `setup()`, and immediately captures
 * the freshly-created `DataSource`. Because every combination gets its own fresh database that
 * contains *only* its prefix's tables, the negative assertion is meaningful. Each `@Test` is fully
 * self-contained (bootstrap → capture → build → CRUD → catalog), so method execution order does
 * not matter.
 */
class TablePrefixIntegrationTest {
    @Test
    fun sqliteDefaultPrefix_createsSkipperTablesAndRoundTripsAllEntities() {
        verifySqlite(prefix = SkipperConfig.DEFAULT_TABLE_PREFIX, otherPrefix = TEMPO_PREFIX)
    }

    @Test
    fun sqliteTempoOverride_createsTempoTablesAndRoundTripsAllEntities() {
        verifySqlite(prefix = TEMPO_PREFIX, otherPrefix = SkipperConfig.DEFAULT_TABLE_PREFIX)
    }

    @Test
    fun mysqlDefaultPrefix_createsSkipperTablesAndRoundTripsAllEntities() {
        verifyMysql(prefix = SkipperConfig.DEFAULT_TABLE_PREFIX, otherPrefix = TEMPO_PREFIX)
    }

    @Test
    fun mysqlTempoOverride_createsTempoTablesAndRoundTripsAllEntities() {
        verifyMysql(prefix = TEMPO_PREFIX, otherPrefix = SkipperConfig.DEFAULT_TABLE_PREFIX)
    }

    private fun verifySqlite(
        prefix: String,
        otherPrefix: String
    ) {
        val extension = SqliteTestSetupExtension(prefix)
        extension.setup()
        val dataSource = SqliteTestSetupExtension.DB_DATA_SOURCE!!

        val deps = TestRuntime()
        deps.config.tablePrefix = prefix
        deps.config.sqliteDataSource = dataSource
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()

        exerciseAllFiveEntities(deps.workflowStore, deps.scheduler)
        assertOnlyPrefixedTablesExist(dataSource, SQLITE_LIST_TABLES_SQL, prefix, otherPrefix)
    }

    private fun verifyMysql(
        prefix: String,
        otherPrefix: String
    ) {
        val extension = MySqlTestSetupExtension(prefix)
        extension.setup()
        val dataSource = MySqlTestSetupExtension.DB_DATA_SOURCE!!

        val deps = TestRuntime()
        deps.config.tablePrefix = prefix
        deps.config.mySqlDataSource = dataSource
        deps.config.workflowStore = MySqlWorkflowStore.Factory()
        deps.config.scheduler = MySqlScheduler.Factory()

        exerciseAllFiveEntities(deps.workflowStore, deps.scheduler)
        assertOnlyPrefixedTablesExist(dataSource, MYSQL_LIST_TABLES_SQL, prefix, otherPrefix)
    }

    /**
     * Creates and reads back one row of every Skipper entity type through the public store /
     * scheduler APIs. Every operation resolves to `<prefix>*` tables via the configured prefix, so
     * a successful round-trip is itself evidence the queries target the prefixed schema.
     */
    private fun exerciseAllFiveEntities(
        store: WorkflowStore,
        scheduler: Scheduler
    ) {
        val workflowId = "prefix-workflow"

        // (1) Workflow instance.
        val instance =
            store.createWorkflow(
                WorkflowCreationRequest.builder()
                    .workflowClass(Workflow::class.java)
                    .workflowId(workflowId)
                    .workflowMethod("execute")
                    .input("input")
                    .requestContext(TestRequestContext.builder().userId("user-1").build())
                    .extraRequestData(TestUtils.EXTRA_REQUEST_DATA)
                    .build()
            )
        assertThat(instance.workflowId).isEqualTo(workflowId)
        assertThat(store.getWorkflow(workflowId).get().workflowId).isEqualTo(workflowId)

        // (2) Action checkpoint.
        store.storeActionCheckpoints(
            workflowId,
            io.vavr.collection.List.of(
                ActionCheckpoint.builder()
                    .checkpointTag(
                        CheckpointTag.builder()
                            .workflowId(workflowId)
                            .actionClass(Actions::class.java)
                            .actionMethod("doAction")
                            .iteration(1L)
                            .build()
                    )
                    .executionStartTime(Instant.EPOCH)
                    .result(Either.right("checkpoint-result"))
                    .isTransient(false)
                    .build()
            )
        )
        assertThat(store.getActionCheckpoints(workflowId).size()).isEqualTo(1)

        // (3) Timer.
        store.createTimer(
            TimerCreationRequest.builder()
                .workflowId(workflowId)
                .timerId("timer-1")
                .duration(Duration.ofSeconds(10))
                .expiresAt(Instant.EPOCH.plusSeconds(10))
                .build()
        )
        assertThat(store.getTimer(workflowId, "timer-1").get().id).isEqualTo("timer-1")

        // (4) Persisted signal.
        val persistedSignal =
            store.persistSignal(
                PersistedSignal(
                    workflowId,
                    "doSignal",
                    PersistedSignal.Status.PENDING,
                    "signal-input",
                    TestRequestContext.builder().userId("user-1").build()
                )
            )
        assertThat(persistedSignal.id).isNotNull()
        assertThat(store.getPersistedSignal(workflowId, persistedSignal.id!!).get().signalMethod)
            .isEqualTo("doSignal")

        // (5) Scheduler task.
        val task =
            scheduler.schedule(
                ScheduleRequest.builder<String>()
                    .id("task-1")
                    .payload("task-payload")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("task-1")
                    .runAfter(null)
                    .build()
            )
        assertThat(task.id).isEqualTo("task-1")
        assertThat(scheduler.getTask<String>("task-1").get().payload).isEqualTo("task-payload")
    }

    /**
     * Asserts that the schema reached through [dataSource] contains the five expected `<prefix>*`
     * tables and none of the five `<otherPrefix>*` tables. This is what proves the prefix is really
     * applied to the DDL (rather than the CRUD merely succeeding against some default schema).
     */
    private fun assertOnlyPrefixedTablesExist(
        dataSource: DataSource,
        listTablesSql: String,
        prefix: String,
        otherPrefix: String
    ) {
        val tableNames = mutableSetOf<String>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(listTablesSql).use { rs ->
                    while (rs.next()) {
                        // Normalize case so the assertion is robust to MySQL platforms that fold
                        // identifier case; the migration DDL and expected names are all lowercase.
                        tableNames.add(rs.getString(1).lowercase())
                    }
                }
            }
        }

        assertThat(tableNames)
            .contains(
                prefix + "workflow_instances",
                prefix + "action_checkpoints",
                prefix + "timers",
                prefix + "scheduler_tasks",
                prefix + "persisted_signals"
            )
        assertThat(tableNames)
            .doesNotContain(
                otherPrefix + "workflow_instances",
                otherPrefix + "action_checkpoints",
                otherPrefix + "timers",
                otherPrefix + "scheduler_tasks",
                otherPrefix + "persisted_signals"
            )
    }

    companion object {
        private const val TEMPO_PREFIX = "tempo_"

        private const val SQLITE_LIST_TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type = 'table'"

        private const val MYSQL_LIST_TABLES_SQL =
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
    }
}
