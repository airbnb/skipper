package com.airbnb.skipper.internal.cluster.jdbc

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.control.Option
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.fail
import org.mockito.kotlin.whenever

/**
 * End-to-end check that wiring [JdbcClusterMembershipManager] into a Skipper instance actually
 * turns task partitioning on: two scheduler managers sharing one database and tenant discover each
 * other through the `cluster_members` table, and from then on each processes only the tasks whose
 * ID hashes into its own bucket range.
 *
 * Without a cluster-aware membership manager both instances would fetch from the whole queue, and
 * with 40 tasks the chance that every task lands on its bucket's owner by accident is negligible.
 */
@ExtendWith(SqliteTestSetupExtension::class)
class JdbcClusterPartitioningIntegrationTest {
    private val tenant = "partition-test-" + UUID.randomUUID()
    private val processedBy = ConcurrentHashMap<String, String>()
    private val instances = ArrayList<Instance>()
    private val counters = ConcurrentHashMap<String, Long>()

    /** Counts every counter increment by `<tags>/<names>` so the test can see which fetch path ran. */
    private val recordingMetrics =
        object : NoOpMetrics() {
            override fun counter(vararg names: String): SkipperCounter = counter(emptyMap(), *names)

            override fun counter(
                tags: Map<String, String>,
                vararg names: String,
            ): SkipperCounter {
                val key = tags.toSortedMap().toString() + "/" + names.joinToString(".")
                return object : SkipperCounter {
                    override fun inc() = inc(1)

                    override fun inc(n: Long) {
                        counters.merge(key, n, Long::plus)
                    }
                }
            }
        }

    private class Instance(
        val memberName: String,
        val scheduler: Scheduler,
        val membership: ClusterMembershipManager,
        val manager: SkipperSchedulerManager,
        val executor: ExecutorService,
        val handlerExecutor: ExecutorService,
    )

    @AfterEach
    fun tearDown() {
        instances.forEach { it.manager.stop() }
        instances.forEach {
            it.executor.shutdownNow()
            it.handlerExecutor.shutdownNow()
        }
        instances.clear()
    }

    @Test
    fun twoInstancesProcessDisjointPartitionsOfTheQueue() {
        val a = startInstance("member-a")
        val b = startInstance("member-b")

        // Both instances must see the full membership before any task exists; until then a member
        // legitimately owns the whole bucket space.
        val members = List.of("member-a", "member-b")
        awaitCondition {
            a.membership.activeMemberIds == members && b.membership.activeMemberIds == members
        }

        val taskIds = (1..TASK_COUNT).map { UUID.randomUUID().toString() }
        for (id in taskIds) {
            a.scheduler.schedule(
                ScheduleRequest.builder<Any>()
                    .type(Task.Type.WORKFLOW)
                    .id(id)
                    .dedupToken(id)
                    .payload(null)
                    .build(),
            )
        }

        awaitCondition(Duration.ofSeconds(30)) { processedBy.size == TASK_COUNT }

        val partitioner = BucketPartitioner()
        val ranges = members.toJavaList().associateWith { partitioner.getBucketRangeForMember(it, members) }
        for ((taskId, member) in processedBy) {
            val bucket = partitioner.hashToBucket(taskId)
            val range = ranges.getValue(member)
            assertTrue(
                range.contains(bucket),
                "task $taskId (bucket $bucket) was processed by $member, whose partition is $range; counters=$counters",
            )
        }
        assertEquals(members.toJavaSet(), processedBy.values.toSet(), "both instances should have taken work")
    }

    private fun startInstance(memberName: String): Instance {
        val deps = TestRuntime()
        deps.config.tenant = tenant
        deps.config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        deps.config.clusterMembershipManager = JdbcClusterMembershipManager.SqliteFactory()
        deps.config.clusterMemberName = memberName
        deps.config.clusterHeartBeatInterval = Duration.ofMillis(100)
        deps.config.metrics = ComponentFactory { recordingMetrics }
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.TASK_PARTITIONING)).thenReturn(true)

        val runtime = deps.runtime
        val membership = runtime.clusterMembershipManager.get()
        val handler =
            object : TaskHandler {
                override fun handle(
                    task: Task<*>,
                    executorService: ExecutorService,
                ): CompletableFuture<Option<Instant>> {
                    processedBy[task.id] = memberName
                    return CompletableFuture.completedFuture(Option.none())
                }
            }
        val executor = Executors.newCachedThreadPool()
        val handlerExecutor = Executors.newSingleThreadExecutor()
        val manager =
            SkipperSchedulerManager(
                deps.schedulerExecutionQueue,
                deps.scheduler,
                executor,
                handlerExecutor,
                HashMap.of(Task.Type.WORKFLOW, handler),
                runtime.metrics.get(),
                10,
                deps.leaseRenewalManager,
                deps.featureGate,
                runtime.knobs.get(),
                membership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )
        manager.start()
        val instance = Instance(memberName, deps.scheduler, membership, manager, executor, handlerExecutor)
        instances.add(instance)
        return instance
    }

    private fun awaitCondition(
        timeout: Duration = Duration.ofSeconds(5),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met within $timeout")
            }
            Thread.sleep(50)
        }
    }

    companion object {
        private const val TASK_COUNT = 40
    }
}
