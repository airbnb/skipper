package com.airbnb.skipper.internal.storage

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.function.Function
import java.util.function.Supplier
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class JdbcTransactionManagerTest {
    private val connection: Connection = mock()
    private val dataSource: DataSource = mock<DataSource>().also { whenever(it.connection).thenReturn(connection) }

    // The message has no "SQLITE_LOCKED" text, so only the result code can make this match.
    private fun locked() = SQLiteException("database table is locked: skipper_workflow_instances", SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)

    @Test
    fun testContentionIsRetriedUntilTheTransactionSucceeds() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofSeconds(5))
        var calls = 0

        val result = manager.execute(Function<Connection, String> { if (++calls <= 3) throw locked() else "ok" })

        assertEquals("ok", result)
        assertEquals(4, calls)
        // Each failed attempt was rolled back and closed before the retry.
        verify(connection, times(3)).rollback()
        verify(connection, times(4)).close()
    }

    @Test
    fun testABusyDatabaseIsRetriedLikeALockedTable() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofSeconds(5))
        var calls = 0

        val result =
            manager.retryOnTransientLockContention(
                Supplier { if (++calls == 1) throw SQLiteException("database is locked", SQLiteErrorCode.SQLITE_BUSY) else "ok" },
            )

        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun testOtherFailuresAreNotRetried() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofSeconds(5))
        val failure = SQLException("syntax error")
        var calls = 0

        val thrown = assertThrows(SQLException::class.java) {
            manager.execute(
                Function<Connection, String> {
                    calls++
                    throw failure
                }
            )
        }

        assertSame(failure, thrown)
        assertEquals(1, calls)
    }

    @Test
    fun testRetriesWaitLongerEachTimeInsteadOfSpinning() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofMillis(500))
        var calls = 0

        assertThrows(SQLiteException::class.java) {
            manager.retryOnTransientLockContention(
                Supplier<String> {
                    calls++
                    throw locked()
                },
            )
        }

        // Waits that double from 5 ms to 100 ms fit about ten attempts into the budget.
        assertTrue(calls in 2..15, "attempts within the budget: $calls")
    }

    @Test
    fun testAnInterruptStopsTheRetryAndStaysSet() {
        val manager = JdbcTransactionManager(dataSource, Duration.ofSeconds(5))
        var calls = 0

        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                manager.retryOnTransientLockContention(
                    Supplier<String> {
                        calls++
                        throw locked()
                    },
                )
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(1, calls)
    }
}
