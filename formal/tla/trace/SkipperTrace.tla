---------------------------------- MODULE SkipperTrace ----------------------------------
(***************************************************************************************)
(* Trace validation: checks that one workflow's recorded run of the real engine is a    *)
(* behaviour of SkipperWorkflowLiveness.                                                *)
(*                                                                                     *)
(* TraceLog is the run as a sequence of events, one per store or scheduler write, in    *)
(* commit order (testutils' TraceRecorder writes it, trace_to_tla.py turns it into a    *)
(* TLA+ value). Each event names the model action that write corresponds to and the     *)
(* durable state after it: the workflow row, the timers and the task rows.              *)
(*                                                                                     *)
(* A step either consumes the next event, taking its action and landing exactly on its  *)
(* state, or is silent: a model step that changes no durable state, standing for what   *)
(* the code does without writing (a handler running the workflow body, a lease         *)
(* lapsing, time passing). The run is accepted when some behaviour consumes every       *)
(* event; TLC then reports TraceNotDone violated. Otherwise, rerun with ReportProgress  *)
(* and the last event number it prints is where code and model part ways.              *)
(***************************************************************************************)
EXTENDS SkipperWorkflowLiveness, Sequences, TLC

CONSTANTS
    TraceLog,         \* the recorded run, from trace_to_tla.py
    ReportProgress    \* print each event as it is matched: for finding where a rejected run diverges

VARIABLE l   \* the next event to consume

traceVars == <<vars, l>>

(* The run_after class a snapshot records: the model's run_after values, coarsened.     *)
Cls(ra) == CASE ra = DUE -> "DUE" [] ra = SOON -> "SOON" [] ra = LATER -> "LATER" [] OTHER -> "LEASE"

(* The model's state after the step is the one the event recorded.                     *)
Matches(e) ==
    /\ wf'.ex = e.wf.ex
    /\ e.wf.ex => wf'.st = e.wf.st /\ wf'.ver = e.wf.ver
    /\ tm' = e.tm
    /\ \A t \in TaskIds :
         /\ row'[t].st = e.row[t].st
         /\ row'[t].st # "NONE" => row'[t].ver = e.row[t].ver /\ row'[t].rc = e.row[t].rc
         /\ row'[t].st \in {"PENDING", "RUNNING"} => Cls(row'[t].ra) = e.row[t].cls

(* The model action each kind of recorded write is.                                     *)
Act(e) ==
    CASE e.act = "Fetch"         -> \E p \in Workers : Fetch(p)
      [] e.act = "MarkFailed"    -> \E p \in Workers : MarkFailed(p)
      [] e.act = "WfPersist"     -> \E p \in Workers : WfPersist(p)
      [] e.act = "WfTimers"      -> \E p \in Workers : WfTimers(p)
      [] e.act = "TmExec"        -> \E p \in Workers : TmExec(p)
      [] e.act = "TmSched"       -> \E p \in Workers : TmSched(p)
      [] e.act = "Finish"        -> \E p \in Workers : Finish(p)
      [] e.act = "Renew"         -> \E p \in Workers : Renew(p)
      [] e.act = "Requeue"       -> \E t \in TaskIds : Requeue(t)
      [] e.act = "StartCreate"   -> StartCreate
      [] e.act = "StartSched"    -> StartSched
      [] e.act = "SignalPersist" -> SignalPersist
      [] e.act = "SignalSched"   -> SignalSched
      [] e.act = "SignalStart"   -> SignalStart
      [] e.act = "WfRead"        -> \E p \in Workers : wk[p].held.wv = 0 /\ WfRead(p)
      [] e.act = "WfRun"         -> \E p \in Workers : WfRun(p)

(* No durable change: the workflow row, timers and each task's status, version and     *)
(* retry count stay put. run_after may move (time passing, a lease lapsing).            *)
DurableSame ==
    /\ wf' = wf /\ tm' = tm
    /\ \A t \in TaskIds :
         row'[t].st = row[t].st /\ row'[t].ver = row[t].ver /\ row'[t].rc = row[t].rc

(* The reads the recorder logs happen only when their event is consumed: a handler       *)
(* loading its instance (an in-memory copy runs on its payload and reads nothing) and    *)
(* then its timers, and a signal loading the instance it applies to.                    *)
LoggedRead ==
    \/ \E p \in Workers : wk[p].pc = "wf_exec" /\ wk[p].held.wv = 0 /\ wk'[p].pc # "wf_exec"
    \/ \E p \in Workers : wk[p].pc = "wf_run" /\ wk'[p].pc # "wf_run"
    \/ spc = "idle" /\ spc' = "exec"

(* Time moves a row's run_after only when the next event needs it: the row that event    *)
(* writes, or a row whose recorded class the model has not reached yet. Letting every    *)
(* pending timer drift through LATER, SOON and DUE between events would multiply states  *)
(* that the next event prunes anyway.                                                    *)
TimeNeeded ==
    \A t \in TaskIds :
        row'[t].ra # row[t].ra =>
            /\ l <= Len(TraceLog)
            /\ t = TraceLog[l].task \/ TraceLog[l].row[t].cls # Cls(row[t].ra)

Silent == Next /\ DurableSame /\ ~LoggedRead /\ TimeNeeded /\ UNCHANGED l

(* A logged read is its model step. A write that changed nothing the model tracks (a    *)
(* lost optimistic lock, an entity that already existed, a no-op honour of a lease) is   *)
(* consumed without a model step; the model action it came from, if any, runs as a     *)
(* silent step. A refused signal changes nothing at all.                                 *)
Consume ==
    /\ l <= Len(TraceLog)
    /\ LET e == TraceLog[l] IN
         /\ CASE e.act = "SignalRejected" -> UNCHANGED vars /\ Matches(e)
              [] e.read -> Act(e) /\ Matches(e)
              [] e.noop -> UNCHANGED vars /\ Matches(e)
              [] OTHER -> Act(e) /\ Matches(e)
         /\ ReportProgress => PrintT(<<"matched", l, e.seq>>)
    /\ l' = l + 1

(* The workflow body is free in trace mode (FreeResults), which leaves TLC guessing a    *)
(* run's outcome long before the write that reveals it. Keep only outcomes one of the    *)
(* next two recorded persists could show. Narrowing the model's choices can only make a  *)
(* trace harder to accept, never accept a wrong one. A persist that changed nothing (a   *)
(* lost lock) reveals nothing, and a run whose persist the trace never got to (the test *)
(* ended first) has nothing to match, so then every outcome stays.                      *)
UpcomingPersists ==
    LET idx == {j \in l..Len(TraceLog) : TraceLog[j].act = "WfPersist"}
        first == IF idx = {} THEN {} ELSE {CHOOSE j \in idx : \A i \in idx : j <= i}
        rest == idx \ first
        second == IF rest = {} THEN {} ELSE {CHOOSE j \in rest : \A i \in rest : j <= i}
    IN {TraceLog[j] : j \in first \cup second}

Reveals(r, e) ==
    /\ StatusOf(r.o) = e.wf.st
    /\ e.tm = [k \in Timers |-> IF TimerMoves(tm[k], r.d[k]) THEN r.d[k] ELSE tm[k]]

TraceResults ==
    IF UpcomingPersists = {} \/ \E e \in UpcomingPersists : e.noop THEN FreeResults
    ELSE {r \in FreeResults : \E e \in UpcomingPersists : Reveals(r, e)}

NoFaultCount == FALSE

TraceInit == Init /\ l = 1

TraceSpec == TraceInit /\ [][Silent \/ Consume]_traceVars

(* Workers are interchangeable, and the check is an invariant, so TLC may merge states   *)
(* that differ only in which worker did what.                                            *)
WorkerSymmetry == Permutations(Workers)

(* Violated exactly when a behaviour has consumed the whole trace: the run is accepted. *)
TraceNotDone == l <= Len(TraceLog)

=========================================================================================
