---------------------------- MODULE SkipperScheduler ----------------------------
(***************************************************************************************)
(* The Skipper scheduler as a component: several instances, each with a concurrency   *)
(* limit, sharing one table of task rows and (optionally) dividing it by bucket       *)
(* between the live cluster members.                                                   *)
(*                                                                                     *)
(* SkipperWorkflowLiveness.tla follows one workflow through the store and covers the  *)
(* per-task protocol: versions, the final versioned write, signals, timers. This      *)
(* model looks at the same code from the other side. Tasks are opaque; what it tracks *)
(* is which instance has admitted which task, the admitted count each instance keeps, *)
(* and who may fetch what. The properties under test:                                 *)
(*                                                                                     *)
(*   1. The admitted count of an instance is exactly its work: tasks queued, runs not *)
(*      yet done, handlers still pending.                                             *)
(*   2. Fetched work never takes an instance above its limit; in-memory work may.     *)
(*   3. No fetched task waits to start while the instance is full, unless in-memory   *)
(*      work is what filled it.                                                       *)
(*   4. Every due task eventually runs on some instance, across crashes and           *)
(*      membership changes.                                                           *)
(*                                                                                     *)
(* Every action is one store transaction or one local step. README.md maps each       *)
(* action to the code it models.                                                      *)
(***************************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS
    NTasks,         \* tasks, numbered 1..NTasks; task t hashes to bucket t % NBuckets
    NInstances,     \* scheduler instances, numbered in member-id order
    NBuckets,       \* BucketPartitioner.TOTAL_BUCKETS, scaled down
    Limit,          \* schedulerMaxConcurrentTasks of every instance; 0 means no limit
    FetchSize,      \* SCHEDULER_FETCH_COUNT: the most tasks one fetch asks for
    Partitioning,   \* FeatureGate TASK_PARTITIONING, with a cluster membership manager
    InMemTasks,     \* tasks that enter through SchedulerExecutionQueue.schedule on some
                    \* instance; the others start as due rows
    Faults,         \* enabled fault kinds, a subset of FaultKinds
    MaxFaults,      \* total faults of the kinds enabled in Faults, hangs aside
    MaxHangs        \* handlers that never complete (fault kind "hang")

FaultKinds == {
    "crash",             \* an instance dies; its queue, runs and count are lost
    "timeout",           \* handleTask stops waiting on a handler that does finish later
    "lapse",             \* a lease lapses under a task its instance still holds
    "member_lapse",      \* a live instance's heartbeat ages out of the member list
    "hang",              \* a handler never completes
    "abort",             \* a run ends before it has a handler: its submit is rejected, the task is
                         \* past its retries, or handler.handle throws
    "cancel_unreported"  \* the renewer cancels a run between its start and the moment it
                         \* reports the handler's future (scheduler finding 1 in README.md)
}

ASSUME /\ NTasks \in Nat \ {0} /\ NInstances \in Nat \ {0} /\ NBuckets \in Nat \ {0}
       /\ Limit \in Nat /\ FetchSize \in Nat \ {0}
       /\ Partitioning \in BOOLEAN
       /\ InMemTasks \subseteq 1..NTasks
       /\ Faults \subseteq FaultKinds /\ MaxFaults \in Nat /\ MaxHangs \in Nat

Tasks == 1..NTasks
Instances == 1..NInstances
Buckets == 0..(NBuckets - 1)
BucketOf(t) == t % NBuckets

Min(a, b) == IF a < b THEN a ELSE b

(* One admission of a task on an instance, from SchedulerExecutionQueue.add until its  *)
(* handler is gone. gen is the lease the admission holds (the row's version).          *)
(*   queued    in the in-memory queue                                                  *)
(*   taken     its AdmittedRun is on the executor, not started; leases are renewed     *)
(*   started   the run called handler.handle and has not yet reported the future       *)
(*   waiting   the run reported the future and waits on it                             *)
(*   finished  the handler completed; the slot is given back next                      *)
(*   detached  the run is done (timeout or cancel), the handler still runs and still   *)
(*             holds the slot                                                          *)
(*   leaked    the run is done and gave the slot back, the handler still runs          *)
Phases == {"queued", "taken", "started", "waiting", "finished", "detached", "leaked"}
InFlight == {"taken", "started", "waiting"}  \* registered with LeaseRenewalManager
HoldsLease == {"queued"} \cup InFlight        \* will renew, or start renewing once taken

VARIABLES
    row,        \* row[t]: the task's row, [st, gen]
    alive,      \* alive[i]: the instance's process is up
    members,    \* instances with a fresh heartbeat row (cluster_members)
    view,       \* view[i]: the member list instance i last read
    jobs,       \* jobs[i]: the admissions instance i has in memory
    admitted,   \* admitted[i]: SchedulerExecutionQueue.admittedCount
    snap,       \* snap[i]: tasks the fetch loop decided to ask for; 0 when not fetching
    pending,    \* pending[i]: tasks the fetch loop has leased and not yet queued
    faults,     \* faults used, hangs aside
    hangs       \* handlers that have hung

vars == <<row, alive, members, view, jobs, admitted, snap, pending, faults, hangs>>

Job(t, g, o) == [task |-> t, gen |-> g, origin |-> o, phase |-> "queued", hung |-> FALSE]

Init ==
    /\ row = [t \in Tasks |-> IF t \in InMemTasks THEN [st |-> "none", gen |-> 0]
                                                ELSE [st |-> "due", gen |-> 0]]
    /\ alive = [i \in Instances |-> TRUE]
    /\ members = Instances
    /\ view = [i \in Instances |-> Instances]
    /\ jobs = [i \in Instances |-> {}]
    /\ admitted = [i \in Instances |-> 0]
    /\ snap = [i \in Instances |-> 0]
    /\ pending = [i \in Instances |-> {}]
    /\ faults = 0
    /\ hangs = 0

-----------------------------------------------------------------------------------------
(* Partitioning: SkipperSchedulerManager.fetchTasksWithOptionalPartitioning            *)

(* The instance fetches from the whole table: the gate is off, or it is not in the     *)
(* member list it read (which covers the empty list).                                  *)
FetchesAll(i) == ~Partitioning \/ i \notin view[i]

(* BucketPartitioner.getBucketRangeForMember: members in id order get contiguous       *)
(* ranges, and the first NBuckets % n of them one bucket more.                         *)
RangeStart(i) ==
    LET n == Cardinality(view[i])
        k == Cardinality({m \in view[i] : m < i})
    IN k * (NBuckets \div n) + Min(k, NBuckets % n)
RangeSize(i) ==
    LET n == Cardinality(view[i])
        k == Cardinality({m \in view[i] : m < i})
    IN (NBuckets \div n) + IF k < NBuckets % n THEN 1 ELSE 0

Owns(i, b) == FetchesAll(i) \/ (b >= RangeStart(i) /\ b < RangeStart(i) + RangeSize(i))

-----------------------------------------------------------------------------------------
(* The fetch loop: SkipperSchedulerManager.startFetchingTasks                          *)

(* freeSlots(). Below zero when in-memory work has gone over the limit.                *)
Free(i) == IF Limit = 0 THEN FetchSize ELSE Limit - admitted[i]

(* The loop reads its free slots and decides how many tasks to ask for. With none      *)
(* free it polls instead, which changes nothing.                                       *)
FetchRead(i) ==
    /\ alive[i] /\ snap[i] = 0 /\ pending[i] = {} /\ Free(i) > 0
    /\ snap' = [snap EXCEPT ![i] = Min(FetchSize, Free(i))]
    /\ UNCHANGED <<row, alive, members, view, jobs, admitted, pending, faults, hangs>>

QueuedHere(i, t) == \E j \in jobs[i] : j.task = t /\ j.phase = "queued"

(* A row the instance's fetch can lease. Trace validation replaces this: it does not   *)
(* see the rows.                                                                       *)
Fetchable(i, t) == row[t].st = "due" /\ Owns(i, BucketOf(t))

(* The sets of tasks a fetch may come back with: as many fetchable ones as it asked    *)
(* for, or all of them. A task whose old copy still sits in this instance's queue has  *)
(* waited there for a whole lease, which counts as a "lapse" fault.                    *)
FetchChoices(i) ==
    LET eligible == {t \in Tasks : Fetchable(i, t) /\ (QueuedHere(i, t) => faults < MaxFaults)}
    IN {S \in SUBSET eligible : Cardinality(S) = Min(snap[i], Cardinality(eligible))}

(* The store leases the tasks to this instance.                                        *)
FetchLease(i) ==
    /\ snap[i] > 0
    /\ \E S \in FetchChoices(i) :
          /\ row' = [t \in Tasks |-> IF t \in S THEN [st |-> "leased", gen |-> row[t].gen + 1]
                                               ELSE row[t]]
          /\ pending' = [pending EXCEPT ![i] = S]
          /\ faults' = IF \E t \in S : QueuedHere(i, t) THEN faults + 1 ELSE faults
    /\ snap' = [snap EXCEPT ![i] = 0]
    /\ UNCHANGED <<alive, members, view, jobs, admitted, hangs>>

(* The loop adds one leased task to the in-memory queue, which counts it. The queue    *)
(* refuses a task whose id it already holds; then the new lease is nobody's.           *)
Deliver(i, t) ==
    /\ t \in pending[i]
    /\ pending' = [pending EXCEPT ![i] = @ \ {t}]
    /\ IF QueuedHere(i, t)
       THEN UNCHANGED <<jobs, admitted>>
       ELSE /\ jobs' = [jobs EXCEPT ![i] = @ \cup {Job(t, row[t].gen, "fetched")}]
            /\ admitted' = [admitted EXCEPT ![i] = @ + 1]
    /\ UNCHANGED <<row, alive, members, view, snap, faults, hangs>>

(* A task a caller in this process may schedule for immediate execution. Trace         *)
(* validation replaces this too.                                                       *)
Schedulable(t) == t \in InMemTasks /\ row[t].st = "none"

(* SchedulerExecutionQueue.schedule: a caller in this process schedules a task for     *)
(* immediate execution. It writes the row as leased and queues a copy. Never refused.  *)
InMemAdd(i, t) ==
    /\ alive[i] /\ Schedulable(t)
    /\ row' = [row EXCEPT ![t] = [st |-> "leased", gen |-> @.gen + 1]]
    /\ jobs' = [jobs EXCEPT ![i] = @ \cup {Job(t, row[t].gen + 1, "inmem")}]
    /\ admitted' = [admitted EXCEPT ![i] = @ + 1]
    /\ UNCHANGED <<alive, members, view, snap, pending, faults, hangs>>

-----------------------------------------------------------------------------------------
(* One admission: startTakingTasks, AdmittedRun, handleTask                            *)

Move(i, j, phase) == jobs' = [jobs EXCEPT ![i] = (@ \ {j}) \cup {[j EXCEPT !.phase = phase]}]
Drop(i, j) == jobs' = [jobs EXCEPT ![i] = @ \ {j}]
Release(i) == admitted' = [admitted EXCEPT ![i] = @ - 1]  \* SchedulerExecutionQueue.markFinished

(* The take loop takes the task and hands its run to the executor. Not gated by the    *)
(* limit: the task was counted when it was added.                                      *)
Take(i, j) ==
    /\ j \in jobs[i] /\ j.phase = "queued"
    /\ Move(i, j, "taken")
    /\ UNCHANGED <<row, alive, members, view, admitted, snap, pending, faults, hangs>>

(* The run starts and calls handler.handle, which starts the handler's work.           *)
RunStart(i, j) ==
    /\ j \in jobs[i] /\ j.phase = "taken"
    /\ Move(i, j, "started")
    /\ UNCHANGED <<row, alive, members, view, admitted, snap, pending, faults, hangs>>

(* handle returned: the run reports the handler's future (holdSlotUntil) and waits.    *)
Report(i, j) ==
    /\ j \in jobs[i] /\ j.phase = "started"
    /\ Move(i, j, "waiting")
    /\ UNCHANGED <<row, alive, members, view, admitted, snap, pending, faults, hangs>>

(* The handler completes.                                                              *)
(* 1. Its run was waiting on it: the run wakes and makes the final write, which        *)
(*    removes the row if it still carries this admission's lease.                      *)
(* 2. Its run had ended and the slot was held for it.                                  *)
(* 3. Its run had ended and had given the slot back already: nothing is left of it.    *)
(* In cases 1 and 2 the slot is given back next (SlotBack).                            *)
HandlerDone(i, j) ==
    /\ j \in jobs[i] /\ ~j.hung /\ j.phase \in {"waiting", "detached", "leaked"}
    /\ row' = IF j.phase = "waiting" /\ row[j.task].gen = j.gen /\ row[j.task].st \in {"leased", "due"}
              THEN [row EXCEPT ![j.task].st = "done"]
              ELSE row
    /\ IF j.phase = "leaked" THEN Drop(i, j) ELSE Move(i, j, "finished")
    /\ UNCHANGED <<alive, members, view, admitted, snap, pending, faults, hangs>>

(* AdmittedRun.done gives the slot back: the run has ended and its handler is done.    *)
SlotBack(i, j) ==
    /\ j \in jobs[i] /\ j.phase = "finished"
    /\ Drop(i, j) /\ Release(i)
    /\ UNCHANGED <<row, alive, members, view, snap, pending, faults, hangs>>

(* The run ends before it has a handler, and gives its slot back: the executor         *)
(* rejects it, the task is past its retries and is marked failed, or handler.handle    *)
(* throws.                                                                             *)
RunAborts(i, j) ==
    /\ "abort" \in Faults /\ faults < MaxFaults
    /\ j \in jobs[i] /\ j.phase \in {"taken", "started"}
    /\ Drop(i, j) /\ Release(i)
    /\ faults' = faults + 1
    /\ UNCHANGED <<row, alive, members, view, snap, pending, hangs>>

(* handleTask stops waiting (SCHEDULER_TASK_EXECUTION_TIMEOUT). The run ends without   *)
(* a final write; the handler keeps running and keeps the slot. A hung handler always  *)
(* gets here; one that would finish gets here only as a fault.                         *)
Timeout(i, j) ==
    /\ j \in jobs[i] /\ j.phase = "waiting"
    /\ \/ j.hung /\ faults' = faults
       \/ ~j.hung /\ "timeout" \in Faults /\ faults < MaxFaults /\ faults' = faults + 1
    /\ Move(i, j, "detached")
    /\ UNCHANGED <<row, alive, members, view, admitted, snap, pending, hangs>>

HungTimeout(i, j) == j.hung /\ Timeout(i, j)

(* A handler that will never complete.                                                 *)
Hang(i, j) ==
    /\ "hang" \in Faults /\ hangs < MaxHangs
    /\ j \in jobs[i] /\ j.phase = "waiting" /\ ~j.hung
    /\ jobs' = [jobs EXCEPT ![i] = (@ \ {j}) \cup {[j EXCEPT !.hung = TRUE]}]
    /\ hangs' = hangs + 1
    /\ UNCHANGED <<row, alive, members, view, admitted, snap, pending, faults>>

(* LeaseRenewalManager.attemptToRenewOneTask fails for a run in flight, because        *)
(* another fetch has leased the row since, and cancels the run. What AdmittedRun.done  *)
(* then does with the slot:                                                            *)
(* 1. The run had not started: it never will, and the slot is given back.              *)
(* 2. The run had reported its handler's future: the slot is held until the handler    *)
(*    completes.                                                                       *)
(* 3. The run had started but not reported the future yet: the slot is given back at   *)
(*    once, and the handler it started runs on without one. Only with                  *)
(*    "cancel_unreported"; without it the cancel lands once the future is reported.    *)
LeaseMoved(j) == row[j.task].gen # j.gen  \* replaced in trace validation, which does not see the rows

Cancel(i, j) ==
    /\ j \in jobs[i] /\ j.phase \in InFlight
    /\ LeaseMoved(j)
    /\ \/ j.phase = "taken" /\ Drop(i, j) /\ Release(i)
       \/ j.phase = "waiting" /\ Move(i, j, "detached") /\ admitted' = admitted
       \/ j.phase = "started" /\ "cancel_unreported" \in Faults /\ Move(i, j, "leaked") /\ Release(i)
    /\ UNCHANGED <<row, alive, members, view, snap, pending, faults, hangs>>

-----------------------------------------------------------------------------------------
(* Leases                                                                              *)

(* Some instance holds the row's current lease: it is about to queue the task, or has  *)
(* a copy that keeps the lease renewed.                                                *)
Held(t) ==
    \E i \in Instances :
        \/ t \in pending[i]
        \/ \E j \in jobs[i] : j.task = t /\ j.gen = row[t].gen /\ j.phase \in HoldsLease

(* The lease runs out and the row is due again. Under a holder that is a fault (a      *)
(* pause, a starved renewer, a copy that waited in the queue for a whole lease).       *)
Lapse(t) ==
    /\ row[t].st = "leased"
    /\ IF Held(t)
       THEN "lapse" \in Faults /\ faults < MaxFaults /\ faults' = faults + 1
       ELSE faults' = faults
    /\ row' = [row EXCEPT ![t].st = "due"]
    /\ UNCHANGED <<alive, members, view, jobs, admitted, snap, pending, hangs>>

FreeLapse(t) == ~Held(t) /\ Lapse(t)

-----------------------------------------------------------------------------------------
(* Instances and cluster membership: JdbcClusterMembershipManager                      *)

(* The process dies. Everything it had in memory goes, the count included; its rows    *)
(* stay leased and its heartbeat row stays until it ages out. One instance always      *)
(* survives.                                                                           *)
Crash(i) ==
    /\ "crash" \in Faults /\ faults < MaxFaults
    /\ alive[i] /\ \E o \in Instances \ {i} : alive[o]
    /\ alive' = [alive EXCEPT ![i] = FALSE]
    /\ jobs' = [jobs EXCEPT ![i] = {}]
    /\ admitted' = [admitted EXCEPT ![i] = 0]
    /\ snap' = [snap EXCEPT ![i] = 0]
    /\ pending' = [pending EXCEPT ![i] = {}]
    /\ view' = [view EXCEPT ![i] = {}]
    /\ faults' = faults + 1
    /\ UNCHANGED <<row, members, hangs>>

(* The instance starts: its first heartbeat registers it and reads the member list.    *)
Join(i) ==
    /\ ~alive[i]
    /\ alive' = [alive EXCEPT ![i] = TRUE]
    /\ members' = IF Partitioning THEN members \cup {i} ELSE members
    /\ view' = [view EXCEPT ![i] = members']
    /\ UNCHANGED <<row, jobs, admitted, snap, pending, faults, hangs>>

(* A dead instance's heartbeat row ages out (LIVENESS_THRESHOLD).                      *)
MemberExpire(i) ==
    /\ Partitioning /\ ~alive[i] /\ i \in members
    /\ members' = members \ {i}
    /\ UNCHANGED <<row, alive, view, jobs, admitted, snap, pending, faults, hangs>>

(* A live instance's heartbeats fail for long enough that its row ages out.            *)
MemberLapse(i) ==
    /\ Partitioning /\ "member_lapse" \in Faults /\ faults < MaxFaults
    /\ alive[i] /\ i \in members
    /\ members' = members \ {i}
    /\ faults' = faults + 1
    /\ UNCHANGED <<row, alive, view, jobs, admitted, snap, pending, hangs>>

(* Its next heartbeat lands and refreshes its cached member list.                      *)
Heartbeat(i) ==
    /\ Partitioning /\ alive[i] /\ i \notin members
    /\ members' = members \cup {i}
    /\ view' = [view EXCEPT ![i] = members']
    /\ UNCHANGED <<row, alive, jobs, admitted, snap, pending, faults, hangs>>

(* The cached member list (one heartbeat interval old at most) is read again.          *)
RefreshView(i) ==
    /\ Partitioning /\ alive[i] /\ view[i] # members
    /\ view' = [view EXCEPT ![i] = members]
    /\ UNCHANGED <<row, alive, members, jobs, admitted, snap, pending, faults, hangs>>

-----------------------------------------------------------------------------------------
JobStep(i, t) ==
    \E j \in jobs[i] :
        /\ j.task = t
        /\ (\/ Take(i, j) \/ RunStart(i, j) \/ Report(i, j) \/ HandlerDone(i, j) \/ SlotBack(i, j)
            \/ Timeout(i, j) \/ Hang(i, j) \/ Cancel(i, j) \/ RunAborts(i, j))

Next ==
    \/ \E i \in Instances :
          \/ FetchRead(i) \/ FetchLease(i)
          \/ Crash(i) \/ Join(i)
          \/ MemberExpire(i) \/ MemberLapse(i) \/ Heartbeat(i) \/ RefreshView(i)
          \/ \E t \in Tasks : Deliver(i, t) \/ InMemAdd(i, t) \/ JobStep(i, t)
    \/ \E t \in Tasks : Lapse(t)

(* Live instances keep fetching, queueing, taking, running and reporting; handlers that *)
(* are not hung complete and give their slot back; the wait on a hung handler times    *)
(* out; unheld leases run out; dead                                                    *)
(* members age out and live ones heartbeat and re-read the member list. Faults,        *)
(* in-memory schedules, cancels and restarts get no fairness: they may or may not      *)
(* happen.                                                                             *)
Fairness ==
    /\ \A i \in Instances :
          /\ WF_vars(FetchRead(i)) /\ WF_vars(FetchLease(i))
          /\ WF_vars(MemberExpire(i)) /\ WF_vars(Heartbeat(i)) /\ WF_vars(RefreshView(i))
          /\ \A t \in Tasks :
                /\ WF_vars(Deliver(i, t))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ Take(i, j))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ RunStart(i, j))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ Report(i, j))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ HandlerDone(i, j))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ SlotBack(i, j))
                /\ WF_vars(\E j \in jobs[i] : j.task = t /\ HungTimeout(i, j))
    /\ \A t \in Tasks : WF_vars(FreeLapse(t))

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------------------
(* Properties                                                                           *)

TypeOK ==
    /\ \A t \in Tasks : row[t].st \in {"none", "due", "leased", "done"} /\ row[t].gen \in Nat
    /\ alive \in [Instances -> BOOLEAN]
    /\ members \subseteq Instances
    /\ view \in [Instances -> SUBSET Instances]
    /\ \A i \in Instances : \A j \in jobs[i] :
          /\ j.task \in Tasks /\ j.gen \in Nat \ {0}
          /\ j.origin \in {"fetched", "inmem"} /\ j.phase \in Phases /\ j.hung \in BOOLEAN
    /\ admitted \in [Instances -> Nat]
    /\ snap \in [Instances -> 0..FetchSize]
    /\ pending \in [Instances -> SUBSET Tasks]
    /\ faults \in 0..MaxFaults /\ hangs \in 0..MaxHangs

(* Property 1. The count is the instance's work: tasks queued, runs not yet done and   *)
(* handlers still pending. It is never negative (TypeOK), and a dead instance has no   *)
(* work and no count.                                                                  *)
AdmittedMatchesWork == \A i \in Instances : admitted[i] = Cardinality(jobs[i])

(* Property 1, the corollary operators read off the admittedTasks gauge: an instance   *)
(* with nothing to do reports zero.                                                    *)
IdleMeansZero == \A i \in Instances : jobs[i] = {} => admitted[i] = 0

Fetched(i) == {j \in jobs[i] : j.origin = "fetched"}
InMem(i) == {j \in jobs[i] : j.origin = "inmem"}
WaitingToStart(i) == {j \in jobs[i] : j.phase \in {"queued", "taken"}}
Executing(i) == {j \in jobs[i] : j.phase \notin {"queued", "taken"}}

(* Property 2. The work an instance fetched, queued yet or not, never exceeds its      *)
(* limit, however stale the free-slot count its fetch acted on.                        *)
FetchedWithinLimit ==
    Limit > 0 => \A i \in Instances : Cardinality(Fetched(i)) + Cardinality(pending[i]) <= Limit

(* Not a property of the design: in-memory executions are never refused, so the count  *)
(* as a whole can pass the limit. Models that list it expect the violation.            *)
AdmittedWithinLimit == Limit > 0 => \A i \in Instances : admitted[i] <= Limit

(* Property 3. A fetched task does not sit leased, waiting to start, behind a full     *)
(* instance, unless in-memory work is taking up room.                                  *)
NoFetchedWaitsWhileFull ==
    Limit > 0 =>
        \A i \in Instances :
            (Fetched(i) \cap WaitingToStart(i) # {} /\ Cardinality(Executing(i)) >= Limit)
                => InMem(i) # {}

Running(t) == \E i \in Instances : \E j \in jobs[i] : j.task = t /\ j.phase = "started"

(* Property 4. Every due task eventually runs on some instance. A row can also be due   *)
(* because its lease lapsed under a run that then finishes it; that counts.            *)
DueEventuallyRuns == \A t \in Tasks : (row[t].st = "due") ~> (Running(t) \/ row[t].st = "done")

(* And, since every fault here is bounded, every task is eventually finished.          *)
EveryTaskCompletes == \A t \in Tasks : (row[t].st # "none") ~> (row[t].st = "done")

(* Membership settles with every bucket owned by some live instance, in that           *)
(* instance's own view of the member list.                                             *)
BucketsEventuallyOwned ==
    <>[](\A b \in Buckets : \E i \in Instances : alive[i] /\ Owns(i, b))

=========================================================================================
