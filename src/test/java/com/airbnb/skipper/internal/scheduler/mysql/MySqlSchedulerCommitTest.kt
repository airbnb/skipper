package com.airbnb.skipper.internal.scheduler.mysql

import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.testutils.TestRuntime
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** What schedule throws when it inserts a new task and the commit fails. */
class MySqlSchedulerCommitTest {
    private val noExistingTask: ResultSet = mock<ResultSet>().also { whenever(it.next()).thenReturn(false) }
    private val statement: PreparedStatement =
        mock<PreparedStatement>().also {
            whenever(it.executeQuery()).thenReturn(noExistingTask)
            whenever(it.executeUpdate()).thenReturn(1)
        }
    private val connection: Connection = mock<Connection>().also { whenever(it.prepareStatement(any())).thenReturn(statement) }
    private val dataSource: DataSource = mock<DataSource>().also { whenever(it.connection).thenReturn(connection) }

    private val scheduler: MySqlScheduler =
        TestRuntime().let { deps ->
            MySqlScheduler(
                JdbcTransactionManager(dataSource, Duration.ofSeconds(1)),
                deps.clock,
                "test-owner",
                deps.runtime.metrics.get(),
                SimplePojoSerde(),
                Duration.ofMinutes(1),
                "skipper_",
            )
        }

    private val request: ScheduleRequest<String> =
        ScheduleRequest.builder<String>()
            .id("test-task-id")
            .payload("payload")
            .type(Task.Type.WORKFLOW)
            .dedupToken("test-task-id")
            .build()

    @Test
    fun testDuplicateReportedAtCommitMeansTheTaskAlreadyExists() {
        whenever(connection.commit()).thenThrow(
            SQLIntegrityConstraintViolationException("Duplicate entry for key 'PRIMARY'", "23000", 1062),
        )

        assertThrows(EntityAlreadyExists::class.java) { scheduler.schedule(request) }
        verify(connection).rollback()
        verify(connection).close()
    }

    @Test
    fun testOtherCommitFailuresAreNotReportedAsDuplicates() {
        val writeConflict = SQLException("Write conflict", "HY000", 9007)
        whenever(connection.commit()).thenThrow(writeConflict)

        assertSame(writeConflict, assertThrows(SQLException::class.java) { scheduler.schedule(request) })
    }
}
