package com.airbnb.skipper.admin

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.factory.SkipperRuntime
import com.example.adminjson.Greeting
import com.example.adminjson.Payout
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The bundled admin UI reads snake_case keys and ISO-8601 timestamps. Those must come from the
 * resource itself, whatever JSON provider the host's JAX-RS stack has, so every JSON endpoint returns
 * a pre-serialized entity. User payloads embedded in the response keep their own property names.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminResourceJsonTest {
    open class EchoWorkflow : Workflow() {
        @WorkflowMethod
        open fun echo(input: Greeting): String = "hello ${input.firstName}"
    }

    open class PayoutWorkflow : Workflow() {
        @WorkflowMethod
        open fun pay(input: Payout): String = "paid"
    }

    private lateinit var runtime: SkipperRuntime
    private lateinit var admin: AdminResource
    private val plainMapper = ObjectMapper()

    @BeforeAll
    fun setUp() {
        runtime = SkipperRuntime(SkipperConfig.forService("admin-json-test"))
        runtime.skipperSchedulerManager.get().start()
        runtime.workflowFactory.get().invoke(EchoWorkflow::class.java, WORKFLOW_ID).echo(Greeting("Ada"))
        runtime.workflowFactory.get().invoke(PayoutWorkflow::class.java, UNION_WORKFLOW_ID).pay(Payout(amountMicros = 42))
        awaitCompleted(WORKFLOW_ID)
        awaitCompleted(UNION_WORKFLOW_ID)
        admin = runtime.adminResource.get()
    }

    @AfterAll
    fun tearDown() {
        runtime.skipperSchedulerManager.get().stop()
    }

    @Test
    fun responsesArePreSerializedJson() {
        val response = admin.getDashboardStats()
        assertEquals(Response.Status.OK.statusCode, response.status)
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.mediaType)
        assertTrue(response.entity is String, "entity should be serialized by the resource, not the host")
    }

    @Test
    fun skipperKeysAreSnakeCase() {
        val stats = json(admin.getDashboardStats())
        assertTrue(stats.has("exhausted_retries_count"), stats.toString())
        assertTrue(stats.has("dlq_tasks_count"), stats.toString())
        assertTrue(stats.has("scheduler_backlog_count"), stats.toString())

        val types = json(admin.listWorkflowTypes())
        val echoType = types.single { it["workflow_class"].asText() == EchoWorkflow::class.java.name }
        assertEquals("echo", echoType["workflow_method"].asText())

        val search = json(admin.searchWorkflows(null, null, null, null, null, 50, null))
        assertTrue(search["workflows"].any { it["workflow_id"].asText() == WORKFLOW_ID }, search.toString())
        assertTrue(search.has("next_cursor"), search.toString())
    }

    @Test
    fun timestampsAreIso8601Strings() {
        val instance = json(admin.getWorkflowInstance(WORKFLOW_ID))["instance"]
        val createdAt = instance["created_at"]
        assertTrue(createdAt.isTextual, "created_at should be a string, got $createdAt")
        Instant.parse(createdAt.asText()) // throws if not ISO-8601
    }

    @Test
    fun userPayloadKeepsItsOwnPropertyNames() {
        val detail = json(admin.getWorkflowInstance(WORKFLOW_ID))
        val input = detail["instance"]["input"]
        assertEquals(Greeting::class.java.name, input["type"].asText())
        assertEquals("Ada", input["value"]["firstName"]?.asText(), input.toString())
        assertFalse(input["value"].has("first_name"), "user field must not be renamed")
        assertEquals("hello Ada", detail["instance"]["result"]["ok"]["value"]?.asText(), detail.toString())
    }

    @Test
    fun thriftUnionInputRendersOnlyItsSetField() {
        val input = json(admin.getWorkflowInstance(UNION_WORKFLOW_ID))["instance"]["input"]["value"]
        assertEquals(42, input["amountMicros"]?.asLong(), input.toString())
        assertFalse(input.has("giftCardCode"), "unset union fields must be filtered out: $input")
    }

    private fun awaitCompleted(workflowId: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (runtime.workflowStore.get().getWorkflow(workflowId).get().status != WorkflowInstance.Status.COMPLETED) {
            check(System.currentTimeMillis() < deadline) { "workflow $workflowId did not complete" }
            Thread.sleep(50)
        }
    }

    private fun json(response: Response): JsonNode = plainMapper.readTree(response.entity as String)

    companion object {
        private const val WORKFLOW_ID = "admin-json-wf"
        private const val UNION_WORKFLOW_ID = "admin-json-union-wf"
    }
}
