------------------------------ MODULE SkipperSchedulerTrace ------------------------------
(***************************************************************************************)
(* Trace validation for the scheduler: checks that what one instance's scheduler did   *)
(* in memory during a recorded run of the real engine is a behaviour of                 *)
(* SkipperScheduler.                                                                    *)
(*                                                                                     *)
(* TraceLog is the run as a sequence of events in the order they happened (testutils'  *)
(* SchedulerTraceRecorder writes it, sched_trace_to_tla.py turns it into a TLA+ value): *)
(* a fetch coming back, a task added to the in-memory queue, taken from it, its        *)
(* handler called, the handler's future reported, the handler finishing, a slot given  *)
(* back. Each event carries the queue's admitted count after it.                       *)
(*                                                                                     *)
(* A step either consumes the next event, taking the model action it stands for and    *)
(* landing on its count, or is the one step the recorder cannot see: the fetch loop    *)
(* reading its free slots. The run is accepted when some behaviour consumes every      *)
(* event; TLC then reports TraceNotDone violated.                                      *)
(*                                                                                     *)
(* The recorder sees one instance and not the task rows, so the model runs with one    *)
(* instance and the conditions on rows are opened up (the Trace* operators below, which *)
(* MC.cfg puts in place of the model's). What is left is the instance's own            *)
(* accounting, which is what the trace can speak to.                                   *)
(***************************************************************************************)
EXTENDS SkipperScheduler, Sequences, TLC

CONSTANTS
    TraceLog,         \* the recorded run, from sched_trace_to_tla.py
    ReportProgress    \* print each event as it is matched: for finding where a rejected run diverges

VARIABLE l   \* the next event to consume

traceVars == <<vars, l>>

Me == 1   \* the instance the trace was recorded on

(* The rows are not in the trace: any task may be due, any task may be scheduled in     *)
(* memory, and any lease may have moved.                                                *)
TraceFetchable(i, t) == TRUE
TraceSchedulable(t) == TRUE
TraceLeaseMoved(j) == TRUE

(* A fetch comes back with exactly the tasks the trace says it did.                    *)
TraceFetchChoices(i) ==
    IF l <= Len(TraceLog) /\ TraceLog[l].act = "Fetch" THEN {TraceLog[l].tasks} ELSE {}

(* The model action each recorded event is.                                            *)
Act(e) ==
    CASE e.act = "Fetch" ->
            \* The loop asked for what its free slots allowed, and got no more than it asked for.
            /\ snap[Me] = e.requested /\ Cardinality(e.tasks) <= e.requested
            /\ FetchLease(Me)
      [] e.act = "Add" /\ e.origin = "fetched" ->
            /\ e.added <=> ~QueuedHere(Me, e.task)
            /\ Deliver(Me, e.task)
      [] e.act = "Add" /\ e.origin = "inmem" ->
            /\ e.added <=> ~QueuedHere(Me, e.task)
            /\ InMemAdd(Me, e.task)
      [] e.act = "Take" -> \E j \in jobs[Me] : j.task = e.task /\ Take(Me, j)
      [] e.act = "Start" -> \E j \in jobs[Me] : j.task = e.task /\ RunStart(Me, j)
      [] e.act = "Report" ->
            \E j \in jobs[Me] :
                /\ j.task = e.task
                \* A handler whose run was cancelled before this point still hands back its future.
                /\ Report(Me, j) \/ (j.phase = "leaked" /\ UNCHANGED vars)
      [] e.act = "Done" -> \E j \in jobs[Me] : j.task = e.task /\ HandlerDone(Me, j)
      [] e.act = "Release" ->
            \* The recorder does not know whose slot came back; the model has to find a task
            \* that could give one back at this point.
            \E j \in jobs[Me] : SlotBack(Me, j) \/ Cancel(Me, j) \/ RunAborts(Me, j)

Consume ==
    /\ l <= Len(TraceLog)
    /\ LET e == TraceLog[l] IN
         /\ Act(e)
         /\ admitted'[Me] = e.admitted
         /\ ReportProgress => PrintT(<<"matched", l, e.seq>>)
    /\ l' = l + 1

(* The fetch loop reading its free slots leaves no event.                              *)
Silent == FetchRead(Me) /\ UNCHANGED l

TraceInit == Init /\ l = 1

TraceSpec == TraceInit /\ [][Silent \/ Consume]_traceVars

(* Violated exactly when a behaviour has consumed the whole trace: the run is accepted. *)
TraceNotDone == l <= Len(TraceLog)

=========================================================================================
