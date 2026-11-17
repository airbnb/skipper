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
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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

    @Test
    fun testDuplicateReportedAtCommitMeansTheWorkflowAlreadyExists() {
        whenever(connection.commit()).thenThrow(
            SQLIntegrityConstraintViolationException("Duplicate entry 'test-workflow-id' for key 'PRIMARY'", "23000", 1062),
        )

        assertThrows(EntityAlreadyExists::class.java) { store.createWorkflow(request) }
    }

    @Test
    fun testOtherCommitFailuresAreNotReportedAsDuplicates() {
        val writeConflict = SQLException("Write conflict", "HY000", 9007)
        whenever(connection.commit()).thenThrow(writeConflict)

        assertSame(writeConflict, assertThrows(SQLException::class.java) { store.createWorkflow(request) })
    }
}
