---------------------------- MODULE SkipperWorkflowLiveness ----------------------------
(***************************************************************************************)
(* How the Skipper engine drives one workflow instance to completion, as a transition *)
(* system over the durable store (workflow row, timer rows, scheduler task rows) and  *)
(* the processes that touch it: scheduler workers, the callers of startWorkflow, a    *)
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
    SignalInMemory,     \* signals take the in-process path: FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER off
    MaxStartCalls,      \* startWorkflow calls each caller makes, beyond retries after a failure
    Callers,            \* startWorkflow callers, each in its own JVM with its own in-memory queue
    RecoverAfterLease,  \* a start that finds the workflow stranded schedules it after a lease (FALSE:
                        \* like any other start, which only calibrates start_race_unguarded)
    NoLongPauses,       \* no start stalls between its two writes, and no in-memory copy waits in its
                        \* queue, for as long as a lease
    UserFailures,       \* the workflow body may fail: transient, non-retryable, retries exhausted
    FreeBody            \* the body's outcome is unconstrained (trace validation runs real workflows)

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
       /\ InMemoryStart \in BOOLEAN /\ SignalInMemory \in BOOLEAN /\ FreeBody \in BOOLEAN
       /\ MaxStartCalls \in Nat \ {0}
       /\ Callers # {} /\ RecoverAfterLease \in BOOLEAN /\ NoLongPauses \in BOOLEAN

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
NoHeld == [id |-> WF, ver |-> 0, ra |-> DUE, rc |-> 0, rerun |-> FALSE, wv |-> 0]
NoDirty == [k \in Timers |-> "NONE"]
IdleW == [pc |-> "idle", held |-> NoHeld, att |-> FALSE, out |-> "none", rv |-> 0, rs |-> "NONE",
          dirty |-> NoDirty, pend |-> {}, re |-> {}, miss |-> {}, fop |-> "remove", fra |-> DUE]

Terminal == {"COMPLETED", "ERROR"}
Settled == Terminal \cup {"RETRIES_EXHAUSTED"}

VARIABLES
    wf,       \* workflow row: [ex, st, ver, sig]; sig = signals applied to the workflow state
    tm,       \* timer rows: k -> NONE | ACTIVE | EXPIRED | CANCELLED
    row,      \* scheduler task rows: id -> [st: NONE|PENDING|RUNNING|FAILED, ra, ver, rc]
    wk,       \* per-worker local state (see IdleW)
    cpc,      \* startWorkflow caller c: ready (a call is due) | sched | recover | done
    lq,       \* caller c's JVM's in-memory queue: {} or {held snapshot}
    spc,      \* signal sender: idle | exec | sched
    sRead,    \* workflow version the in-flight signal read
    sigSent, faults, renewals, requeues, starts

vars == <<wf, tm, row, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

TypeOK ==
    /\ wf \in [ex: BOOLEAN, st: {"CREATED", "RUNNING", "WAITING", "TRANSIENT_ERROR",
                                 "COMPLETED", "ERROR", "RETRIES_EXHAUSTED"},
               ver: Nat, sig: Nat]
    /\ tm \in [Timers -> {"NONE", "ACTIVE", "EXPIRED", "CANCELLED"}]
    /\ row \in [TaskIds -> [st: {"NONE", "PENDING", "RUNNING", "FAILED"}, ra: Nat, ver: Nat, rc: Nat]]
    /\ \A p \in Workers : wk[p].pc \in {"idle", "dlq", "wf_exec", "wf_run", "wf_persist", "wf_timers",
                                        "wf_rearm", "tm_exec", "tm_sched", "finish"}
    /\ cpc \in [Callers -> {"ready", "sched", "recover", "done"}]
    /\ starts \in [Callers -> Nat]
    /\ spc \in {"idle", "exec", "sched"}

-----------------------------------------------------------------------------------------
(* Helpers                                                                              *)

Live(t) == row[t].st \in {"PENDING", "RUNNING"}
Queued(t) == row[t].st \in {"PENDING", "RUNNING", "FAILED"}   \* FAILED = in the DLQ

(* Every in-memory copy of the workflow task, in any caller's JVM.                     *)
InMemoryCopies == UNION {lq[c] : c \in Callers}

(* The JVM signals run in. Its in-memory queue is one of the callers'.                 *)
SignalNode == CHOOSE c \in Callers : TRUE

(* Task.hasActiveLease: PENDING or RUNNING with run_after <= now + lease.               *)
ActiveLease(r) == r.st \in {"PENDING", "RUNNING"} /\ r.ra # LATER

(* Scheduler.schedule for task t: insert, or overwrite resetting status and retry count;*)
(* when honouring an active lease, leave the row alone or (bump) bump only its version  *)
(* so the holder's final versioned write fails and it reruns the task. The lease check  *)
(* and the write are one transaction (JDBC) or one versioned write.                     *)
SchedReq(t, ra, honor, bump) ==
    IF row[t].st = "NONE" THEN [st |-> "PENDING", ra |-> ra, ver |-> 1, rc |-> 0]
    ELSE IF honor /\ ActiveLease(row[t])
         THEN IF bump THEN [row[t] EXCEPT !.ver = @ + 1] ELSE row[t]
         ELSE [st |-> "PENDING", ra |-> ra, ver |-> row[t].ver + 1, rc |-> 0]

(* The engine's own requests bump when BUMP_TASK_VERSION_ON_HONORED_LEASE is on.         *)
Sched(t, ra, honor) == SchedReq(t, ra, honor, BumpOnHonoredLease)

(* A lease value nobody holds or sees: one past every run_after in a row, a worker's    *)
(* hand or the in-memory queue. Only equality between leases matters, so this is as good *)
(* as a counter, and states that differ only in how many leases came before merge.       *)
NewLease ==
    LET used == {FirstLease - 1} \cup {row[t].ra : t \in TaskIds}
                \cup {wk[p].held.ra : p \in Workers} \cup {h.ra : h \in InMemoryCopies}
    IN 1 + (CHOOSE m \in used : \A u \in used : u <= m)

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

Outcomes == {"WAIT", "COMPLETE", "TRANSIENT", "NONRETRYABLE", "EXHAUSTED"}

(* Any body, as far as the engine can tell. A run replays the waits in program order     *)
(* (timer k is the k-th wait to create its timer) and may end at any point, but it only  *)
(* suspends inside waitUntil: at a wait whose timer is not over, which it cancels if the *)
(* condition now holds, and otherwise suspends on, creating the timer on the first visit.*)
Ends == {"COMPLETE", "TRANSIENT", "NONRETRYABLE", "EXHAUSTED"}

RECURSIVE FreeWalk(_, _)
FreeWalk(k, acc) ==
    {[o |-> f, d |-> acc] : f \in Ends} \cup
    (IF k > NWaits THEN {}
     ELSE IF tm[k] \in {"EXPIRED", "CANCELLED"} THEN FreeWalk(k + 1, acc)
     ELSE FreeWalk(k + 1, [acc EXCEPT ![k] = "CANCELLED"]) \cup
          {[o |-> "WAIT", d |-> IF tm[k] = "NONE" THEN [acc EXCEPT ![k] = "ACTIVE"] ELSE acc]})

FreeResults == FreeWalk(1, NoDirty)

Results ==
    IF FreeBody THEN FreeResults ELSE
    {Walk(1, wf.sig, NoDirty)} \cup
    (IF UserFailures
     THEN {[o |-> f, d |-> NoDirty] : f \in {"TRANSIENT", "NONRETRYABLE", "EXHAUSTED"}}
     ELSE {})

(* updateWorkflowAndStoreCheckpointsAndTimers creates a missing timer (then moves it to *)
(* the dirty status) and moves an existing one only as Timer.Status.canTransitionTo allows.*)
TimerMoves(cur, new) ==
    /\ new # "NONE"
    /\ \/ cur = "NONE"
       \/ cur = "ACTIVE" /\ new \in {"CANCELLED", "EXPIRED"}

StatusOf(o) ==
    CASE o = "WAIT" -> "WAITING"
      [] o = "COMPLETE" -> "COMPLETED"
      [] o = "TRANSIENT" -> "TRANSIENT_ERROR"
      [] o = "NONRETRYABLE" -> "ERROR"
      [] o = "EXHAUSTED" -> "RETRIES_EXHAUSTED"

(* Faults are budgeted so liveness can assume they stop. Trace validation checks no      *)
(* liveness, and a counter that only records how many early expiries a behaviour used    *)
(* would keep otherwise equal states apart, so it turns counting off (SkipperTrace.tla).  *)
FaultsCounted == TRUE

Fault(kind) ==
    /\ kind \in Faults
    /\ IF FaultsCounted THEN faults < MaxFaults /\ faults' = faults + 1 ELSE UNCHANGED faults

SetW(p, r) == wk' = [wk EXCEPT ![p] = r]

-----------------------------------------------------------------------------------------
(* Scheduler workers: SkipperSchedulerManager, LeaseRenewalManager and the handlers.    *)

(* Scheduler.fetch leases a due task: RUNNING, fresh run_after, version and retry_count *)
(* up by one. handleTask sends it to the DLQ once retry_count exceeds MaxRetries.       *)
Fetch(p) ==
    /\ wk[p].pc = "idle"
    /\ \E t \in TaskIds :
         /\ Live(t) /\ row[t].ra = DUE
         /\ LET r == [st |-> "RUNNING", ra |-> NewLease, ver |-> row[t].ver + 1, rc |-> row[t].rc + 1]
            IN /\ row' = [row EXCEPT ![t] = r]
               /\ SetW(p, [IdleW EXCEPT
                     !.pc = IF r.rc > MaxRetries THEN "dlq"
                            ELSE IF t = WF THEN "wf_exec" ELSE "tm_exec",
                     !.held = [id |-> t, ver |-> r.ver, ra |-> r.ra, rc |-> r.rc, rerun |-> FALSE, wv |-> 0],
                     !.att = TRUE])
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* A consumer takes the in-memory copy startWorkflow queued in its JVM.                *)
Take(p) ==
    /\ wk[p].pc = "idle"
    /\ \E c \in Callers : \E h \in lq[c] :
         /\ SetW(p, [IdleW EXCEPT !.pc = "wf_exec", !.held = h, !.att = TRUE])
         /\ lq' = [lq EXCEPT ![c] = {}]
    /\ UNCHANGED <<wf, tm, row, cpc, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* Scheduler.markAsFailed, versioned; a lost race is swallowed and the row left alone.  *)
MarkFailed(p) ==
    /\ wk[p].pc = "dlq"
    /\ LET t == wk[p].held.id IN
         row' = IF row[t].st # "NONE" /\ row[t].ver = wk[p].held.ver
                THEN [row EXCEPT ![t].st = "FAILED", ![t].ver = @ + 1]
                ELSE row
    /\ SetW(p, IdleW)
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* WorkflowExecutionTaskHandler.handle, in the two reads it makes before running the     *)
(* body. WfRead: load the instance, unless this is an in-memory copy, which runs on the  *)
(* instance it was queued with (wv). WfRun: load the timers and run the body. A terminal *)
(* workflow's body is not run again: WorkflowExecutor short-circuits to its current      *)
(* status, which is then persisted as usual. re: the timers the run loaded as ACTIVE and *)
(* left untouched, which WfRearm checks for a missing task.                             *)
WfRead(p) ==
    /\ wk[p].pc = "wf_exec"
    /\ IF ~wf.ex
       THEN SetW(p, IdleW)   \* NonRetryableError thrown outside the future; row left to its lease
       ELSE SetW(p, [wk[p] EXCEPT !.pc = "wf_run", !.rs = wf.st,
                                  !.rv = IF wk[p].held.wv > 0 THEN wk[p].held.wv ELSE wf.ver])
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

WfRun(p) ==
    /\ wk[p].pc = "wf_run"
    /\ IF wk[p].rs \in Terminal
       THEN SetW(p, [wk[p] EXCEPT !.pc = "wf_persist", !.dirty = NoDirty,
                                  !.out = IF wk[p].rs = "COMPLETED" THEN "COMPLETE" ELSE "NONRETRYABLE"])
       ELSE \E res \in Results :
              SetW(p, [wk[p] EXCEPT !.pc = "wf_persist", !.out = res.o, !.dirty = res.d,
                                    !.re = {k \in Timers : tm[k] = "ACTIVE" /\ res.d[k] = "NONE"}])
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* updateWorkflowAndStoreCheckpointsAndTimers: status, state and dirty timers in one    *)
(* versioned transaction. Losing the optimistic lock is a TRANSIENT_ERROR result that   *)
(* is not persisted; the task is rescheduled.                                           *)
WfPersist(p) ==
    /\ wk[p].pc = "wf_persist"
    /\ LET w == wk[p] IN
       IF wf.ver = w.rv
       THEN /\ wf' = [wf EXCEPT !.st = StatusOf(w.out), !.ver = @ + 1]
            /\ tm' = [k \in Timers |-> IF TimerMoves(tm[k], w.dirty[k]) THEN w.dirty[k] ELSE tm[k]]
            /\ \E ra \in {DUE, SOON, LATER} :
                 SetW(p, [w EXCEPT !.pc = "wf_timers",
                                   !.pend = {k \in Timers : w.dirty[k] = "ACTIVE" /\ tm[k] = "NONE"},
                                   !.re = IF StatusOf(w.out) \in Terminal THEN {} ELSE w.re,
                                   !.fop = IF w.out = "TRANSIENT" THEN "retry" ELSE "remove",
                                   !.fra = ra])
       ELSE /\ UNCHANGED <<wf, tm>>
            /\ \E ra \in {DUE, SOON, LATER} :
                 SetW(p, [w EXCEPT !.pc = "finish", !.re = {}, !.fop = "retry", !.fra = ra])
    /\ UNCHANGED <<row, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* After the transaction, one TIMER task is scheduled per timer the run created. Like   *)
(* the re-arm, it honours an active lease without a bump: an overlapping execution may  *)
(* already have re-armed this timer, and a worker leased the task.                      *)
WfTimers(p) ==
    /\ wk[p].pc = "wf_timers"
    /\ IF wk[p].pend = {}
       THEN /\ SetW(p, [wk[p] EXCEPT !.pc = "wf_rearm"])
            /\ UNCHANGED row
       ELSE \E k \in wk[p].pend, ra \in {DUE, SOON, LATER} :
              /\ row' = [row EXCEPT ![k] = SchedReq(k, ra, TRUE, FALSE)]
              /\ SetW(p, [wk[p] EXCEPT !.pend = @ \ {k}])
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* rearmOrphanedTimers, for a workflow left non-terminal: a timer the run loaded as      *)
(* ACTIVE and did not touch gets its TIMER task scheduled again if the task is missing,  *)
(* which is what a failure between WfPersist and WfTimers leaves behind. The check       *)
(* (getTask) and the schedule are separate steps: an overlapping execution of the same   *)
(* workflow (a lease that lapsed under its holder) can create, and a worker lease, the   *)
(* task in between. The schedule honours an active lease, without a version bump, so a  *)
(* leased task is left alone (TimerSchedulesKeepLeases).                                *)
WfRearmCheck(p) ==
    /\ wk[p].pc = "wf_rearm" /\ wk[p].re # {}
    /\ \E k \in wk[p].re :
         SetW(p, [wk[p] EXCEPT !.re = @ \ {k},
                               !.miss = IF row[k].st = "NONE" THEN @ \cup {k} ELSE @])
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

WfRearm(p) ==
    /\ wk[p].pc = "wf_rearm"
    /\ IF wk[p].re = {} /\ wk[p].miss = {}
       THEN /\ SetW(p, [wk[p] EXCEPT !.pc = "finish"])
            /\ UNCHANGED row
       ELSE \E k \in wk[p].miss, ra \in {DUE, SOON, LATER} :
              /\ row' = [row EXCEPT ![k] = SchedReq(k, ra, TRUE, FALSE)]
              /\ SetW(p, [wk[p] EXCEPT !.miss = @ \ {k}])
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

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
    /\ UNCHANGED <<wf, row, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

TmSched(p) ==
    /\ wk[p].pc = "tm_sched"
    /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
    /\ SetW(p, [wk[p] EXCEPT !.pc = "finish", !.fop = "remove"])
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

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
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

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
               /\ UNCHANGED row
          ELSE IF r.ver = w.held.ver
          THEN /\ row' = [row EXCEPT ![t].ver = @ + 1, ![t].ra = NewLease]
               /\ SetW(p, [w EXCEPT !.held.ver = r.ver + 1, !.held.ra = NewLease])
          ELSE IF r.ra = w.held.ra
          THEN /\ SetW(p, [w EXCEPT !.held.ver = r.ver, !.held.rerun = TRUE])
               /\ UNCHANGED row
          ELSE /\ SetW(p, [w EXCEPT !.att = FALSE])
               /\ UNCHANGED row
    /\ UNCHANGED <<wf, tm, cpc, lq, spc, sRead, sigSent, faults, requeues, starts>>

-----------------------------------------------------------------------------------------
(* Time. A lease whose holder is gone lapses; a backoff or timer comes due.             *)

(* With NoLongPauses, a lease does not lapse while something that began within it is    *)
(* still to happen: a start between its two writes, or an in-memory copy of that lease  *)
(* waiting to be taken. The models that use it start from a fresh workflow, so every     *)
(* workflow-task lease was granted after a pending start began.                          *)
LeaseStillClaimed(t) ==
    /\ NoLongPauses /\ t = WF
    /\ \/ \E c \in Callers : cpc[c] = "sched"
       \/ \E h \in InMemoryCopies : h.ra = row[WF].ra

ExpireFree(t) ==
    /\ Live(t) /\ row[t].ra >= FirstLease /\ ~LiveHolder(t) /\ ~LeaseStillClaimed(t)
    /\ row' = [row EXCEPT ![t].ra = DUE]
    /\ UNCHANGED <<wf, tm, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

Tick(t) ==
    /\ Live(t) /\ row[t].ra \in {SOON, LATER}
    /\ \E ra \in {DUE, SOON} : ra < row[t].ra /\ row' = [row EXCEPT ![t].ra = ra]
    /\ UNCHANGED <<wf, tm, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

-----------------------------------------------------------------------------------------
(* Environment: the callers of startWorkflow, a signal sender, an operator.             *)

(* A RUNNING workflow with no task row: the start that wrote it never scheduled it, or  *)
(* has not yet (SkipperEngine.isStranded). The store writes a new workflow as RUNNING.   *)
Stranded == wf.st = "RUNNING" /\ row[WF].st = "NONE"

(* SkipperEngine.startWorkflow, part 1: createWorkflow, or on EntityAlreadyExists       *)
(* return the existing instance without scheduling (terminal, or the no-op gate is on,  *)
(* except RETRIES_EXHAUSTED which is always re-run). A stranded workflow is scheduled    *)
(* even with the no-op gate on; the instance read and its task lookup are one step here. *)
StartCreate(c) ==
    /\ cpc[c] = "ready"
    /\ IF ~wf.ex
       THEN /\ wf' = [ex |-> TRUE, st |-> "RUNNING", ver |-> 1, sig |-> 0]
            /\ cpc' = [cpc EXCEPT ![c] = "sched"]
       ELSE /\ UNCHANGED wf
            /\ cpc' = [cpc EXCEPT ![c] =
                    IF wf.st = "RETRIES_EXHAUSTED" \/ ~(wf.st \in Terminal \/ NoopOnExisting) THEN "sched"
                    ELSE IF Stranded THEN IF RecoverAfterLease THEN "recover" ELSE "sched"
                    ELSE "done"]
    /\ UNCHANGED <<tm, row, wk, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* SchedulerExecutionQueue.schedule for a due workflow task: persist a leased backstop  *)
(* row and queue the task in JVM n, unless it is already queued there. It queues the     *)
(* copy even when the row write honoured someone else's lease. The queued copy carries   *)
(* the workflow instance as it is now (wv): the handler runs on that payload instead of  *)
(* reading the store again.                                                              *)
InMemorySchedule(n) ==
    \/ /\ lq[n] = {}
       /\ LET r == Sched(WF, NewLease, TRUE) IN
            /\ row' = [row EXCEPT ![WF] = r]
            /\ lq' = [lq EXCEPT ![n] =
                    {[id |-> WF, ver |-> r.ver, ra |-> r.ra, rc |-> 0, rerun |-> FALSE, wv |-> wf.ver]}]
    \/ /\ lq[n] # {}
       /\ UNCHANGED <<row, lq>>

(* Part 2: scheduleExecution, a separate transaction. Async goes to the persistent      *)
(* scheduler; in-process execution persists a leased backstop row and queues the task   *)
(* in this JVM (SchedulerExecutionQueue.schedule).                                      *)
StartSched(c) ==
    /\ cpc[c] = "sched"
    /\ cpc' = [cpc EXCEPT ![c] = "done"]
    /\ \/ /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
          /\ UNCHANGED <<lq>>
       \/ InMemoryStart /\ InMemorySchedule(c)
    /\ UNCHANGED <<wf, tm, wk, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* Part 2 for a stranded workflow (SchedulerExecutionQueue.scheduleAfterLease): only the *)
(* backstop row, due after a lease, and no in-memory copy. Its start may only be slow    *)
(* and about to schedule in memory: whichever row write comes first, the other honours   *)
(* its lease, and the only copy that runs is the one queued against it.                  *)
StartRecover(c) ==
    /\ cpc[c] = "recover"
    /\ cpc' = [cpc EXCEPT ![c] = "done"]
    /\ row' = [row EXCEPT ![WF] = Sched(WF, NewLease, TRUE)]
    /\ UNCHANGED <<wf, tm, wk, lq, spc, sRead, sigSent, faults, renewals, requeues, starts>>

(* The caller invokes the workflow method again (a sync caller reading its result).     *)
StartAgain(c) ==
    /\ cpc[c] = "done" /\ starts[c] < MaxStartCalls
    /\ cpc' = [cpc EXCEPT ![c] = "ready"] /\ starts' = [starts EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<wf, tm, row, wk, lq, spc, sRead, sigSent, faults, renewals, requeues>>

(* SkipperEngine.runSignal: read, run the signal method, persist state and RUNNING      *)
(* (versioned), then schedule the workflow task honouring an active lease.              *)
SignalStart ==
    /\ spc = "idle" /\ sigSent < MaxSignals
    /\ wf.ex /\ wf.st \notin Terminal
    /\ spc' = "exec" /\ sRead' = wf.ver /\ sigSent' = sigSent + 1
    /\ UNCHANGED <<wf, tm, row, wk, cpc, lq, faults, renewals, requeues, starts>>

SignalPersist ==
    /\ spc = "exec"
    /\ IF wf.ver = sRead
       THEN /\ wf' = [wf EXCEPT !.st = "RUNNING", !.sig = @ + 1, !.ver = @ + 1]
            /\ spc' = "sched"
       ELSE /\ UNCHANGED wf
            /\ spc' = "idle"   \* OptimisticLockingError surfaces to the sender
    /\ UNCHANGED <<tm, row, wk, cpc, lq, sRead, sigSent, faults, renewals, requeues, starts>>

SignalSched ==
    /\ spc = "sched"
    /\ spc' = "idle"
    /\ IF ~SignalInMemory
       THEN /\ row' = [row EXCEPT ![WF] = Sched(WF, DUE, TRUE)]
            /\ UNCHANGED <<lq>>
       ELSE InMemorySchedule(SignalNode)
    /\ UNCHANGED <<wf, tm, wk, cpc, sRead, sigSent, faults, renewals, requeues, starts>>

(* Scheduler.requeueFailedTask from the admin DLQ view.                                 *)
Requeue(t) ==
    /\ row[t].st = "FAILED" /\ requeues < MaxRequeues
    /\ row' = [row EXCEPT ![t] = [st |-> "PENDING", ra |-> DUE, ver |-> @.ver + 1, rc |-> 0]]
    /\ requeues' = requeues + 1
    /\ UNCHANGED <<wf, tm, wk, cpc, lq, spc, sRead, sigSent, faults, renewals, starts>>

-----------------------------------------------------------------------------------------
(* Faults, each bounded by MaxFaults and enabled per kind.                              *)

Crash(p) ==
    /\ wk[p].pc # "idle" /\ Fault("crash")
    /\ SetW(p, IdleW)
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, renewals, requeues, starts>>

LoseLocalQueue(c) ==
    /\ lq[c] # {} /\ Fault("crash")
    /\ lq' = [lq EXCEPT ![c] = {}]
    /\ UNCHANGED <<wf, tm, row, wk, cpc, spc, sRead, sigSent, renewals, requeues, starts>>

HandlerTimeout(p) ==
    /\ wk[p].pc \notin {"idle", "dlq"} /\ wk[p].att /\ Fault("timeout")
    /\ SetW(p, [wk[p] EXCEPT !.att = FALSE])
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, renewals, requeues, starts>>

ExpireEarly(t) ==
    /\ Live(t) /\ row[t].ra >= FirstLease /\ LiveHolder(t) /\ Fault("early_expiry")
    /\ row' = [row EXCEPT ![t].ra = DUE]
    /\ UNCHANGED <<wf, tm, wk, cpc, lq, spc, sRead, sigSent, renewals, requeues, starts>>

(* A store error after the workflow row committed: scheduling a timer task (new or      *)
(* re-armed), or the workflow task from the timer handler, throws. All are handled as   *)
(* retryable.                                                                            *)
StoreError(p) ==
    /\ \/ wk[p].pc = "wf_timers" /\ wk[p].pend # {}
       \/ wk[p].pc = "wf_rearm" /\ wk[p].miss # {}
       \/ wk[p].pc = "tm_sched"
    /\ Fault("store_error")
    /\ \E ra \in {DUE, SOON, LATER} :
         SetW(p, [wk[p] EXCEPT !.pc = "finish", !.pend = {}, !.re = {}, !.miss = {}, !.fop = "retry",
                               !.fra = ra])
    /\ UNCHANGED <<wf, tm, row, cpc, lq, spc, sRead, sigSent, renewals, requeues, starts>>

(* startWorkflow throws after the row exists; the caller is expected to retry.          *)
StartFail(c) ==
    /\ cpc[c] \in {"sched", "recover", "done"} /\ wf.ex /\ Fault("caller_fail")
    /\ cpc' = [cpc EXCEPT ![c] = "ready"]
    /\ UNCHANGED <<wf, tm, row, wk, lq, spc, sRead, sigSent, renewals, requeues, starts>>

SignalFail ==
    /\ spc = "sched" /\ Fault("signal_fail")
    /\ spc' = "idle"
    /\ UNCHANGED <<wf, tm, row, wk, cpc, lq, sRead, sigSent, renewals, requeues, starts>>

-----------------------------------------------------------------------------------------
Init ==
    /\ wf = [ex |-> FALSE, st |-> "CREATED", ver |-> 0, sig |-> 0]
    /\ tm = [k \in Timers |-> "NONE"]
    /\ row = [t \in TaskIds |-> NoRow]
    /\ wk = [p \in Workers |-> IdleW]
    /\ cpc = [c \in Callers |-> "ready"] /\ lq = [c \in Callers |-> {}] /\ spc = "idle" /\ sRead = 0
    /\ sigSent = 0 /\ faults = 0 /\ renewals = 0 /\ requeues = 0 /\ starts = [c \in Callers |-> 1]

WorkerStep(p) ==
    \/ Fetch(p) \/ Take(p) \/ MarkFailed(p) \/ WfRead(p) \/ WfRun(p) \/ WfPersist(p) \/ WfTimers(p)
    \/ WfRearmCheck(p) \/ WfRearm(p) \/ TmExec(p) \/ TmSched(p) \/ Finish(p)

Next ==
    \/ \E p \in Workers : WorkerStep(p) \/ Renew(p) \/ Crash(p) \/ HandlerTimeout(p) \/ StoreError(p)
    \/ \E t \in TaskIds : ExpireFree(t) \/ Tick(t) \/ ExpireEarly(t) \/ Requeue(t)
    \/ \E c \in Callers : StartCreate(c) \/ StartSched(c) \/ StartRecover(c) \/ StartFail(c) \/ StartAgain(c)
                         \/ LoseLocalQueue(c)
    \/ SignalStart \/ SignalPersist \/ SignalSched \/ SignalFail

(* Workers keep working, time passes, the caller retries until a call succeeds, and a   *)
(* signal call that started runs to its end. Faults, renewals, new signals and          *)
(* requeues get no fairness: they may or may not happen.                                *)
Fairness ==
    /\ \A p \in Workers : WF_vars(WorkerStep(p))
    /\ \A t \in TaskIds : WF_vars(ExpireFree(t)) /\ WF_vars(Tick(t))
    /\ \A c \in Callers : WF_vars(StartCreate(c)) /\ WF_vars(StartSched(c)) /\ WF_vars(StartRecover(c))
    /\ WF_vars(SignalPersist) /\ WF_vars(SignalSched)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------------------
(* Properties                                                                           *)

(* Something that will run the workflow task again: its row (FAILED rows are in the DLQ *)
(* and get requeued), the in-memory copy, a caller retry or signal schedule still owed, *)
(* or a timer task whose timer already fired and which will schedule the workflow.      *)
WfDriven ==
    \/ Queued(WF)
    \/ InMemoryCopies # {}
    \/ \E c \in Callers : cpc[c] \in {"ready", "sched", "recover"}
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

(* Worker p schedules timer k's task in this step: a new timer's (WfTimers) or a        *)
(* re-armed one's (WfRearm).                                                             *)
SchedulesTimer(p, k) ==
    \/ wk[p].pc = "wf_timers" /\ k \in wk[p].pend /\ wk'[p].pc = "wf_timers" /\ k \notin wk'[p].pend
    \/ wk[p].pc = "wf_rearm" /\ k \in wk[p].miss /\ wk'[p].pc = "wf_rearm" /\ k \notin wk'[p].miss

(* The engine never replaces a TIMER task someone holds a lease on when it schedules a   *)
(* timer's task: an overlapping execution may have scheduled it, and a worker leased it. *)
TimerSchedulesKeepLeases ==
    [][\A p \in Workers, k \in Timers :
         SchedulesTimer(p, k) /\ ActiveLease(row[k]) => row'[k] = row[k]]_vars

(* Worker p is running the workflow task: from loading the instance to its final write.  *)
RunsWorkflow(p) ==
    wk[p].held.id = WF /\ wk[p].pc \in {"wf_exec", "wf_run", "wf_persist", "wf_timers", "wf_rearm", "finish"}

(* At most one execution of the workflow at a time. Two at once would run the same       *)
(* actions concurrently; Skipper's retries assume a workflow's runs follow one another.  *)
NoOverlappingRuns == \A p, q \in Workers : RunsWorkflow(p) /\ RunsWorkflow(q) => p = q

(* Terminal states are absorbing.                                                       *)
TerminalIsStable == [][wf.st \in Terminal => wf'.st = wf.st]_vars

(* Liveness: the workflow is eventually created and ends up, for good, terminal,        *)
(* RETRIES_EXHAUSTED, or with a task parked in the DLQ.                                 *)
Done == wf.ex /\ (wf.st \in Settled \/ \E t \in TaskIds : row[t].st = "FAILED")
EventuallyDone == <>[]Done

=========================================================================================
