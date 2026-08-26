package com.airbnb.skipper

import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SuspendSupport] — the coroutine bridge utility.
 */
class SuspendSupportTest {
    // ──────────────────────────────────────────────────────────────────────────
    // Test fixtures
    // ──────────────────────────────────────────────────────────────────────────

    @Suppress("unused")
    private class Target {
        // Regular (non-suspend) methods
        fun regularNoArgs(): String = "regular-no-args"

        fun regularWithArg(input: String): String = "regular-$input"

        fun regularReturnsVoid() = Unit

        // Suspend methods
        suspend fun suspendNoArgs(): String = "suspend-no-args"

        suspend fun suspendWithArg(input: String): String = "suspend-$input"

        suspend fun suspendReturnsUnit() = Unit

        suspend fun suspendThrows(): String = throw IllegalStateException("boom")

        // Error subclass (RetryableError/WaitSignal extend Error, not Exception).
        // This verifies the InvocationTargetException unwrap path handles Error correctly.
        suspend fun suspendThrowsRetryable(): String = throw RetryableError("retryable")

        // Exercises the COROUTINE_SUSPENDED branch: delay(1) causes the coroutine to actually
        // suspend rather than complete synchronously, so method.invoke() returns the
        // COROUTINE_SUSPENDED sentinel. invokeSuspendFunctionAsync must handle this correctly.
        suspend fun suspendWithDelay(): String {
            delay(1)
            return "delayed-result"
        }

        // Exercises withContext(Dispatchers.IO) inside a suspend function to verify
        // that ThreadLocal context survives coroutine dispatcher switches via the
        // ScopeSnapshotContextElement installed by invokeSuspendFunctionAsync.
        suspend fun suspendWithDispatcherSwitch(): String {
            return withContext(Dispatchers.IO) {
                THREAD_LOCAL.get() ?: "NO_CONTEXT"
            }
        }
    }

    companion object {
        val THREAD_LOCAL = ThreadLocal<String?>()
    }

    private val target = Target()

    private fun method(
        name: String,
        vararg paramTypes: Class<*>
    ): Method = Target::class.java.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }

    // ──────────────────────────────────────────────────────────────────────────
    // isSuspendFunction
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `isSuspendFunction returns false for regular method without args`() {
        assertFalse(SuspendSupport.isSuspendFunction(method("regularNoArgs")))
    }

    @Test
    fun `isSuspendFunction returns false for regular method with arg`() {
        assertFalse(SuspendSupport.isSuspendFunction(method("regularWithArg", String::class.java)))
    }

    @Test
    fun `isSuspendFunction returns true for suspend method without user args`() {
        // Kotlin compiles suspendNoArgs() to suspendNoArgs(Continuation): Object
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendNoArgs" }
        assertTrue(SuspendSupport.isSuspendFunction(m))
    }

    @Test
    fun `isSuspendFunction returns true for suspend method with user arg`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        assertTrue(SuspendSupport.isSuspendFunction(m))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getUserParameterCount
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `getUserParameterCount returns 0 for regular method without args`() {
        assertEquals(0, SuspendSupport.getUserParameterCount(method("regularNoArgs")))
    }

    @Test
    fun `getUserParameterCount returns 1 for regular method with arg`() {
        assertEquals(1, SuspendSupport.getUserParameterCount(method("regularWithArg", String::class.java)))
    }

    @Test
    fun `getUserParameterCount returns 0 for suspend method without user args`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendNoArgs" }
        assertEquals(0, SuspendSupport.getUserParameterCount(m))
    }

    @Test
    fun `getUserParameterCount returns 1 for suspend method with 1 user arg`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        assertEquals(1, SuspendSupport.getUserParameterCount(m))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getUserParameterTypes
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `getUserParameterTypes returns empty array for regular method without args`() {
        assertThat(SuspendSupport.getUserParameterTypes(method("regularNoArgs"))).isEmpty()
    }

    @Test
    fun `getUserParameterTypes excludes Continuation for suspend methods`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        val types = SuspendSupport.getUserParameterTypes(m)
        assertThat(types).hasSize(1)
        assertThat(types[0]).isEqualTo(String::class.java)
        // Continuation must not be present
        assertThat(types).doesNotContain(Continuation::class.java)
    }

    @Test
    fun `getUserParameterTypes returns empty array for suspend method without user args`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendNoArgs" }
        assertThat(SuspendSupport.getUserParameterTypes(m)).isEmpty()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // filterContinuationArgs
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `filterContinuationArgs returns unchanged array when no Continuation present`() {
        val args: Array<Any?> = arrayOf("hello", 42)
        assertThat(SuspendSupport.filterContinuationArgs(args)).containsExactly("hello", 42)
    }

    @Test
    fun `filterContinuationArgs strips trailing Continuation`() {
        val fakeContinuation = object : Continuation<Any?> {
            override val context get() = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) {}
        }
        val args: Array<Any?> = arrayOf("hello", fakeContinuation)
        val filtered = SuspendSupport.filterContinuationArgs(args)
        assertThat(filtered).containsExactly("hello")
    }

    @Test
    fun `filterContinuationArgs returns empty array when only Continuation present`() {
        val fakeContinuation = object : Continuation<Any?> {
            override val context get() = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) {}
        }
        val args: Array<Any?> = arrayOf(fakeContinuation)
        val filtered = SuspendSupport.filterContinuationArgs(args)
        assertThat(filtered).isEmpty()
    }

    @Test
    fun `filterContinuationArgs returns unchanged empty array`() {
        val args: Array<Any?> = emptyArray()
        assertThat(SuspendSupport.filterContinuationArgs(args)).isEmpty()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getEffectiveReturnType
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `getEffectiveReturnType returns returnType for regular method`() {
        assertThat(SuspendSupport.getEffectiveReturnType(method("regularWithArg", String::class.java)))
            .isEqualTo(String::class.java)
    }

    @Test
    fun `getEffectiveReturnType returns Kotlin return type for suspend method returning String`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        // Kotlin metadata records the declared return type (String), not the bytecode return type (Object)
        assertThat(SuspendSupport.getEffectiveReturnType(m)).isEqualTo(String::class.java)
    }

    @Test
    fun `getEffectiveReturnType returns Unit for suspend method returning Unit`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendReturnsUnit" }
        assertThat(SuspendSupport.getEffectiveReturnType(m)).isEqualTo(Unit::class.java)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // invokeSuspendFunctionAsync
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `invokeSuspendFunctionAsync invokes suspend method without user args`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendNoArgs" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(m, target).get()
        assertThat(result).isEqualTo("suspend-no-args")
    }

    @Test
    fun `invokeSuspendFunctionAsync invokes suspend method with user arg`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(m, target, ContextSnapshot.NOOP, "world").get()
        assertThat(result).isEqualTo("suspend-world")
    }

    @Test
    fun `invokeSuspendFunctionAsync returns null for suspend Unit method`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendReturnsUnit" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(m, target).get()
        // Unit suspend functions return null — Unit is normalized to null so that
        // action checkpoints storing the result can be serialized by the storage layer.
        assertThat(result).isNull()
    }

    @Test
    fun `invokeSuspendFunctionAsync propagates exceptions thrown inside suspend method`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendThrows" }
        val future = SuspendSupport.invokeSuspendFunctionAsync(m, target)
        assertThatThrownBy { future.get() }
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("boom")
    }

    @Test
    fun `invokeSuspendFunctionAsync propagates Error subclasses thrown inside suspend method`() {
        // RetryableError and WaitSignal both extend Error (not Exception). The bridge must
        // correctly unwrap InvocationTargetException and store the Error in the future, so the
        // Skipper engine can classify it (retry vs. hibernate) correctly.
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendThrowsRetryable" }
        val future = SuspendSupport.invokeSuspendFunctionAsync(m, target)
        assertThatThrownBy { future.get() }
            .hasCauseInstanceOf(RetryableError::class.java)
            .hasRootCauseMessage("retryable")
    }

    @Test
    fun `invokeSuspendFunctionAsync handles COROUTINE_SUSPENDED when function actually suspends`() {
        // delay(1) causes the suspend function to genuinely suspend: method.invoke() returns
        // the COROUTINE_SUSPENDED sentinel rather than the result directly.
        // invokeSuspendFunctionAsync must handle this correctly via the Continuation.
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithDelay" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(m, target).get()
        assertThat(result).isEqualTo("delayed-result")
    }

    @Test
    fun `invokeSuspendFunction propagates non-InvocationTargetException errors`() {
        // Passing the wrong number of user arguments causes method.invoke() to throw
        // IllegalArgumentException directly (NOT wrapped in InvocationTargetException).
        // This exercises the outer catch(Throwable) block in invokeSuspendFunction, which
        // exists to prevent the runBlocking scope from hanging indefinitely.
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendWithArg" }
        val future = SuspendSupport.invokeSuspendFunctionAsync(m, target)
        assertThatThrownBy { future.get() } // suspendWithArg expects 1 user arg but we pass none
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `invokeSuspendFunctionAsync returns a CompletableFuture`() {
        val m = Target::class.java.getDeclaredMethods().first { it.name == "suspendNoArgs" }
        val future = SuspendSupport.invokeSuspendFunctionAsync(m, target)
        assertThat(future).isInstanceOf(java.util.concurrent.CompletableFuture::class.java)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Context propagation across dispatcher switches
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `invokeSuspendFunctionAsync propagates context through withContext dispatcher switch`() {
        // Simulates the real-world scenario: WorkflowExecutor sets a ThreadLocal, then
        // the action body calls withContext(Dispatchers.IO) and reads the ThreadLocal
        // on the IO thread. Without ScopeSnapshotContextElement the ThreadLocal is lost.
        THREAD_LOCAL.set("test-context-value")
        try {
            val snapshot = object : ContextSnapshot {
                private val captured = THREAD_LOCAL.get()

                override fun activate(): AutoCloseable {
                    val previous = THREAD_LOCAL.get()
                    THREAD_LOCAL.set(captured)
                    return AutoCloseable { THREAD_LOCAL.set(previous) }
                }
            }
            val m = Target::class.java.getDeclaredMethods()
                .first { it.name == "suspendWithDispatcherSwitch" }
            val result = SuspendSupport.invokeSuspendFunctionAsync(m, target, snapshot).get()
            assertThat(result).isEqualTo("test-context-value")
        } finally {
            THREAD_LOCAL.remove()
        }
    }

    @Test
    fun `invokeSuspendFunctionAsync with NOOP snapshot does not fail on dispatcher switch`() {
        // Verifies NOOP snapshot is harmless — the ThreadLocal is simply absent on the IO thread.
        THREAD_LOCAL.remove()
        val m = Target::class.java.getDeclaredMethods()
            .first { it.name == "suspendWithDispatcherSwitch" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(m, target).get()
        assertThat(result).isEqualTo("NO_CONTEXT")
    }
}
