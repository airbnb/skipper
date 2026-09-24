---------------------------- MODULE SkipperWorkflowLiveness ----------------------------
(***************************************************************************************)
(* How the Skipper engine drives one workflow instance to completion, as a transition *)
(* system over the durable store (workflow row, timer rows, scheduler task rows) and  *)
(* the processes that touch it: scheduler workers, the caller of startWorkflow, a     *)
(* signal sender and an operator working the DLQ.                                     *)
(*                                                                                     *)
(* The property under test: once a workflow is created it eventually settles in a     *)
(* terminal state (COMPLETED, ERROR) or one that needs manual intervention            *)
(* (RETRIES_EXHAUSTED, or a FAILED task sitting in the DLQ). The invariants below are *)
(* the safety half of that argument: every workflow that is not settled has something *)
(* durable (or an obligation someone will honour) that will run it again.             *)
(*                                                                                     *)
(* The workflow body is abstracted to NWaits calls to waitUntil(cond, timeout) and a  *)
(* return. Wait k's condition holds once k signals have been applied to the state.    *)
(* Every action is one store transaction or one local step, so a fault can land       *)
(* between any two of them. README.md maps each action to the code it models.         *)
(***************************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
    Workers,            \* scheduler consumer threads, possibly in different JVMs
    NWaits,             \* waitUntil calls in the abstract workflow body
    MaxRetries,         \* SCHEDULER_TASK_MAX_RETRIES: deliveries before a task goes to the DLQ
    MaxSignals,         \* signals the environment may send
    MaxFaults,          \* total faults of the kinds enabled in Faults
    MaxRenewals,        \* lease renewals; bounds the task version counter
    MaxRequeues,        \* operator requeues of FAILED tasks
    Faults,             \* enabled fault kinds, a subset of FaultKinds
    NoopOnExisting,     \* FeatureGate CREATE_EXISTING_WORKFLOW_IS_NOOP (default on)
    BumpOnHonoredLease, \* FeatureGate BUMP_TASK_VERSION_ON_HONORED_LEASE (default on)
    InMemoryStart,      \* startWorkflow may take the in-process path (synchronous start)
    UserFailures        \* the workflow body may fail: transient, non-retryable, retries exhausted

FaultKinds == {
    "crash",        \* a worker or the caller's JVM dies; its local state is lost
    "timeout",      \* handleTask stops waiting on the handler (execution timeout); the handler keeps running
    "early_expiry", \* a lease lapses while its holder is still working (GC pause, renewal starvation)
    "store_error",  \* a store write after the workflow row was persisted throws
    "caller_fail",  \* a startWorkflow call fails after the workflow row was created
    "signal_fail"   \* a signal call fails after the signal was persisted, before scheduling
}

ASSUME /\ Workers # {}
       /\ NWaits \in Nat /\ MaxRetries \in Nat /\ MaxSignals \in Nat
       /\ MaxFaults \in Nat /\ MaxRenewals \in Nat /\ MaxRequeues \in Nat
       /\ Faults \subseteq FaultKinds
       /\ NoopOnExisting \in BOOLEAN /\ BumpOnHonoredLease \in BOOLEAN /\ UserFailures \in BOOLEAN
       /\ InMemoryStart \in BOOLEAN

-----------------------------------------------------------------------------------------
(* Task ids. The WORKFLOW task's id is the workflow id; TIMER task k belongs to wait k. *)
WF == 0
Timers == 1..NWaits
TaskIds == {WF} \cup Timers

(* A task row's run_after, relative to now. Fetch only takes DUE rows.                 *)
DUE == 0        \* run_after <= now
SOON == 1       \* now < run_after <= now + lease: not fetchable, but hasActiveLease() holds
LATER == 2      \* run_after > now + lease: not fetchable, hasActiveLease() does not hold
FirstLease == 3 \* values from here on are leases: run_after = now + lease, fresh per fetch/renewal

NoRow == [st |-> "NONE", ra |-> DUE, ver |-> 0, rc |-> 0]
NoHeld == [id |-> WF, ver |-> 0, ra |-> DUE, rc |-> 0, rerun |-> FALSE]
NoDirty == [k \in Timers |-> "NONE"]
IdleW == [pc |-> "idle", held |-> NoHeld, att |-> FALSE, out |-> "none", rv |-> 0,
          dirty |-> NoDirty, pend |-> {}, fop |-> "remove", fra |-> DUE]

Terminal == {"COMPLETED", "ERROR"}
Settled == Terminal \cup {"RETRIES_EXHAUSTED"}

VARIABLES
    wf,       \* workflow row: [ex, st, ver, sig]; sig = signals applied to the workflow state
    tm,       \* timer rows: k -> NONE | ACTIVE | EXPIRED | CANCELLED
    row,      \* scheduler task rows: id -> [st: NONE|PENDING|RUNNING|FAILED, ra, ver, rc]
    tok,      \* next fresh lease value
    wk,       \* per-worker local state (see IdleW)
    cpc,      \* startWorkflow caller: ready (a call is due) | sched | done
    lq,       \* the caller JVM's in-memory queue: {} or {held snapshot}
    spc,      \* signal sender: idle | exec | sched
    sRead,    \* workflow version the in-flight signal read
    sigSent, faults, renewals, requeues

vars == <<wf, tm, row, tok, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

TypeOK ==
    /\ wf \in [ex: BOOLEAN, st: {"CREATED", "RUNNING", "WAITING", "TRANSIENT_ERROR",
                                 "COMPLETED", "ERROR", "RETRIES_EXHAUSTED"},
               ver: Nat, sig: Nat]
    /\ tm \in [Timers -> {"NONE", "ACTIVE", "EXPIRED", "CANCELLED"}]
    /\ row \in [TaskIds -> [st: {"NONE", "PENDING", "RUNNING", "FAILED"}, ra: Nat, ver: Nat, rc: Nat]]
    /\ \A p \in Workers : wk[p].pc \in {"idle", "dlq", "wf_exec", "wf_persist", "wf_timers",
                                        "tm_exec", "tm_sched", "finish"}
    /\ cpc \in {"ready", "sched", "done"}
    /\ spc \in {"idle", "exec", "sched"}

-----------------------------------------------------------------------------------------
(* Helpers                                                                              *)

Live(t) == row[t].st \in {"PENDING", "RUNNING"}
Queued(t) == row[t].st \in {"PENDING", "RUNNING", "FAILED"}   \* FAILED = in the DLQ

(* Task.hasActiveLease: PENDING or RUNNING with run_after <= now + lease.               *)
ActiveLease(r) == r.st \in {"PENDING", "RUNNING"} /\ r.ra # LATER

(* Scheduler.schedule for task t: insert, or overwrite resetting status and retry count;*)
(* when honouring an active lease, leave the row alone or (gate on) bump only its       *)
(* version so the holder's final versioned write fails and it reruns the task.          *)
Sched(t, ra, honor) ==
    IF row[t].st = "NONE" THEN [st |-> "PENDING", ra |-> ra, ver |-> 1, rc |-> 0]
    ELSE IF honor /\ ActiveLease(row[t])
         THEN IF BumpOnHonoredLease THEN [row[t] EXCEPT !.ver = @ + 1] ELSE row[t]
         ELSE [st |-> "PENDING", ra |-> ra, ver |-> row[t].ver + 1, rc |-> 0]

(* The holder of task t's lease is still working and will renew or finish it.          *)
LiveHolder(t) ==
    \E p \in Workers : /\ wk[p].pc # "idle" /\ wk[p].att
                       /\ wk[p].held.id = t /\ wk[p].held.ra = row[t].ra

(* One replay of the workflow body against the stored timers and state: each waitUntil *)
(* passes if its timer is terminal or its condition holds (cancelling the timer),      *)
(* otherwise the run suspends, creating the timer on the first visit only.             *)
RECURSIVE Walk(_, _, _)
Walk(k, sig, acc) ==
    IF k > NWaits THEN [o |-> "COMPLETE", d |-> acc]
    ELSE IF tm[k] \in {"EXPIRED", "CANCELLED"} THEN Walk(k + 1, sig, acc)
    ELSE IF sig >= k THEN Walk(k + 1, sig, [acc EXCEPT ![k] = "CANCELLED"])
    ELSE IF tm[k] = "NONE" THEN [o |-> "WAIT", d |-> [acc EXCEPT ![k] = "ACTIVE"]]
    ELSE [o |-> "WAIT", d |-> acc]

Results ==
    {Walk(1, wf.sig, NoDirty)} \cup
    (IF UserFailures
     THEN {[o |-> f, d |-> NoDirty] : f \in {"TRANSIENT", "NONRETRYABLE", "EXHAUSTED"}}
     ELSE {})

StatusOf(o) ==
    CASE o = "WAIT" -> "WAITING"
      [] o = "COMPLETE" -> "COMPLETED"
      [] o = "TRANSIENT" -> "TRANSIENT_ERROR"
      [] o = "NONRETRYABLE" -> "ERROR"
      [] o = "EXHAUSTED" -> "RETRIES_EXHAUSTED"

Fault(kind) == kind \in Faults /\ faults < MaxFaults /\ faults' = faults + 1

SetW(p, r) == wk' = [wk EXCEPT ![p] = r]

-----------------------------------------------------------------------------------------
(* Scheduler workers: SkipperSchedulerManager, LeaseRenewalManager and the handlers.    *)

(* Scheduler.fetch leases a due task: RUNNING, fresh run_after, version and retry_count *)
(* up by one. handleTask sends it to the DLQ once retry_count exceeds MaxRetries.       *)
Fetch(p) ==
    /\ wk[p].pc = "idle"
    /\ \E t \in TaskIds :
         /\ Live(t) /\ row[t].ra = DUE
         /\ LET r == [st |-> "RUNNING", ra |-> tok, ver |-> row[t].ver + 1, rc |-> row[t].rc + 1]
            IN /\ row' = [row EXCEPT ![t] = r]
               /\ SetW(p, [IdleW EXCEPT
                     !.pc = IF r.rc > MaxRetries THEN "dlq"
                            ELSE IF t = WF THEN "wf_exec" ELSE "tm_exec",
                     !.held = [id |-> t, ver |-> r.ver, ra |-> r.ra, rc |-> r.rc, rerun |-> FALSE],
                     !.att = TRUE])
    /\ tok' = tok + 1
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* A consumer takes the in-memory copy startWorkflow queued in this JVM.               *)
Take(p) ==
    /\ wk[p].pc = "idle" /\ lq # {}
    /\ \E h \in lq : SetW(p, [IdleW EXCEPT !.pc = "wf_exec", !.held = h, !.att = TRUE])
    /\ lq' = {}
    /\ UNCHANGED <<wf, tm, row, tok, cpc, spc, sRead, sigSent, faults, renewals, requeues>>

(* Scheduler.markAsFailed, versioned; a lost race is swallowed and the row left alone.  *)
MarkFailed(p) ==
    /\ wk[p].pc = "dlq"
    /\ LET t == wk[p].held.id IN
         row' = IF row[t].st # "NONE" /\ row[t].ver = wk[p].held.ver
                THEN [row EXCEPT ![t].st = "FAILED", ![t].ver = @ + 1]
                ELSE row
    /\ SetW(p, IdleW)
    /\ UNCHANGED <<wf, tm, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* WorkflowExecutionTaskHandler: load the instance and run the body. A terminal         *)
(* workflow is not run again (WorkflowExecutor short-circuits).                         *)
WfExec(p) ==
    /\ wk[p].pc = "wf_exec"
    /\ IF ~wf.ex
       THEN SetW(p, IdleW)   \* NonRetryableError thrown outside the future; row left to its lease
       ELSE IF wf.st \in Terminal
       THEN SetW(p, [wk[p] EXCEPT !.pc = "finish", !.out = "noop", !.fop = "remove"])
       ELSE \E res \in Results :
              SetW(p, [wk[p] EXCEPT !.pc = "wf_persist", !.out = res.o, !.dirty = res.d,
                                    !.rv = wf.ver])
    /\ UNCHANGED <<wf, tm, row, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* updateWorkflowAndStoreCheckpointsAndTimers: status, state and dirty timers in one    *)
(* versioned transaction. Losing the optimistic lock is a TRANSIENT_ERROR result that   *)
(* is not persisted; the task is rescheduled.                                           *)
WfPersist(p) ==
    /\ wk[p].pc = "wf_persist"
    /\ LET w == wk[p] IN
       IF wf.ver = w.rv
       THEN /\ wf' = [wf EXCEPT !.st = StatusOf(w.out), !.ver = @ + 1]
            /\ tm' = [k \in Timers |-> IF w.dirty[k] = "NONE" THEN tm[k] ELSE w.dirty[k]]
            /\ \E ra \in {SOON, LATER} :
                 SetW(p, [w EXCEPT !.pc = "wf_timers",
                                   !.pend = {k \in Timers : w.dirty[k] = "ACTIVE"},
                                   !.fop = IF w.out = "TRANSIENT" THEN "retry" ELSE "remove",
                                   !.fra = ra])
       ELSE /\ UNCHANGED <<wf, tm>>
            /\ \E ra \in {SOON, LATER} :
                 SetW(p, [w EXCEPT !.pc = "finish", !.fop = "retry", !.fra = ra])
    /\ UNCHANGED <<row, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* After the transaction, one TIMER task is scheduled per timer the run created.        *)
WfTimers(p) ==
    /\ wk[p].pc = "wf_timers"
    /\ IF wk[p].pend = {}
       THEN /\ SetW(p, [wk[p] EXCEPT !.pc = "finish"])
            /\ UNCHANGED row
       ELSE \E k \in wk[p].pend :
              /\ row' = [row EXCEPT ![k] = Sched(k, LATER, FALSE)]
              /\ SetW(p, [wk[p] EXCEPT !.pend = @ \ {k}])
    /\ UNCHANGED <<wf, tm, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* TimerTaskHandler: defer while the workflow task holds a lease, else expire the timer *)
(* (idempotent) and schedule the workflow. A cancelled or missing timer is dropped.     *)
TmExec(p) ==
    /\ wk[p].pc = "tm_exec"
    /\ LET k == wk[p].held.id IN
       CASE ActiveLease(row[WF]) ->
                /\ SetW(p, [wk[p] EXCEPT !.pc = "finish", !.fop = "retry", !.fra = SOON])
                /\ UNCHANGED tm
         [] tm[k] = "ACTIVE" ->
                /\ tm' = [tm EXCEPT ![k] = "EXPIRED"]
                /\ SetW(p, [wk[p] EXCEPT !.pc = "tm_sched"])
         [] tm[k] = "EXPIRED" ->
                /\ SetW(p, [wk[p] EXCEPT !.pc = "tm_sched"])
                /\ UNCHANGED tm
         [] OTHER ->
                /\ SetW(p, [wk[p] EXCEPT !.pc = "finish", !.fop = "remove"])
                /\ UNCHANGED tm
    /\ UNCHANGED <<wf, row, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

TmSched(p) ==
    /\ wk[p].pc = "tm_sched"
    /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
    /\ SetW(p, [wk[p] EXCEPT !.pc = "finish", !.fop = "remove"])
    /\ UNCHANGED <<wf, tm, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* SkipperSchedulerManager.finishTask: the final versioned remove or rescheduleForRetry.*)
(* A version that moved with run_after unchanged means a rerun was requested on our     *)
(* lease; a moved run_after means another worker has the task now. Only WORKFLOW tasks  *)
(* are rerun immediately; other types run again when their lease expires.               *)
RerunNow(t) == IF t = WF THEN [row EXCEPT ![WF] = Sched(WF, DUE, FALSE)] ELSE row

Finish(p) ==
    /\ wk[p].pc = "finish"
    /\ LET w == wk[p]
           t == w.held.id
           r == row[t]
       IN row' = IF ~w.att THEN row                         \* handleTask already gave up
                 ELSE IF w.held.rerun THEN RerunNow(t)
                 ELSE IF r.st # "NONE" /\ r.ver = w.held.ver
                      THEN IF w.fop = "remove"
                           THEN [row EXCEPT ![t] = NoRow]
                           ELSE [row EXCEPT ![t] = [st |-> "PENDING", ra |-> w.fra,
                                                    ver |-> r.ver + 1, rc |-> r.rc + 1]]
                 ELSE IF r.st # "NONE" /\ r.ra = w.held.ra THEN RerunNow(t)
                 ELSE row                                   \* removed elsewhere, or lease lost
    /\ SetW(p, IdleW)
    /\ UNCHANGED <<wf, tm, tok, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* LeaseRenewalManager.attemptToRenewOneTask. A version bump that left run_after alone  *)
(* is adopted and remembered as a rerun request; any other failure cancels handleTask's *)
(* wait, but not the handler, which runs on and still writes.                           *)
Renew(p) ==
    /\ wk[p].pc \notin {"idle", "dlq"} /\ wk[p].att
    /\ renewals < MaxRenewals
    /\ renewals' = renewals + 1
    /\ LET w == wk[p]
           t == w.held.id
           r == row[t]
       IN IF r.st \notin {"PENDING", "RUNNING"}
          THEN /\ SetW(p, [w EXCEPT !.att = FALSE])
               /\ UNCHANGED <<row, tok>>
          ELSE IF r.ver = w.held.ver
          THEN /\ row' = [row EXCEPT ![t].ver = @ + 1, ![t].ra = tok]
               /\ tok' = tok + 1
               /\ SetW(p, [w EXCEPT !.held.ver = r.ver + 1, !.held.ra = tok])
          ELSE IF r.ra = w.held.ra
          THEN /\ SetW(p, [w EXCEPT !.held.ver = r.ver, !.held.rerun = TRUE])
               /\ UNCHANGED <<row, tok>>
          ELSE /\ SetW(p, [w EXCEPT !.att = FALSE])
               /\ UNCHANGED <<row, tok>>
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, requeues>>

-----------------------------------------------------------------------------------------
(* Time. A lease whose holder is gone lapses; a backoff or timer comes due.             *)

ExpireFree(t) ==
    /\ Live(t) /\ row[t].ra >= FirstLease /\ ~LiveHolder(t)
    /\ row' = [row EXCEPT ![t].ra = DUE]
    /\ UNCHANGED <<wf, tm, tok, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

Tick(t) ==
    /\ Live(t) /\ row[t].ra \in {SOON, LATER}
    /\ row' = [row EXCEPT ![t].ra = DUE]
    /\ UNCHANGED <<wf, tm, tok, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues>>

-----------------------------------------------------------------------------------------
(* Environment: the caller of startWorkflow, a signal sender, an operator.              *)

(* SkipperEngine.startWorkflow, part 1: createWorkflow, or on EntityAlreadyExists       *)
(* return the existing instance without scheduling (terminal, or the no-op gate is on,  *)
(* except RETRIES_EXHAUSTED which is always re-run).                                    *)
StartCreate ==
    /\ cpc = "ready"
    /\ IF ~wf.ex
       THEN /\ wf' = [ex |-> TRUE, st |-> "CREATED", ver |-> 1, sig |-> 0]
            /\ cpc' = "sched"
       ELSE /\ UNCHANGED wf
            /\ cpc' = IF (wf.st \in Terminal \/ NoopOnExisting) /\ wf.st # "RETRIES_EXHAUSTED"
                      THEN "done" ELSE "sched"
    /\ UNCHANGED <<tm, row, tok, wk, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* Part 2: scheduleExecution, a separate transaction. Async goes to the persistent      *)
(* scheduler; in-process execution persists a leased backstop row and queues the task   *)
(* in this JVM (SchedulerExecutionQueue.schedule).                                      *)
StartSched ==
    /\ cpc = "sched"
    /\ cpc' = "done"
    /\ \/ /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
          /\ UNCHANGED <<tok, lq>>
       \/ /\ InMemoryStart /\ lq = {}
          /\ LET r == Sched(WF, tok, TRUE) IN
               /\ row' = [row EXCEPT ![WF] = r]
               /\ lq' = {[id |-> WF, ver |-> r.ver, ra |-> r.ra, rc |-> 0, rerun |-> FALSE]}
          /\ tok' = tok + 1
       \/ /\ InMemoryStart /\ lq # {}   \* already queued in this JVM: nothing is written
          /\ UNCHANGED <<row, tok, lq>>
    /\ UNCHANGED <<wf, tm, wk, spc, sRead, sigSent, faults, renewals, requeues>>

(* SkipperEngine.runSignal: read, run the signal method, persist state and RUNNING      *)
(* (versioned), then schedule the workflow task honouring an active lease.              *)
SignalStart ==
    /\ spc = "idle" /\ sigSent < MaxSignals
    /\ wf.ex /\ wf.st \notin Terminal
    /\ spc' = "exec" /\ sRead' = wf.ver /\ sigSent' = sigSent + 1
    /\ UNCHANGED <<wf, tm, row, tok, wk, cpc, lq, faults, renewals, requeues>>

SignalPersist ==
    /\ spc = "exec"
    /\ IF wf.ver = sRead
       THEN /\ wf' = [wf EXCEPT !.st = "RUNNING", !.sig = @ + 1, !.ver = @ + 1]
            /\ spc' = "sched"
       ELSE /\ UNCHANGED wf
            /\ spc' = "idle"   \* OptimisticLockingError surfaces to the sender
    /\ UNCHANGED <<tm, row, tok, wk, cpc, lq, sRead, sigSent, faults, renewals, requeues>>

SignalSched ==
    /\ spc = "sched"
    /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
    /\ spc' = "idle"
    /\ UNCHANGED <<wf, tm, tok, wk, cpc, lq, sRead, sigSent, faults, renewals, requeues>>

(* Scheduler.requeueFailedTask from the admin DLQ view.                                 *)
Requeue(t) ==
    /\ row[t].st = "FAILED" /\ requeues < MaxRequeues
    /\ row' = [row EXCEPT ![t] = [st |-> "PENDING", ra |-> DUE, ver |-> @.ver + 1, rc |-> 0]]
    /\ requeues' = requeues + 1
    /\ UNCHANGED <<wf, tm, tok, wk, cpc, lq, spc, sRead, sigSent, faults, renewals>>

-----------------------------------------------------------------------------------------
(* Faults, each bounded by MaxFaults and enabled per kind.                              *)

Crash(p) ==
    /\ wk[p].pc # "idle" /\ Fault("crash")
    /\ SetW(p, IdleW)
    /\ UNCHANGED <<wf, tm, row, tok, cpc, lq, spc, sRead, sigSent, renewals, requeues>>

LoseLocalQueue ==
    /\ lq # {} /\ Fault("crash")
    /\ lq' = {}
    /\ UNCHANGED <<wf, tm, row, tok, wk, cpc, spc, sRead, sigSent, renewals, requeues>>

HandlerTimeout(p) ==
    /\ wk[p].pc \notin {"idle", "dlq"} /\ wk[p].att /\ Fault("timeout")
    /\ SetW(p, [wk[p] EXCEPT !.att = FALSE])
    /\ UNCHANGED <<wf, tm, row, tok, cpc, lq, spc, sRead, sigSent, renewals, requeues>>

ExpireEarly(t) ==
    /\ Live(t) /\ row[t].ra >= FirstLease /\ LiveHolder(t) /\ Fault("early_expiry")
    /\ row' = [row EXCEPT ![t].ra = DUE]
    /\ UNCHANGED <<wf, tm, tok, wk, cpc, lq, spc, sRead, sigSent, renewals, requeues>>

(* A store error after the workflow row committed: scheduling a timer task, or the      *)
(* workflow task from the timer handler, throws. Both are handled as retryable.         *)
StoreError(p) ==
    /\ \/ wk[p].pc = "wf_timers" /\ wk[p].pend # {}
       \/ wk[p].pc = "tm_sched"
    /\ Fault("store_error")
    /\ \E ra \in {SOON, LATER} :
         SetW(p, [wk[p] EXCEPT !.pc = "finish", !.pend = {}, !.fop = "retry", !.fra = ra])
    /\ UNCHANGED <<wf, tm, row, tok, cpc, lq, spc, sRead, sigSent, renewals, requeues>>

(* startWorkflow throws after the row exists; the caller is expected to retry.          *)
StartFail ==
    /\ cpc \in {"sched", "done"} /\ wf.ex /\ Fault("caller_fail")
    /\ cpc' = "ready"
    /\ UNCHANGED <<wf, tm, row, tok, wk, lq, spc, sRead, sigSent, renewals, requeues>>

SignalFail ==
    /\ spc = "sched" /\ Fault("signal_fail")
    /\ spc' = "idle"
    /\ UNCHANGED <<wf, tm, row, tok, wk, cpc, lq, sRead, sigSent, renewals, requeues>>

-----------------------------------------------------------------------------------------
Init ==
    /\ wf = [ex |-> FALSE, st |-> "CREATED", ver |-> 0, sig |-> 0]
    /\ tm = [k \in Timers |-> "NONE"]
    /\ row = [t \in TaskIds |-> NoRow]
    /\ tok = FirstLease
    /\ wk = [p \in Workers |-> IdleW]
    /\ cpc = "ready" /\ lq = {} /\ spc = "idle" /\ sRead = 0
    /\ sigSent = 0 /\ faults = 0 /\ renewals = 0 /\ requeues = 0

WorkerStep(p) ==
    \/ Fetch(p) \/ Take(p) \/ MarkFailed(p) \/ WfExec(p) \/ WfPersist(p) \/ WfTimers(p)
    \/ TmExec(p) \/ TmSched(p) \/ Finish(p)

Next ==
    \/ \E p \in Workers : WorkerStep(p) \/ Renew(p) \/ Crash(p) \/ HandlerTimeout(p) \/ StoreError(p)
    \/ \E t \in TaskIds : ExpireFree(t) \/ Tick(t) \/ ExpireEarly(t) \/ Requeue(t)
    \/ StartCreate \/ StartSched \/ StartFail
    \/ SignalStart \/ SignalPersist \/ SignalSched \/ SignalFail
    \/ LoseLocalQueue

(* Workers keep working, time passes, the caller retries until a call succeeds, and a   *)
(* signal call that started runs to its end. Faults, renewals, new signals and          *)
(* requeues get no fairness: they may or may not happen.                                *)
Fairness ==
    /\ \A p \in Workers : WF_vars(WorkerStep(p))
    /\ \A t \in TaskIds : WF_vars(ExpireFree(t)) /\ WF_vars(Tick(t))
    /\ WF_vars(StartCreate) /\ WF_vars(StartSched)
    /\ WF_vars(SignalPersist) /\ WF_vars(SignalSched)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------------------
(* Properties                                                                           *)

(* Something that will run the workflow task again: its row (FAILED rows are in the DLQ *)
(* and get requeued), the in-memory copy, a caller retry or signal schedule still owed, *)
(* or a timer task whose timer already fired and which will schedule the workflow.      *)
WfDriven ==
    \/ Queued(WF)
    \/ lq # {}
    \/ cpc \in {"ready", "sched"}
    \/ spc = "sched"
    \/ \E k \in Timers : tm[k] = "EXPIRED" /\ Queued(k)

(* A timer that will fire: ACTIVE with its task row present, or about to be scheduled.  *)
TimerArmed(k) ==
    /\ tm[k] = "ACTIVE"
    /\ \/ Queued(k)
       \/ \E p \in Workers : wk[p].pc = "wf_timers" /\ k \in wk[p].pend

(* A workflow the engine considers running always has its workflow task behind it.      *)
(* Being rescued by an unrelated wait timer (up to 365 days away) does not count: this  *)
(* is the invariant the lost-signal race of airbnb/skipper#12 broke.                    *)
RunningIsDriven ==
    wf.ex /\ wf.st \in {"CREATED", "RUNNING", "TRANSIENT_ERROR"} => WfDriven

(* Every waiting workflow has a timer that will fire (or is already being re-run).      *)
WaitingIsDriven ==
    wf.st = "WAITING" => (\E k \in Timers : TimerArmed(k)) \/ WfDriven

(* Terminal states are absorbing.                                                       *)
TerminalIsStable == [][wf.st \in Terminal => wf'.st = wf.st]_vars

(* Liveness: the workflow is eventually created and ends up, for good, terminal,        *)
(* RETRIES_EXHAUSTED, or with a task parked in the DLQ.                                 *)
Done == wf.ex /\ (wf.st \in Settled \/ \E t \in TaskIds : row[t].st = "FAILED")
EventuallyDone == <>[]Done

=========================================================================================
