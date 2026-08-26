package com.airbnb.skipper

import com.airbnb.skipper.internal.ActionExecutor.ExecuteActionRequest
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.util.ExtraRequestData
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import io.opentracing.Tracer.SpanBuilder
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ExecutionMetricsCollectorTest {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var mockTracer: Tracer
    private lateinit var collector: ExecutionMetricsCollector

    @BeforeEach
    fun setup() {
        mockTracer = mock<Tracer>()
        collector = ExecutionMetricsCollector(mockTracer)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateActionSpan_withoutParentContext() {
        val actionClass = "TestAction"
        val actionMethod = "testMethod"
        val workflowClass = "TestWorkflow"
        val workflowMethod = "workflowMethod"

        // Create real workflow instance
        val workflow =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(TestWorkflow::class.java)
                .workflowMethod(workflowMethod)
                .extraRequestData(ExtraRequestData())
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflow)
                .clock(Clock.systemUTC())
                .executorService(executorService)
                .build()

        // Create test action and method
        val testAction = TestAction()
        val method: Method = TestAction::class.java.getDeclaredMethod(actionMethod, String::class.java)

        // Create real ExecuteActionRequest
        val request =
            ExecuteActionRequest.builder()
                .actionObject(testAction)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("test"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()

        val mockSpanBuilder = mock<SpanBuilder>()
        val mockSpan = mock<Span>()

        whenever(mockTracer.buildSpan(any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.withTag(any<String>(), any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.start()).thenReturn(mockSpan)

        val result = collector.createActionSpan(request)

        assertNotNull(result)

        // Verify the span name
        val spanNameCaptor = argumentCaptor<String>()
        verify(mockTracer).buildSpan(spanNameCaptor.capture())
        assertEquals(
            String.format("action.%s.%s", actionClass, actionMethod),
            spanNameCaptor.firstValue
        )

        // Verify tags were set with camelCase
        verify(mockSpanBuilder)
            .withTag(
                ExecutionMetricsCollector.TAG_COMPONENT,
                ExecutionMetricsCollector.RESOURCE_TEMPO_ACTION
            )
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_ACTION_CLASS, actionClass)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_ACTION_METHOD, actionMethod)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_CLASS, workflowClass)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_METHOD, workflowMethod)
    }

    // Helper classes for mocking
    private class TestAction : Actions() {
        @Execute
        fun testMethod(input: String): String {
            return input
        }
    }

    private class TestWorkflow : Workflow() {
        @WorkflowMethod
        fun workflowMethod(input: String): String {
            return input
        }
    }

    @Test
    @Throws(Exception::class)
    fun testCreateActionSpan_withParentContext() {
        val actionClass = "TestAction"
        val actionMethod = "testMethod"
        val workflowClass = "TestWorkflow"
        val workflowMethod = "workflowMethod"

        // Create a mock active span (workflow span)
        val mockWorkflowSpan = mock<Span>()
        whenever(mockTracer.activeSpan()).thenReturn(mockWorkflowSpan)

        // Create real workflow instance
        val workflow =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(TestWorkflow::class.java)
                .workflowMethod(workflowMethod)
                .extraRequestData(ExtraRequestData())
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflow)
                .clock(Clock.systemUTC())
                .executorService(executorService)
                .build()

        // Create test action and method
        val testAction = TestAction()
        val method: Method = TestAction::class.java.getDeclaredMethod(actionMethod, String::class.java)

        // Create real ExecuteActionRequest
        val request =
            ExecuteActionRequest.builder()
                .actionObject(testAction)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("test"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()

        // Set up mocks for span creation
        val mockSpanBuilder = mock<SpanBuilder>()
        val mockSpan = mock<Span>()

        whenever(mockTracer.buildSpan(any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.withTag(any<String>(), any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.start()).thenReturn(mockSpan)

        val result = collector.createActionSpan(request)

        assertNotNull(result)

        // Verify the span was NOT created with an explicit parent context
        // (it relies on the active span automatically being used as parent)
        verify(mockSpanBuilder, never()).asChildOf(any<SpanContext>())

        // Verify span tags were set correctly
        verify(mockSpanBuilder)
            .withTag(
                ExecutionMetricsCollector.TAG_COMPONENT,
                ExecutionMetricsCollector.RESOURCE_TEMPO_ACTION
            )
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_ACTION_CLASS, actionClass)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_ACTION_METHOD, actionMethod)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_CLASS, workflowClass)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_METHOD, workflowMethod)
    }

    @Test
    fun testCreateWorkflowSpan_withoutParentContext() {
        val workflowClass = "TestWorkflow"
        val workflowMethod = "workflowMethod"
        val workflowStatus = "RUNNING"

        // Create real workflow instance
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(TestWorkflow::class.java)
                .workflowMethod(workflowMethod)
                .status(WorkflowInstance.Status.RUNNING)
                .extraRequestData(ExtraRequestData())
                .build()

        val mockSpanBuilder = mock<SpanBuilder>()
        val mockSpan = mock<Span>()

        whenever(mockTracer.buildSpan(any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.withTag(any<String>(), any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.start()).thenReturn(mockSpan)

        val result = collector.createWorkflowSpan(workflowInstance)

        assertNotNull(result)

        // Verify the span name
        val spanNameCaptor = argumentCaptor<String>()
        verify(mockTracer).buildSpan(spanNameCaptor.capture())
        assertEquals(
            String.format("workflow.%s.%s", workflowClass, workflowMethod),
            spanNameCaptor.firstValue
        )

        // Verify tags were set with camelCase
        verify(mockSpanBuilder)
            .withTag(
                ExecutionMetricsCollector.TAG_COMPONENT,
                ExecutionMetricsCollector.RESOURCE_TEMPO_WORKFLOW
            )
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_CLASS, workflowClass)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_METHOD, workflowMethod)
        verify(mockSpanBuilder).withTag(ExecutionMetricsCollector.TAG_WORKFLOW_STATUS, workflowStatus)
    }

    @Test
    fun testCreateWorkflowSpan_withParentContext() {
        val workflowClass = "TestWorkflow"
        val workflowMethod = "workflowMethod"
        val workflowStatus = "RUNNING"

        // Create a mock parent span and store its context
        val mockParentSpan = mock<Span>()
        val mockParentContext = mock<SpanContext>()
        whenever(mockParentSpan.context()).thenReturn(mockParentContext)
        whenever(mockTracer.activeSpan()).thenReturn(mockParentSpan)

        val extraRequestData = ExtraRequestData()
        extraRequestData.storeSpanContext(mockTracer)

        // Create real workflow instance
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(TestWorkflow::class.java)
                .workflowMethod(workflowMethod)
                .status(WorkflowInstance.Status.RUNNING)
                .extraRequestData(extraRequestData)
                .build()

        // Set up mocks for span creation
        val mockSpanBuilder = mock<SpanBuilder>()
        val mockSpan = mock<Span>()

        whenever(mockTracer.buildSpan(any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.withTag(any<String>(), any<String>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.asChildOf(any<SpanContext>())).thenReturn(mockSpanBuilder)
        whenever(mockSpanBuilder.start()).thenReturn(mockSpan)
        whenever(mockTracer.extract<Any>(any(), any())).thenReturn(mockParentContext)

        val result = collector.createWorkflowSpan(workflowInstance)

        assertNotNull(result)

        // Verify the span was created as a child of the parent context
        verify(mockSpanBuilder).asChildOf(mockParentContext)
    }
}
