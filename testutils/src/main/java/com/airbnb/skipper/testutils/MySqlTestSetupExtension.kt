package com.airbnb.skipper.testutils

import com.airbnb.skipper.SkipperConfig
import io.vavr.collection.List
import java.sql.SQLException
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.internal.util.jdbc.DriverDataSource
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.mariadb.jdbc.MariaDbDataSource

/**
 * Setup integration tests for code that uses the MySQL Skipper backend.
 *
 * This will spin up an in-memory MariaDB database and run Flyway migrations to create the database
 * schema.
 *
 * The [tablePrefix] (default [SkipperConfig.DEFAULT_TABLE_PREFIX]) is the single source of truth for
 * table naming: it is supplied to Flyway as the `tablePrefix` placeholder so the templated DDL
 * creates `<prefix>*` tables, and the same prefix drives the [tables] reset list. Tests that exercise
 * an alternate prefix (e.g. the legacy `tempo_`) construct the extension with an explicit prefix.
 */
class MySqlTestSetupExtension
    @JvmOverloads
    constructor(
        private val tablePrefix: String = SkipperConfig.DEFAULT_TABLE_PREFIX
    ) : OssSetupExtension(), BeforeEachCallback {
        /** The Skipper tables under the configured prefix, truncated before each test. */
        private val tables: List<String> = TABLE_SUFFIXES.map { suffix -> tablePrefix + suffix }

        override fun beforeEach(context: ExtensionContext) {
            ds!!.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    tables.forEach { tableName ->
                        try {
                            stmt.execute("TRUNCATE TABLE $tableName")
                        } catch (e: SQLException) {
                            throw RuntimeException(e)
                        }
                    }
                }
            }
        }

        override fun setup() {
            mysqlDB4jEnv = MysqlDB4jEnv(DB_NAME)
            mysqlDB4jEnv!!.start()
            val migrationDataSource =
                DriverDataSource(
                    ClassLoader.getSystemClassLoader(),
                    "com.mysql.jdbc.Driver",
                    mysqlDB4jEnv!!.mysqlJdbcUrl,
                    "root",
                    ""
                )
            ds =
                MariaDbDataSource(
                    mysqlDB4jEnv!!.mariaDbJdbcUrl + "?user=root"
                )

            val flyway = Flyway()
            flyway.setDataSource(migrationDataSource)
            flyway.setLocations("classpath:db/migration")
            flyway.setPlaceholders(mapOf("tablePrefix" to tablePrefix))
            flyway.setCleanDisabled(true)
            flyway.migrate()
            DB_DATA_SOURCE = ds
        }

        override fun close() {
            mysqlDB4jEnv?.stop()
            mysqlDB4jEnv = null
            ds = null
            DB_DATA_SOURCE = null
        }

        companion object {
            /** The Skipper table-name suffixes truncated before each test (the prefix is prepended per instance). */
            private val TABLE_SUFFIXES: List<String> =
                List.of(
                    "workflow_instances",
                    "action_checkpoints",
                    "timers",
                    "scheduler_tasks",
                    "persisted_signals"
                )

            private var ds: DataSource? = null
            private var mysqlDB4jEnv: MysqlDB4jEnv? = null

            @JvmField
            var DB_NAME: String = "testdb"

            @JvmField
            var DB_DATA_SOURCE: DataSource? = null
        }
    }
