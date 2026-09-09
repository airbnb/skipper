package com.airbnb.skipper.internal.storage.sqlite

import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.testutils.TestRuntime
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The documented way to get a durable single-node store: `SqliteWorkflowStore.Factory("skipper.db")`
 * and `SqliteScheduler.Factory("skipper.db")`. Both must land in the same file, bootstrap the schema
 * there, and see each other's rows across separately built runtimes (i.e. a process restart).
 */
class SqliteFileBackedFactoryTest {
    @Test
    fun factoriesWithAPathShareOneDurableDatabaseFile(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("skipper.db").toString()

        val first = TestRuntime()
        first.config.workflowStore = SqliteWorkflowStore.Factory(path)
        first.config.scheduler = SqliteScheduler.Factory(path)
        val tenant = first.config.tenant
        first.scheduler.schedule(
            ScheduleRequest.builder<String>()
                .id("durable")
                .dedupToken("durable")
                .payload("payload")
                .type(Task.Type.WORKFLOW)
                .build(),
        )
        assertTrue(Files.exists(dir.resolve("skipper.db")), "database file is created at the given path")

        // A second, independently built runtime on the same path (same tenant) sees the persisted task.
        val second = TestRuntime()
        second.config.tenant = tenant
        second.config.workflowStore = SqliteWorkflowStore.Factory(path)
        second.config.scheduler = SqliteScheduler.Factory(path)
        val task = second.scheduler.getTask<String>("durable")
        assertTrue(task.isDefined, "task persisted to disk is visible to a new runtime")
        assertEquals("payload", task.get().payload)
    }

    @Test
    fun noArgFactoriesStillDefaultToInMemory() {
        // Guards the Java-visible no-arg constructors (@JvmOverloads) that the quickstart relies on.
        val deps = TestRuntime()
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        assertEquals(0, deps.scheduler.realSize())
    }
}
