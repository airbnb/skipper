package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import kotlin.reflect.KClass
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StateMachineEventResolverTest {
    private sealed class ResolverTestEvent : StateMachineEvent() {
        object Go : ResolverTestEvent()

        data class Complete(val reason: String = "done") : ResolverTestEvent()

        object Skip : ResolverTestEvent()
    }

    private enum class ResolverTestState { IDLE, DONE }

    @Suppress("UNCHECKED_CAST")
    private open class ResolverTestStateMachine : SkipperStateMachine<ResolverTestState, ResolverTestEvent, String>(ResolverTestState.IDLE) {
        override fun StateMachineBuilder<ResolverTestState, ResolverTestEvent, String>.define() {
            state(ResolverTestState.DONE) { terminal() }
        }
    }

    private fun createResolver(
        handlerEventClasses: List<KClass<out ResolverTestEvent>> = emptyList(),
    ): StateMachineEventResolver<ResolverTestEvent> =
        StateMachineEventResolver(
            stateMachineClass = ResolverTestStateMachine::class.java,
            handlerEventClasses = handlerEventClasses,
        )

    // ── Discovery ──

    @Test
    fun `discovers sealed subclasses of the event base class`() {
        val resolver = createResolver()
        assertThat(resolver.eventClassesByAlias.keys).containsExactlyInAnyOrder("Go", "Complete", "Skip")
    }

    // ── eventTypeAlias ──

    @Test
    fun `eventTypeAlias returns simple name for each event subclass`() {
        val resolver = createResolver()
        assertThat(resolver.eventTypeAlias(ResolverTestEvent.Go::class)).isEqualTo("Go")
        assertThat(resolver.eventTypeAlias(ResolverTestEvent.Complete::class)).isEqualTo("Complete")
        assertThat(resolver.eventTypeAlias(ResolverTestEvent.Skip::class)).isEqualTo("Skip")
    }

    // ── resolveEventClass ──

    @Test
    fun `resolveEventClass returns class for valid alias`() {
        val resolver = createResolver()
        assertThat(resolver.resolveEventClass("Go", "wf-1")).isEqualTo(ResolverTestEvent.Go::class.java)
    }

    @Test
    fun `resolveEventClass throws for unknown alias`() {
        val resolver = createResolver()
        assertThatThrownBy { resolver.resolveEventClass("DoesNotExist", "wf-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown event type alias 'DoesNotExist'")
    }

    // ── serializeEventPayload ──

    @Test
    fun `serializeEventPayload returns empty object node for singleton object event`() {
        val resolver = createResolver()
        val payload = resolver.serializeEventPayload(ResolverTestEvent.Go)
        // Object singletons serialize to {} (empty ObjectNode), not null
        assertThat(payload).isNotNull
        assertThat(payload!!.size()).isEqualTo(0)
    }

    @Test
    fun `serializeEventPayload returns non-null for data class event`() {
        val resolver = createResolver()
        val payload = resolver.serializeEventPayload(ResolverTestEvent.Complete("finished"))
        assertThat(payload).isNotNull
        assertThat(payload!!.get("reason").asText()).isEqualTo("finished")
    }

    // ── materializeEvent ──

    @Test
    fun `materializeEvent reconstructs singleton event`() {
        val resolver = createResolver()
        val rs = RuntimeState()
        with(StateMachineStateCodec) { rs.eventTypeId("Go") }

        val record = CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 1_000_000_000L)
        val event = resolver.materializeEvent(record, rs, "wf-1")

        assertThat(event).isSameAs(ResolverTestEvent.Go)
    }

    @Test
    fun `materializeEvent reconstructs data class event from payload`() {
        val resolver = createResolver()
        val rs = RuntimeState()
        with(StateMachineStateCodec) { rs.eventTypeId("Complete") }

        val payload = resolver.serializeEventPayload(ResolverTestEvent.Complete("test-reason"))
        val record = CompactEventRecord(typeId = 0, payload = payload, receivedAtEpochNanos = 1_000_000_000L)
        val event = resolver.materializeEvent(record, rs, "wf-1")

        assertThat(event).isInstanceOf(ResolverTestEvent.Complete::class.java)
        assertThat((event as ResolverTestEvent.Complete).reason).isEqualTo("test-reason")
    }

    @Test
    fun `materializeEvent uses default constructor when payload is null for data class`() {
        val resolver = createResolver()
        val rs = RuntimeState()
        with(StateMachineStateCodec) { rs.eventTypeId("Complete") }

        val record = CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 1_000_000_000L)
        val event = resolver.materializeEvent(record, rs, "wf-1")

        assertThat(event).isInstanceOf(ResolverTestEvent.Complete::class.java)
        assertThat((event as ResolverTestEvent.Complete).reason).isEqualTo("done")
    }

    // ── Handler class merging ──

    @Test
    fun `handler classes merge with discovered classes without duplicates`() {
        val resolver = createResolver(
            handlerEventClasses = listOf(ResolverTestEvent.Go::class, ResolverTestEvent.Complete::class),
        )
        assertThat(resolver.eventClassesByAlias.keys).containsExactlyInAnyOrder("Go", "Complete", "Skip")
    }

    // ── @EventAlias rename tolerance ──

    private sealed class AliasedEvent : StateMachineEvent() {
        @EventAlias("OldComplete")
        data class NewComplete(val reason: String = "done") : AliasedEvent()

        object Plain : AliasedEvent()
    }

    private enum class AliasedTestState { IDLE, DONE }

    private open class AliasedTestStateMachine : SkipperStateMachine<AliasedTestState, AliasedEvent, String>(AliasedTestState.IDLE) {
        override fun StateMachineBuilder<AliasedTestState, AliasedEvent, String>.define() {
            state(AliasedTestState.DONE) { terminal() }
        }
    }

    private fun createAliasedResolver(aliasMigrations: Map<String, String> = emptyMap(),): StateMachineEventResolver<AliasedEvent> =
        StateMachineEventResolver(
            stateMachineClass = AliasedTestStateMachine::class.java,
            handlerEventClasses = emptyList(),
            aliasMigrations = aliasMigrations,
        )

    @Test
    fun `eventTypeAlias prefers EventAlias value over simple class name`() {
        val resolver = createAliasedResolver()
        assertThat(resolver.eventTypeAlias(AliasedEvent.NewComplete::class)).isEqualTo("OldComplete")
        assertThat(resolver.eventTypeAlias(AliasedEvent.Plain::class)).isEqualTo("Plain")
    }

    @Test
    fun `eventClassesByAlias keys an annotated event by its EventAlias not its class name`() {
        val resolver = createAliasedResolver()
        assertThat(resolver.eventClassesByAlias.keys).containsExactlyInAnyOrder("OldComplete", "Plain")
    }

    @Test
    fun `resolveEventClass remaps a persisted alias through aliasMigrations to the current class`() {
        val resolver = createAliasedResolver(aliasMigrations = mapOf("ReallyOldName" to "OldComplete"))
        assertThat(resolver.resolveEventClass("ReallyOldName", "wf-1"))
            .isEqualTo(AliasedEvent.NewComplete::class.java)
    }

    // ── aliasMigrations validation ──

    @Test
    fun `eventClassesByAlias throws when a migration key collides with a live event alias`() {
        // "OldComplete" is NewComplete's live @EventAlias, so it must not also be a migration key.
        val resolver = createAliasedResolver(aliasMigrations = mapOf("OldComplete" to "Plain"))
        assertThatThrownBy { resolver.eventClassesByAlias }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not collide with current event aliases")
            .hasMessageContaining("OldComplete")
    }

    @Test
    fun `eventClassesByAlias throws when a migration target does not resolve to a live event alias`() {
        val resolver = createAliasedResolver(aliasMigrations = mapOf("ReallyOld" to "DoesNotExist"))
        assertThatThrownBy { resolver.eventClassesByAlias }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must resolve to a current event alias")
            .hasMessageContaining("DoesNotExist")
    }

    // ── validateOrThrow ──

    @Test
    fun `validateOrThrow surfaces an aliasMigrations misconfiguration instead of staying silent`() {
        // Proves validateOrThrow actually forces the lazy eventClassesByAlias validation rather than
        // being a no-op: a migration key colliding with a live alias must fail here, not at first decode.
        val resolver = createAliasedResolver(aliasMigrations = mapOf("OldComplete" to "Plain"))
        assertThatThrownBy { resolver.validateOrThrow() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not collide with current event aliases")
            .hasMessageContaining("OldComplete")
    }

    @Test
    fun `validateOrThrow does not throw for a well-formed resolver`() {
        val resolver = createResolver()
        assertThatCode { resolver.validateOrThrow() }.doesNotThrowAnyException()
    }

    // ── Duplicate-alias guard ──

    private sealed class DuplicateAliasEvent : StateMachineEvent() {
        @EventAlias("Dup")
        object First : DuplicateAliasEvent()

        @EventAlias("Dup")
        object Second : DuplicateAliasEvent()
    }

    private enum class DuplicateAliasState { IDLE, DONE }

    private open class DuplicateAliasStateMachine : SkipperStateMachine<DuplicateAliasState, DuplicateAliasEvent, String>(DuplicateAliasState.IDLE) {
        override fun StateMachineBuilder<DuplicateAliasState, DuplicateAliasEvent, String>.define() {
            state(DuplicateAliasState.DONE) { terminal() }
        }
    }

    @Test
    fun `eventClassesByAlias throws when two events resolve to the same alias`() {
        val resolver = StateMachineEventResolver(
            stateMachineClass = DuplicateAliasStateMachine::class.java,
            handlerEventClasses = emptyList<KClass<out DuplicateAliasEvent>>(),
        )
        assertThatThrownBy { resolver.eventClassesByAlias }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be unique")
            .hasMessageContaining("Dup")
    }
}
