package com.airbnb.skipper.internal.storage.sqlite

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

/**
 * Exercises a durable, file-backed SQLite database via [SkipperConfig.setSqliteDataSource]: a caller
 * supplies a [SQLiteDataSource] pointed at a `jdbc:sqlite:<file>` URL,
 * [JdbcTransactionManager.SqliteFactory] runs the Flyway migrations to create the schema, and rows
 * written through [JdbcTransactionManager.execute] round-trip losslessly.
 */
internal class SqliteTransactionManagerTest {
    @Test
    fun fileBackedManagerInitializesSchemaAndRoundTripsRow(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("skipper-test.db")
        val fileDataSource = SQLiteDataSource()
        fileDataSource.url = "jdbc:sqlite:$dbFile"

        val config = SkipperConfig.forService("test")
        config.sqliteDataSource = fileDataSource

        // The factory runs the SQLite Flyway migrations against the supplied DataSource, creating the
        // schema before returning the manager.
        val manager = JdbcTransactionManager.SqliteFactory().create(config)

        val timerId = "timer-1"
        val owner = "test"
        val workflowId = "workflow-1"
        val expiresAt = Timestamp.valueOf("2026-01-02 03:04:05.123")
        val status = "PENDING"
        val durationInSecs = 600L
        val createdAt = Timestamp.valueOf("2026-01-01 00:00:00.000")
        val updatedAt = Timestamp.valueOf("2026-01-01 00:00:01.000")
        val version = 0L

        manager.execute<Any?> { connection ->
            // The execute(...) lambda is a Function, which cannot declare checked exceptions, so the
            // JDBC SQLException is rethrown as unchecked. In production the SQLException would surface
            // via the manager's own SneakyThrow; here it would simply fail the test.
            try {
                connection.prepareStatement(
                    "INSERT INTO ${config.tablePrefix}timers (" +
                        "timer_id, owner, workflow_id, expires_at, status, duration_in_secs, " +
                        "created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, timerId)
                    ps.setString(2, owner)
                    ps.setString(3, workflowId)
                    ps.setTimestamp(4, expiresAt)
                    ps.setString(5, status)
                    ps.setLong(6, durationInSecs)
                    ps.setTimestamp(7, createdAt)
                    ps.setTimestamp(8, updatedAt)
                    ps.setLong(9, version)
                    ps.executeUpdate()
                }
            } catch (e: java.sql.SQLException) {
                throw RuntimeException(e)
            }
            null
        }

        val readBack =
            manager.execute<Timer> { connection ->
                try {
                    connection.prepareStatement(
                        "SELECT timer_id, owner, workflow_id, expires_at, status, duration_in_secs," +
                            " created_at, updated_at, version FROM ${config.tablePrefix}timers WHERE owner = ? AND" +
                            " workflow_id = ? AND timer_id = ?"
                    ).use { ps ->
                        ps.setString(1, owner)
                        ps.setString(2, workflowId)
                        ps.setString(3, timerId)
                        ps.executeQuery().use { rs ->
                            assertThat(rs.next()).isTrue()
                            val timer =
                                Timer(
                                    rs.getString("timer_id"),
                                    rs.getString("owner"),
                                    rs.getString("workflow_id"),
                                    rs.getTimestamp("expires_at"),
                                    rs.getString("status"),
                                    rs.getLong("duration_in_secs"),
                                    rs.getTimestamp("created_at"),
                                    rs.getTimestamp("updated_at"),
                                    rs.getLong("version")
                                )
                            assertThat(rs.next()).isFalse()
                            timer
                        }
                    }
                } catch (e: java.sql.SQLException) {
                    throw RuntimeException(e)
                }
            }

        assertThat(readBack.timerId).isEqualTo(timerId)
        assertThat(readBack.owner).isEqualTo(owner)
        assertThat(readBack.workflowId).isEqualTo(workflowId)
        assertThat(readBack.expiresAt).isEqualTo(expiresAt)
        assertThat(readBack.status).isEqualTo(status)
        assertThat(readBack.durationInSecs).isEqualTo(durationInSecs)
        assertThat(readBack.createdAt).isEqualTo(createdAt)
        assertThat(readBack.updatedAt).isEqualTo(updatedAt)
        assertThat(readBack.version).isEqualTo(version)

        // The file-backed DataSource must produce a durable, on-disk database file.
        assertThat(Files.exists(dbFile)).isTrue()
    }

    /** Immutable holder for a `<prefix>timers` row read back from the database. */
    private class Timer(
        val timerId: String,
        val owner: String,
        val workflowId: String,
        val expiresAt: Timestamp,
        val status: String,
        val durationInSecs: Long,
        val createdAt: Timestamp,
        val updatedAt: Timestamp,
        val version: Long
    )
}
