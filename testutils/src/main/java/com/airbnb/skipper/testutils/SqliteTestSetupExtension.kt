package com.airbnb.skipper.testutils

import com.airbnb.skipper.SkipperConfig
import io.vavr.collection.List
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.sqlite.SQLiteDataSource

/**
 * Setup integration tests for code that uses the SQLite Skipper backend.
 *
 * It extends the OSS-native [OssSetupExtension], exposes a static [DataSource] field
 * ([DB_DATA_SOURCE]), and resets state before each test, backing it with a shared, named
 * in-memory SQLite database. It bootstraps the schema with Flyway, pointed at the SQLite-dialect
 * migrations (`classpath:db/sqlite`).
 *
 * Unlike MySQL, SQLite has no separate server: the database lives in-memory inside the driver.
 * To let multiple connections (and therefore multiple stores built for different owners, e.g.
 * `createWorkflowStore("test-owner2")`) see one shared database, we use a uniquely-named
 * shared-cache in-memory URL (`jdbc:sqlite:file:<uuid>?mode=memory&cache=shared`) rather than
 * a plain `jdbc:sqlite::memory:` URL (which gives each connection a private database). A
 * shared-cache in-memory database is destroyed when its last connection closes, so [setup]
 * opens one keep-alive [Connection] and holds it for the lifetime of the DataSource (closed
 * in [close]). The [DB_DATA_SOURCE] field is the single DataSource tests wire into
 * `config.setSqliteDataSource(...)`; sharing across owners comes from that common DataSource.
 *
 * SQLite has no `TRUNCATE`, so [beforeEach] resets each of the six Skipper tables with
 * `DELETE FROM <table>`.
 *
 * The [tablePrefix] (default [SkipperConfig.DEFAULT_TABLE_PREFIX]) is the single source of truth for
 * table naming: it is supplied to Flyway as the `tablePrefix` placeholder so the templated DDL
 * creates `<prefix>*` tables, and the same prefix drives the [tables] reset list. Tests that exercise
 * an alternate prefix (e.g. the legacy `tempo_`) construct the extension with an explicit prefix.
 */
class SqliteTestSetupExtension
    @JvmOverloads
    constructor(
        private val tablePrefix: String = SkipperConfig.DEFAULT_TABLE_PREFIX
    ) : OssSetupExtension(), BeforeEachCallback {
        /** The six Skipper tables under the configured prefix, reset before each test. */
        private val tables: List<String> = TABLE_SUFFIXES.map { suffix -> tablePrefix + suffix }

        override fun beforeEach(context: ExtensionContext) {
            DB_DATA_SOURCE!!.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    tables.forEach { tableName ->
                        try {
                            stmt.execute("DELETE FROM $tableName")
                        } catch (e: SQLException) {
                            throw RuntimeException(e)
                        }
                    }
                }
            }
        }

        override fun setup() {
            val url = "jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared"
            val dataSource = SQLiteDataSource()
            dataSource.url = url
            // Keep one connection open for the DataSource's lifetime so the shared in-memory database
            // survives between pooled connections.
            keepAlive = dataSource.connection
            DB_DATA_SOURCE = dataSource

            // Bootstrap the schema with Flyway against the SQLite-dialect migrations, the same mechanism
            // JdbcTransactionManager.SqliteFactory uses at runtime.
            val flyway = Flyway()
            flyway.setDataSource(DB_DATA_SOURCE)
            flyway.setLocations("classpath:db/sqlite")
            // The templated DDL uses the `${tablePrefix}` placeholder; Flyway's placeholderReplacement
            // defaults to true, so this must be supplied or migrate() fails resolving the placeholder.
            flyway.setPlaceholders(mapOf("tablePrefix" to tablePrefix))
            flyway.migrate()
        }

        override fun close() {
            keepAlive?.close()
            keepAlive = null
        }

        companion object {
            /** The six Skipper table-name suffixes (the prefix is prepended per instance). */
            private val TABLE_SUFFIXES: List<String> =
                List.of(
                    "workflow_instances",
                    "action_checkpoints",
                    "timers",
                    "scheduler_tasks",
                    "persisted_signals",
                    "cluster_members"
                )

            @JvmField
            var DB_DATA_SOURCE: DataSource? = null

            // Held open for the lifetime of the shared in-memory database so it is not destroyed when
            // individual pooled connections close. Closed in [close].
            private var keepAlive: Connection? = null
        }
    }
