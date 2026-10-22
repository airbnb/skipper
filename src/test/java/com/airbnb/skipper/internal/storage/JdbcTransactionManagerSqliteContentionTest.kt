package com.airbnb.skipper.internal.storage

import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.function.Function
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Lock contention against a real shared-cache SQLite database: another connection holds a write
 * transaction on the table the manager reads.
 */
class JdbcTransactionManagerSqliteContentionTest {
    private lateinit var dataSource: SQLiteDataSource
    private lateinit var keepAlive: Connection
    private var holder: Thread? = null

    @BeforeEach
    fun setUp() {
        dataSource = SQLiteDataSource()
        dataSource.url = "jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared"
        keepAlive = dataSource.connection
        keepAlive.createStatement().use { it.execute("CREATE TABLE rows (id INTEGER PRIMARY KEY, v TEXT)") }
    }

    @AfterEach
    fun tearDown() {
        holder?.join(10_000)
        keepAlive.close()
    }

    /** Returns once another connection holds an uncommitted write on `rows`. That connection commits after [hold]. */
    private fun holdWriteLock(hold: Duration) {
        val locked = CountDownLatch(1)
        holder =
            Thread {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    conn.createStatement().use { it.execute("INSERT INTO rows (v) VALUES ('held')") }
                    locked.countDown()
                    Thread.sleep(hold.toMillis())
                    conn.commit()
                }
            }.also { it.start() }
        locked.await()
    }

    private fun countRows(manager: JdbcTransactionManager): Int =
        manager.execute(
            Function<Connection, Int> { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM rows").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            },
        )

    @Test
    fun testAReadWaitsOutAWriterHeldLongerThanAFewQuickRetries() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofSeconds(10))
        holdWriteLock(Duration.ofMillis(1_500))
        val started = System.nanoTime()

        val count = countRows(manager)

        assertEquals(1, count)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(1_400), "waited for the writer")
    }

    @Test
    fun testAReadGivesUpSoonAfterTheBudget() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofMillis(500))
        holdWriteLock(Duration.ofSeconds(3))
        val started = System.nanoTime()

        val thrown = assertThrows(SQLiteException::class.java) { countRows(manager) }

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(SQLiteErrorCode.SQLITE_LOCKED.code, thrown.resultCode.code and 0xFF)
        assertTrue(elapsed >= Duration.ofMillis(500), "retried for the budget: $elapsed")
        assertTrue(elapsed < Duration.ofMillis(1_500), "gave up soon after the budget: $elapsed")
    }
}
