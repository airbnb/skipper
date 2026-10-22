package com.airbnb.skipper.internal.storage

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.function.Function
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
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
}
