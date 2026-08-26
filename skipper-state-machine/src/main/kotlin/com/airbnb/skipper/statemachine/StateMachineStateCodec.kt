package com.airbnb.skipper.statemachine

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.smile.SmileFactory
import com.fasterxml.jackson.dataformat.smile.SmileGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.luben.zstd.Zstd
import java.time.Duration
import java.time.Instant

/**
 * Stateless codec that encapsulates all compact state format concerns for [SkipperStateMachine].
 *
 * This object owns the binary serialization format (SMILE + Zstd), the compact data classes that
 * represent the persisted snapshot, and all pure data transformations between the compact
 * representation and the richer types used at runtime (timestamps, spans, transition records).
 *
 * It has **zero Skipper dependencies** — it operates purely on data structures and primitives.
 * [SkipperStateMachine] delegates all encode/decode, timestamp conversion, dictionary management,
 * and transition record building to this codec, keeping the state machine focused on orchestration.
 *
 * Extension functions (e.g., timestamp conversions, span conversions, dictionary helpers) are
 * defined inside the `object` scope and are accessible via `with(StateMachineStateCodec) { ... }`.
 */
internal object StateMachineStateCodec {
    /**
     * Format version stamped on every snapshot/journal segment this code writes.
     *
     * Split from the minimum-supported version so the format can evolve without a fail-closed
     * decode. Bump a writer version only for a reader-visible semantic change; routine additive
     * (nullable/defaulted) fields need no bump because [snapshotMapper] ignores unknown properties.
     */
    private const val COMPACT_STATE_SNAPSHOT_WRITER_VERSION = 1
    private const val COMPACT_JOURNAL_SEGMENT_WRITER_VERSION = 1

    /**
     * Lowest format version this code will decode. Decode tolerates any version `>= MIN` with no
     * upper bound, so a pod running older code keeps reading blobs that a newer pod rewrote during a
     * rolling deploy. Raise a minimum only to retire a format after verifying zero in-flight
     * instances remain below it.
     */
    private const val MIN_SUPPORTED_STATE_SNAPSHOT_VERSION = 1
    private const val MIN_SUPPORTED_JOURNAL_SEGMENT_VERSION = 1

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MAX_DECOMPRESSED_SIZE = 512 * 1024 * 1024 // 512 MB
    private const val COMPRESSION_LEVEL = 6

    /**
     * Placeholder for a null component in a checkpoint name. Every formatter uses the same sentinel,
     * and the surrounding key structure keeps distinct records disjoint even when a component is null.
     */
    private const val NULL_KEY_SENTINEL = "_"

    /**
     * SMILE binary mapper with back-references for repeated field names/values.
     * Combined with Zstd compression, this achieves ~33-65x size reduction vs the
     * old JSON + @JsonTypeInfo(CLASS) format.
     *
     * `FAIL_ON_UNKNOWN_PROPERTIES=false` lets a reader drop fields a newer writer added.
     *
     * `READ_UNKNOWN_ENUM_VALUES_AS_NULL` is set as the correct default for forward compatibility, but
     * note it is currently a no-op in practice: the embedded enum fields ([CompactTransitionRecord]'s
     * `outcome`/`triggerKind`) are non-null, so an unknown value deserializes to `null` and the Kotlin
     * module then rejects it. Adding a new [SkipperStateMachine.TriggerKind] /
     * [SkipperStateMachine.TransitionOutcome] constant therefore still requires first making the
     * relevant field nullable (or staging the constant behind a version) before any writer emits it.
     * State and event-type names are plain strings resolved separately by [resolveStateName] /
     * [resolveEventTypeAlias], which fail loudly on an unknown value.
     */
    private val snapshotMapper = ObjectMapper(
        SmileFactory().apply {
            enable(SmileGenerator.Feature.CHECK_SHARED_NAMES)
            enable(SmileGenerator.Feature.CHECK_SHARED_STRING_VALUES)
        },
    ).registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)

    /** Exposes the Smile mapper for event payload serialization in [SkipperStateMachine]. */
    val mapper: ObjectMapper get() = snapshotMapper

    // ── Compact data classes ──

    /** Persisted runtime snapshot before Zstd compression. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    data class CompactStateSnapshot(
        val version: Int = COMPACT_STATE_SNAPSHOT_WRITER_VERSION,
        val stateNames: List<String> = emptyList(),
        val eventTypeNames: List<String> = emptyList(),
        val events: List<CompactEventRecord> = emptyList(),
        val afterHooks: List<CompactAfterHookRecord> = emptyList(),
        val timeouts: List<CompactTimeoutRecord> = emptyList(),
        val transitions: List<CompactTransitionRecord> = emptyList(),
    )

    /** One durable event-log entry stored in the compact runtime snapshot. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactEventRecord(
        val typeId: Int,
        val payload: JsonNode? = null,
        val receivedAtEpochNanos: Long,
    )

    /** One after-hook firing stored in the compact runtime snapshot. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactAfterHookRecord(
        val firedAtEpochNanos: Long,
        val durationNanos: Long,
        /**
         * Stable per-state hook identity (declaration ordinal or explicit id). Nullable for backward
         * compatibility with records written before hook ids existed; such legacy records fall back
         * to `(firedAtEpochNanos, durationNanos)` for upsert matching.
         */
        val hookId: String? = null,
        val handlerSpan: CompactSpan? = null,
    )

    /** One timeout firing stored in the compact runtime snapshot. */
    data class CompactTimeoutRecord(
        val firedAtEpochNanos: Long,
        val durationNanos: Long,
    )

    /** Nanosecond-precision span encoded as start epoch nanos plus duration nanos. */
    data class CompactSpan(
        val startEpochNanos: Long,
        val durationNanos: Long,
    )

    /** Compact representation of one admin-visible transition row. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactTransitionRecord(
        val fromStateId: Int? = null,
        val toStateId: Int? = null,
        val outcome: SkipperStateMachine.TransitionOutcome,
        val triggerKind: SkipperStateMachine.TriggerKind,
        val stateEntryEpochNanos: Long? = null,
        val eventIndex: Int? = null,
        val timeoutDurationNanos: Long? = null,
        val span: CompactSpan,
        val handlerSpan: CompactSpan? = null,
        val initialMiddlewareSpan: CompactSpan? = null,
        val beforeMiddlewareSpan: CompactSpan? = null,
        val onExitSpan: CompactSpan? = null,
        val onEntrySpan: CompactSpan? = null,
        val afterMiddlewareSpan: CompactSpan? = null,
        val terminalMiddlewareSpan: CompactSpan? = null,
        /**
         * Per-run sequence number of this transition, assigned at its first journal write and carried
         * through every phase write so they share a replay-stable checkpoint name. Reconstructed
         * deterministically because the event loop re-walks the durable log in the same order each
         * replay. Not surfaced in the admin snapshot; nullable for records written before it existed.
         */
        val seq: Int? = null,
    )

    /**
     * Versioned single-record journal segment stored as the result of a Skipper action checkpoint.
     *
     * Segments deliberately reuse the same Smile + Zstd mapper as the main snapshot so replay
     * metadata stays compact. A segment contains exactly one logical record; this keeps Skipper
     * action checkpoint results small and lets the state machine append the replayed value into the
     * rebuilt runtime snapshot. Timestamp markers are control records: they are not rendered by
     * admin directly, but later visible transition/after-hook records consume them as durable
     * first-attempt span starts.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactJournalSegment(
        val version: Int = COMPACT_JOURNAL_SEGMENT_WRITER_VERSION,
        val transition: CompactTransitionRecord? = null,
        val afterHook: CompactAfterHookRecord? = null,
        val timeout: CompactTimeoutRecord? = null,
        val timestampMarkerEpochNanos: Long? = null,
    )

    /** Mutable decoded snapshot used while replay rebuilds runtime/admin state. */
    data class RuntimeState(
        val stateNames: MutableList<String> = mutableListOf(),
        val eventTypeNames: MutableList<String> = mutableListOf(),
        val events: MutableList<CompactEventRecord> = mutableListOf(),
        val afterHooks: MutableList<CompactAfterHookRecord> = mutableListOf(),
        val timeouts: MutableList<CompactTimeoutRecord> = mutableListOf(),
        val transitions: MutableList<CompactTransitionRecord> = mutableListOf(),
    )

    /** Transition metadata captured before dictionaries are resolved into compact integer ids. */
    data class PendingTransition(
        val fromStateName: String?,
        val toStateName: String?,
        val outcome: SkipperStateMachine.TransitionOutcome,
        val triggerKind: SkipperStateMachine.TriggerKind,
        val transitionStart: SkipperStateMachine.PreciseTimestamp,
        val timeoutDurationNanos: Long?,
        val handlerSpan: SkipperStateMachine.PreciseTimeRange? = null,
        val eventIndex: Int? = null,
        val initialMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        val beforeMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        val onExitSpan: SkipperStateMachine.PreciseTimeRange? = null,
    )

    // ── Snapshot encode / decode ──

    /** Encodes the full runtime snapshot to compressed Smile bytes for the workflow `@StateField`. */
    fun encode(state: RuntimeState): ByteArray {
        val raw = snapshotMapper.writeValueAsBytes(state.toSnapshot())
        return Zstd.compress(raw, COMPRESSION_LEVEL)
    }

    /**
     * Decodes the compressed workflow snapshot.
     *
     * Empty payloads represent a brand-new workflow. Non-empty payloads must be Zstd frames
     * produced by [encode], bounded by [MAX_DECOMPRESSED_SIZE], and carrying a snapshot format
     * version `>= MIN_SUPPORTED_STATE_SNAPSHOT_VERSION` (newer versions are tolerated for forward
     * compatibility during rolling deploys).
     */
    fun decode(
        payload: ByteArray,
        workflowId: String
    ): RuntimeState {
        if (payload.isEmpty()) {
            return RuntimeState()
        }

        // encode() uses Zstd.compress() (frame compression, not streaming), so decompressedSize()
        // always returns the correct original size from the frame header. The range check also
        // rejects 0, which Zstd returns when the frame size is unknown (streaming-compressed data).
        val decompressedSize = Zstd.decompressedSize(payload)
        require(decompressedSize in 1..MAX_DECOMPRESSED_SIZE) {
            "Invalid decompressed size $decompressedSize for workflow $workflowId (max $MAX_DECOMPRESSED_SIZE)"
        }
        val raw = Zstd.decompress(payload, decompressedSize.toInt())
        val snapshot = snapshotMapper.readValue(raw, CompactStateSnapshot::class.java)
        require(snapshot.version >= MIN_SUPPORTED_STATE_SNAPSHOT_VERSION) {
            "Unsupported SkipperStateMachine snapshot version ${snapshot.version} for workflow $workflowId " +
                "(minimum supported $MIN_SUPPORTED_STATE_SNAPSHOT_VERSION)"
        }
        return RuntimeState(
            stateNames = snapshot.stateNames.toMutableList(),
            eventTypeNames = snapshot.eventTypeNames.toMutableList(),
            events = snapshot.events.toMutableList(),
            afterHooks = snapshot.afterHooks.toMutableList(),
            timeouts = snapshot.timeouts.toMutableList(),
            transitions = snapshot.transitions.toMutableList(),
        )
    }

    // ── Journal segment encode / decode ──

    /** Encodes one transition journal record as compressed Smile bytes. */
    fun encodeTransitionSegment(record: CompactTransitionRecord): ByteArray = encodeJournalSegment(CompactJournalSegment(transition = record))

    /** Decodes one transition journal record returned by Skipper action replay. */
    fun decodeTransitionSegment(
        payload: ByteArray,
        workflowId: String
    ): CompactTransitionRecord =
        decodeJournalSegment(payload, workflowId).transition
            ?: error("Journal segment for workflow $workflowId did not contain a transition record")

    /** Encodes one after-hook journal record as compressed Smile bytes. */
    fun encodeAfterHookSegment(record: CompactAfterHookRecord): ByteArray = encodeJournalSegment(CompactJournalSegment(afterHook = record))

    /** Decodes one after-hook journal record returned by Skipper action replay. */
    fun decodeAfterHookSegment(
        payload: ByteArray,
        workflowId: String
    ): CompactAfterHookRecord =
        decodeJournalSegment(payload, workflowId).afterHook
            ?: error("Journal segment for workflow $workflowId did not contain an after-hook record")

    /** Encodes one timeout-firing journal record as compressed Smile bytes. */
    fun encodeTimeoutSegment(record: CompactTimeoutRecord): ByteArray = encodeJournalSegment(CompactJournalSegment(timeout = record))

    /** Decodes one timeout-firing journal record returned by Skipper action replay. */
    fun decodeTimeoutSegment(
        payload: ByteArray,
        workflowId: String
    ): CompactTimeoutRecord =
        decodeJournalSegment(payload, workflowId).timeout
            ?: error("Journal segment for workflow $workflowId did not contain a timeout record")

    /** Encodes a hidden durable timestamp marker used to seed a later visible span. */
    fun encodeTimestampMarkerSegment(epochNanos: Long): ByteArray =
        encodeJournalSegment(CompactJournalSegment(timestampMarkerEpochNanos = epochNanos))

    /** Decodes a hidden durable timestamp marker returned by Skipper action replay. */
    fun decodeTimestampMarkerSegment(
        payload: ByteArray,
        workflowId: String
    ): Long =
        decodeJournalSegment(payload, workflowId).timestampMarkerEpochNanos
            ?: error("Journal segment for workflow $workflowId did not contain a timestamp marker")

    /** Encodes a single-record journal segment with the shared compact binary format. */
    private fun encodeJournalSegment(segment: CompactJournalSegment): ByteArray {
        val raw = snapshotMapper.writeValueAsBytes(segment)
        return Zstd.compress(raw, COMPRESSION_LEVEL)
    }

    /**
     * Decodes and validates one compact journal segment.
     *
     * The exactly-one-record invariant prevents ambiguous replay where a single Skipper action
     * checkpoint could otherwise rebuild multiple admin records.
     */
    private fun decodeJournalSegment(
        payload: ByteArray,
        workflowId: String
    ): CompactJournalSegment {
        val decompressedSize = Zstd.decompressedSize(payload)
        require(decompressedSize in 1..MAX_DECOMPRESSED_SIZE) {
            "Invalid journal segment decompressed size $decompressedSize for workflow $workflowId (max $MAX_DECOMPRESSED_SIZE)"
        }
        val raw = Zstd.decompress(payload, decompressedSize.toInt())
        val segment = snapshotMapper.readValue(raw, CompactJournalSegment::class.java)
        require(segment.version >= MIN_SUPPORTED_JOURNAL_SEGMENT_VERSION) {
            "Unsupported SkipperStateMachine journal segment version ${segment.version} for workflow $workflowId " +
                "(minimum supported $MIN_SUPPORTED_JOURNAL_SEGMENT_VERSION)"
        }
        val recordCount = listOf(
            segment.transition,
            segment.afterHook,
            segment.timeout,
            segment.timestampMarkerEpochNanos,
        )
            .count { it != null }
        require(recordCount == 1) {
            "Invalid SkipperStateMachine journal segment for workflow $workflowId: expected exactly one record, found $recordCount"
        }
        return segment
    }

    // ── Timestamp nano conversions ──

    /** Converts a primitive precise timestamp to epoch nanoseconds with overflow checks. */
    fun SkipperStateMachine.PreciseTimestamp.toEpochNanos(): Long = Math.addExact(Math.multiplyExact(epochSecond, NANOS_PER_SECOND), nano.toLong())

    /** Converts epoch nanoseconds back to the primitive timestamp stored in admin snapshots. */
    fun Long.toPreciseTimestamp(): SkipperStateMachine.PreciseTimestamp {
        val epochSecond = Math.floorDiv(this, NANOS_PER_SECOND)
        val nano = Math.floorMod(this, NANOS_PER_SECOND).toInt()
        return SkipperStateMachine.PreciseTimestamp(epochSecond, nano)
    }

    /** Converts epoch nanoseconds to [Instant] for deadline arithmetic. */
    fun Long.toInstant(): Instant =
        toPreciseTimestamp().let {
            Instant.ofEpochSecond(it.epochSecond, it.nano.toLong())
        }

    /** Converts a [Duration] to nanoseconds for compact storage. */
    fun Duration.toNanosExact(): Long = toNanos()

    // ── Span conversions ──

    /** Expands a compact span to the public precise time-range representation. */
    fun CompactSpan.toPreciseTimeRange(): SkipperStateMachine.PreciseTimeRange {
        val start = startEpochNanos.toPreciseTimestamp()
        val end = Math.addExact(startEpochNanos, durationNanos).toPreciseTimestamp()
        return SkipperStateMachine.PreciseTimeRange(start, end)
    }

    /** Compacts a public precise time range into start epoch nanos plus duration nanos. */
    fun SkipperStateMachine.PreciseTimeRange.toCompactSpan(): CompactSpan =
        CompactSpan(
            startEpochNanos = start.toEpochNanos(),
            durationNanos = Math.addExact(
                Math.multiplyExact(end.epochSecond - start.epochSecond, NANOS_PER_SECOND),
                (end.nano - start.nano).toLong(),
            ),
        )

    // ── Name dictionary helpers ──

    /** Returns the compact dictionary id for [stateName], adding it when first observed. */
    fun RuntimeState.stateId(stateName: String): Int = getOrAdd(stateNames, stateName)

    /** Returns the compact dictionary id for [eventTypeAlias], adding it when first observed. */
    fun RuntimeState.eventTypeId(eventTypeAlias: String): Int = getOrAdd(eventTypeNames, eventTypeAlias)

    /** Returns an existing dictionary index for [value], or appends it and returns the new index. */
    private fun getOrAdd(
        values: MutableList<String>,
        value: String
    ): Int {
        val existingIndex = values.indexOf(value)
        if (existingIndex >= 0) {
            return existingIndex
        }
        values.add(value)
        return values.lastIndex
    }

    // ── State queries ──

    /** Returns the current state id from the latest transition that entered a state. */
    fun currentStateId(state: RuntimeState): Int? =
        state.transitions.lastOrNull { it.stateEntryEpochNanos != null && it.toStateId != null }?.toStateId

    /** Returns the state-entry timestamp for [currentStateId], if a state has been entered. */
    fun currentStateEntryEpochNanos(state: RuntimeState): Long? =
        state.transitions.lastOrNull { it.stateEntryEpochNanos != null && it.toStateId != null }?.stateEntryEpochNanos

    /**
     * Clears replay-derived histories while preserving dictionaries and the durable event log.
     *
     * Transitions, timeout firings, and after-hook records are rebuilt on every execution from
     * compact journal checkpoint results. This prevents duplicate append-only history when Skipper
     * replays a workflow from its existing persisted blob.
     */
    fun RuntimeState.clearReplayDerivedHistory() {
        afterHooks.clear()
        timeouts.clear()
        transitions.clear()
    }

    /** Resolves a compact state id back to its string name, failing with workflow context. */
    fun resolveStateName(
        state: RuntimeState,
        stateId: Int,
        workflowId: String
    ): String =
        state.stateNames.getOrElse(stateId) {
            error("Missing state id $stateId in workflow $workflowId")
        }

    /** Resolves a compact event type id back to its alias, failing with workflow context. */
    fun resolveEventTypeAlias(
        state: RuntimeState,
        typeId: Int,
        workflowId: String
    ): String =
        state.eventTypeNames.getOrElse(typeId) {
            error("Missing event type id $typeId in workflow $workflowId")
        }

    // ── Transition building ──

    /** Converts a runtime trigger into compact trigger kind and optional timeout duration. */
    fun triggerMetadata(trigger: TransitionTrigger<*>): Pair<SkipperStateMachine.TriggerKind, Long?> =
        when (trigger) {
            is TransitionTrigger.Event -> SkipperStateMachine.TriggerKind.EVENT to null
            is TransitionTrigger.Timeout -> SkipperStateMachine.TriggerKind.TIMEOUT to trigger.duration.toNanosExact()
            is TransitionTrigger.AutoTransition -> SkipperStateMachine.TriggerKind.AUTO_TRANSITION to null
            is TransitionTrigger.InitialState -> SkipperStateMachine.TriggerKind.INITIAL to null
        }

    /** Captures transition metadata before a [RuntimeState] dictionary is available. */
    fun buildPendingTransition(
        fromStateName: String?,
        toStateName: String?,
        outcome: SkipperStateMachine.TransitionOutcome,
        triggerKind: SkipperStateMachine.TriggerKind,
        timeoutDurationNanos: Long?,
        transitionStart: SkipperStateMachine.PreciseTimestamp,
        handlerSpan: SkipperStateMachine.PreciseTimeRange? = null,
        eventIndex: Int? = null,
        initialMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        beforeMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        onExitSpan: SkipperStateMachine.PreciseTimeRange? = null,
    ): PendingTransition =
        PendingTransition(
            fromStateName = fromStateName,
            toStateName = toStateName,
            outcome = outcome,
            triggerKind = triggerKind,
            transitionStart = transitionStart,
            timeoutDurationNanos = timeoutDurationNanos,
            handlerSpan = handlerSpan,
            eventIndex = eventIndex,
            initialMiddlewareSpan = initialMiddlewareSpan,
            beforeMiddlewareSpan = beforeMiddlewareSpan,
            onExitSpan = onExitSpan,
        )

    /** Resolves [PendingTransition] names into compact ids and creates a durable record. */
    fun RuntimeState.transitionRecord(
        pendingTransition: PendingTransition,
        span: SkipperStateMachine.PreciseTimeRange,
        stateEntryEpochNanos: Long? = null,
    ): CompactTransitionRecord =
        CompactTransitionRecord(
            fromStateId = pendingTransition.fromStateName?.let { stateName -> stateId(stateName) },
            toStateId = pendingTransition.toStateName?.let { stateName -> stateId(stateName) },
            outcome = pendingTransition.outcome,
            triggerKind = pendingTransition.triggerKind,
            stateEntryEpochNanos = stateEntryEpochNanos,
            eventIndex = pendingTransition.eventIndex,
            timeoutDurationNanos = pendingTransition.timeoutDurationNanos,
            span = span.toCompactSpan(),
            handlerSpan = pendingTransition.handlerSpan?.toCompactSpan(),
            initialMiddlewareSpan = pendingTransition.initialMiddlewareSpan?.toCompactSpan(),
            beforeMiddlewareSpan = pendingTransition.beforeMiddlewareSpan?.toCompactSpan(),
            onExitSpan = pendingTransition.onExitSpan?.toCompactSpan(),
        )

    // ── Transition materialization ──

    /** Expands a compact transition record into the public admin/query transition entry. */
    fun materializeTransition(
        state: RuntimeState,
        record: CompactTransitionRecord
    ): SkipperStateMachine.TransitionLogEntry =
        SkipperStateMachine.TransitionLogEntry(
            fromState = record.fromStateId?.let { state.stateNames.getOrNull(it) },
            toState = record.toStateId?.let { state.stateNames.getOrNull(it) },
            outcome = record.outcome,
            triggerKind = record.triggerKind,
            triggerName = deriveTriggerName(state, record),
            span = record.span.toPreciseTimeRange(),
            handlerSpan = record.handlerSpan?.toPreciseTimeRange(),
            eventIndex = record.eventIndex,
            initialMiddlewareSpan = record.initialMiddlewareSpan?.toPreciseTimeRange(),
            beforeMiddlewareSpan = record.beforeMiddlewareSpan?.toPreciseTimeRange(),
            onExitSpan = record.onExitSpan?.toPreciseTimeRange(),
            onEntrySpan = record.onEntrySpan?.toPreciseTimeRange(),
            afterMiddlewareSpan = record.afterMiddlewareSpan?.toPreciseTimeRange(),
            terminalMiddlewareSpan = record.terminalMiddlewareSpan?.toPreciseTimeRange(),
        )

    /** Returns the human-readable trigger label for a compact transition record. */
    fun deriveTriggerName(
        state: RuntimeState,
        record: CompactTransitionRecord
    ): String =
        when (record.triggerKind) {
            SkipperStateMachine.TriggerKind.EVENT ->
                record.eventIndex
                    ?.let { state.events.getOrNull(it) }
                    ?.let { state.eventTypeNames.getOrNull(it.typeId) }
                    ?: "Unknown"

            SkipperStateMachine.TriggerKind.TIMEOUT ->
                record.timeoutDurationNanos
                    ?.let { "timeout(${Duration.ofNanos(it)})" }
                    ?: "timeout"

            SkipperStateMachine.TriggerKind.AUTO_TRANSITION -> "auto"
            SkipperStateMachine.TriggerKind.INITIAL -> "initial"
        }

    // ── Journal checkpoint names ──

    /** Which hidden start-timestamp marker a [markerCheckpointName] write represents. */
    enum class MarkerKind { EVENT_HANDLER, TIMEOUT_HANDLER, INVALID }

    /**
     * Which durable write within a multi-write journal record this checkpoint represents.
     *
     * One transition row is built by several writes that progressively merge phase spans; each must
     * carry a distinct name (Skipper rejects a duplicate name within one execution pass), while the
     * rest of the name isolates the row from every other record. The framework always emits these in
     * a fixed order regardless of client edits, so the per-phase suffix is replay-stable.
     *
     * These are labels for **durable write points**, not a strict 1:1 mapping of lifecycle phases —
     * the set is deliberately asymmetric to match the write structure, and nothing depends on the
     * names beyond uniqueness-per-write and admin display:
     * - There is no `AFTER_MIDDLEWARE`: the after-transition middleware span is the last thing
     *   measured, so it is merged into the [COMPLETED] write rather than getting its own.
     * - The inline phases ([BEFORE_MIDDLEWARE], [ON_EXIT], [ON_ENTRY]) are each a single boundary
     *   write that finalizes the just-finished phase's span and starts the next. Terminal-state
     *   middleware, by contrast, is journaled by a separate two-write pair
     *   ([TERMINAL_STARTED], [TERMINAL_COMPLETED]) because it runs after the row is already
     *   [COMPLETED]; hence the started/completed split that the inline phases do not have.
     */
    enum class JournalPhase {
        /** Transition begins; records the start span. */
        STARTED,

        /** After before-transition middleware ran; merges its span. */
        BEFORE_MIDDLEWARE,

        /** After onExit hooks ran; merges their span and stamps the state-entry time. */
        ON_EXIT,

        /** After onEntry hooks ran; merges their span. */
        ON_ENTRY,

        /** Transition completes; merges the after-transition middleware span and final end time. */
        COMPLETED,

        /** Terminal-state middleware begins (attaches to the already-completed row). */
        TERMINAL_STARTED,

        /** Terminal-state middleware completes. */
        TERMINAL_COMPLETED,
    }

    /**
     * Stable checkpoint name for a transition record write: `sm:txn:<seq>:<phase>`.
     *
     * Keyed on the per-run transition [CompactTransitionRecord.seq], not a timestamp — the sequence is
     * reproduced identically on replay (the event loop re-walks the durable log in the same order),
     * whereas a transition's start instant is read fresh from the clock on each execution and would
     * differ on replay. Every phase write of one transition carries the same `seq`, so they share the
     * key and differ only by [phase].
     */
    fun transitionCheckpointName(
        record: CompactTransitionRecord,
        phase: JournalPhase,
    ): String = "sm:txn:${record.seq ?: NULL_KEY_SENTINEL}:$phase"

    /** Stable checkpoint name for a timeout firing (one per state entry). */
    fun timeoutCheckpointName(
        stateEntryEpochNanos: Long?,
        durationNanos: Long
    ): String = "sm:timeout:${stateEntryEpochNanos ?: NULL_KEY_SENTINEL}:$durationNanos"

    /** Stable checkpoint name for one after-hook firing write, keyed by state entry + hook identity. */
    fun afterHookCheckpointName(
        stateEntryEpochNanos: Long?,
        hookId: String?,
        durationNanos: Long,
        phase: JournalPhase,
    ): String {
        val hook = hookId ?: "dur:$durationNanos"
        return "sm:after:${stateEntryEpochNanos ?: NULL_KEY_SENTINEL}:$hook:$phase"
    }

    /**
     * Stable checkpoint name for a hidden start-timestamp marker.
     *
     * Event/invalid markers key on the deterministic event-log index; the timeout marker (one per
     * state entry) keys on the durable state-entry time. Both reproduce identically on replay, unlike
     * a fresh clock read.
     */
    fun markerCheckpointName(
        markerKind: MarkerKind,
        eventIndex: Int?,
        stateEntryEpochNanos: Long?,
    ): String =
        when (markerKind) {
            MarkerKind.EVENT_HANDLER -> "sm:mark:evt:${eventIndex ?: NULL_KEY_SENTINEL}:handler"
            MarkerKind.INVALID -> "sm:mark:invalid:${eventIndex ?: NULL_KEY_SENTINEL}:start"
            MarkerKind.TIMEOUT_HANDLER -> "sm:mark:timeout:${stateEntryEpochNanos ?: NULL_KEY_SENTINEL}:handler"
        }

    // ── RuntimeState to snapshot (private) ──

    /** Freezes mutable replay state into the immutable structure written to Smile. */
    private fun RuntimeState.toSnapshot(): CompactStateSnapshot =
        CompactStateSnapshot(
            stateNames = stateNames.toList(),
            eventTypeNames = eventTypeNames.toList(),
            events = events.toList(),
            afterHooks = afterHooks.toList(),
            timeouts = timeouts.toList(),
            transitions = transitions.toList(),
        )
}
