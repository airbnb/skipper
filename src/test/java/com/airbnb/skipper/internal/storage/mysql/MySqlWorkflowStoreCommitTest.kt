package com.airbnb.skipper.internal.storage.mysql

import com.airbnb.skipper.RequestContextSerde
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.internal.TestUtils.EXTRA_REQUEST_DATA
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.testutils.TestRuntime
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Timestamp
import java.time.Duration
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** What createWorkflow throws when the INSERT succeeds but the commit fails. */
class MySqlWorkflowStoreCommitTest {
    private val statement: PreparedStatement = mock<PreparedStatement>().also { whenever(it.executeUpdate()).thenReturn(1) }
    private val connection: Connection = mock<Connection>().also { whenever(it.prepareStatement(any())).thenReturn(statement) }
    private val dataSource: DataSource = mock<DataSource>().also { whenever(it.connection).thenReturn(connection) }

    private val store: MySqlWorkflowStore =
        TestRuntime().let { deps ->
            MySqlWorkflowStore(
                JdbcTransactionManager(dataSource, Duration.ofSeconds(1)),
                deps.runtime.metrics.get(),
                deps.runtime.serde.get(),
                SimplePojoSerde(),
                RequestContextSerde.NOOP,
                deps.clock,
                "test-owner",
                "skipper_",
            )
        }

    private val request: WorkflowCreationRequest =
        WorkflowCreationRequest.builder()
            .workflowClass(Workflow::class.java)
            .workflowId("test-workflow-id")
            .workflowMethod("test-method")
            .extraRequestData(EXTRA_REQUEST_DATA)
            .build()

    private val writeConflict = SQLException("Write conflict", "HY000", 9007)

    @Test
    fun testDuplicateReportedAtCommitMeansTheWorkflowAlreadyExists() {
        whenever(connection.commit()).thenThrow(
            SQLIntegrityConstraintViolationException("Duplicate entry 'test-workflow-id' for key 'PRIMARY'", "23000", 1062),
        )

        assertThrows(EntityAlreadyExists::class.java) { store.createWorkflow(request) }
    }

    /** Only a write conflict is checked against the stored row; any other commit failure comes back unchanged. */
    @Test
    fun testOtherCommitFailuresAreNotReportedAsDuplicates() {
        val lockWaitTimeout = SQLException("Lock wait timeout exceeded", "HY000", 1205)
        doThrow(lockWaitTimeout).doNothing().whenever(connection).commit()
        val winnersRow = winnersRow()
        whenever(statement.executeQuery()).thenReturn(winnersRow)

        assertSame(lockWaitTimeout, assertThrows(SQLException::class.java) { store.createWorkflow(request) })
    }

    /** A creator that loses the race to another creator of the same id is told the workflow already exists. */
    @Test
    fun testWriteConflictAtCommitWithTheRowPresentMeansTheWorkflowAlreadyExists() {
        failOnlyTheCreatesCommit()
        val winnersRow = winnersRow()
        whenever(statement.executeQuery()).thenReturn(winnersRow)

        assertThrows(EntityAlreadyExists::class.java) { store.createWorkflow(request) }
    }

    /** A write conflict with no workflow behind it is not a duplicate; the caller gets the original error. */
    @Test
    fun testWriteConflictAtCommitWithTheRowAbsentRethrowsTheOriginalError() {
        failOnlyTheCreatesCommit()
        val noRow = mock<ResultSet>().also { whenever(it.next()).thenReturn(false) }
        whenever(statement.executeQuery()).thenReturn(noRow)

        assertSame(writeConflict, assertThrows(SQLException::class.java) { store.createWorkflow(request) })
    }

    /** The create's commit fails with a write conflict; the re-read that follows commits normally. */
    private fun failOnlyTheCreatesCommit() {
        doThrow(writeConflict).doNothing().whenever(connection).commit()
    }

    /** The row another creator committed for the same id. */
    private fun winnersRow(): ResultSet =
        mock<ResultSet>().also {
            whenever(it.next()).thenReturn(true)
            whenever(it.getString("workflow_id")).thenReturn(request.workflowId)
            whenever(it.getString("workflow_class")).thenReturn(Workflow::class.java.name)
            whenever(it.getString("workflow_method")).thenReturn(request.workflowMethod)
            whenever(it.getString("state")).thenReturn("{}")
            whenever(it.getString("status")).thenReturn("RUNNING")
            whenever(it.getString("request_context")).thenReturn("")
            whenever(it.getString("extra_request_data")).thenReturn(SimplePojoSerde().serialize(EXTRA_REQUEST_DATA))
            whenever(it.getTimestamp(any<String>())).thenReturn(Timestamp(0))
        }
}
