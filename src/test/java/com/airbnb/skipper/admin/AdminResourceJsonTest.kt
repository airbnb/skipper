package com.airbnb.skipper.admin

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.factory.SkipperRuntime
import com.example.adminjson.Greeting
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

    private lateinit var runtime: SkipperRuntime
    private lateinit var admin: AdminResource
    private val plainMapper = ObjectMapper()

    @BeforeAll
    fun setUp() {
        runtime = SkipperRuntime(SkipperConfig.forService("admin-json-test"))
        runtime.skipperSchedulerManager.get().start()
        runtime.workflowFactory.get().invoke(EchoWorkflow::class.java, WORKFLOW_ID).echo(Greeting("Ada"))
        val deadline = System.currentTimeMillis() + 10_000
        while (runtime.workflowStore.get().getWorkflow(WORKFLOW_ID).get().status != WorkflowInstance.Status.COMPLETED) {
            check(System.currentTimeMillis() < deadline) { "workflow did not complete" }
            Thread.sleep(50)
        }
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
        assertEquals(EchoWorkflow::class.java.name, types[0]["workflow_class"].asText())
        assertEquals("echo", types[0]["workflow_method"].asText())

        val search = json(admin.searchWorkflows(null, null, null, null, null, 50, null))
        assertEquals(WORKFLOW_ID, search["workflows"][0]["workflow_id"].asText())
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

    private fun json(response: Response): JsonNode = plainMapper.readTree(response.entity as String)

    companion object {
        private const val WORKFLOW_ID = "admin-json-wf"
    }
}
