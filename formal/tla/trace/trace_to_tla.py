#!/usr/bin/env python3
"""Turns recorded engine traces into TLC models of SkipperTrace, one per workflow.

    trace_to_tla.py <trace.ndjson>... --out <dir>

A trace file (written by testutils' TraceRecorder) holds every store and scheduler write of one
runtime. For each workflow in it this either writes <dir>/<name>/MC.tla and MC.cfg, a model TLC
checks against SkipperTrace, or reports why the workflow is out of the model's scope. It prints one
JSON line per workflow: {"trace", "workflow", "origin", "status": "model" | "skipped", ...}.

The mapping has three parts:
  * each write becomes the model action it corresponds to, chosen by operation and call site
    (ACTIONS below). Writes to state the model does not track, such as checkpoints and persisted
    signal rows, are dropped. Anything else is out of scope, which skips the workflow;
  * the state after each write is reduced to what the model tracks. Task ids become 0 for the
    workflow task and 1..n for timers, in order of first appearance. run_after becomes the
    model's class: DUE, SOON, LATER, or LEASE when a fetch, renewal or in-memory schedule set it;
  * the model's constants come from the trace header (gates, retry limit) and from counting
    events (signals, starts, renewals).
"""

import argparse
import json
import os
import sys

# (op, site fragment) -> model action. The first entry whose fragment appears in the call site wins.
ACTIONS = [
    ("createWorkflow", "SkipperEngine.startWorkflow", "StartCreate"),
    ("schedule", "SkipperEngine.runSignal", "SignalSched"),
    ("schedule", "SkipperEngine.scheduleExecution", "StartSched"),
    ("schedule", "TimerTaskHandler.handle", "TmSched"),
    ("schedule", "WorkflowExecutionTaskHandler.persistExecutionResult", "WfTimers"),
    ("schedule", "SkipperSchedulerManager.scheduleRerunNow", "Finish"),
    ("fetch", "SkipperSchedulerManager", "Fetch"),
    ("remove", "SkipperSchedulerManager", "Finish"),
    ("rescheduleForRetry", "SkipperSchedulerManager", "Finish"),
    ("markAsFailed", "SkipperSchedulerManager", "MarkFailed"),
    ("renewLease", "LeaseRenewalManager", "Renew"),
    ("requeueFailedTask", "", "Requeue"),
    ("updateWorkflowAndStoreCheckpointsAndTimers", "WorkflowExecutionTaskHandler.persistExecutionResult", "WfPersist"),
    ("updateWorkflow", "SkipperEngine.runSignal", "SignalPersist"),
    ("updateWorkflowAndMarkSignal", "SkipperEngine.runSignal", "SignalPersist"),
    ("expireTimer", "TimerTaskHandler.handle", "TmExec"),
    # The reads the recorder logs (TracingWorkflowStore): each is a model step.
    ("getWorkflow", "WorkflowExecutionTaskHandler.handle", "WfRead"),
    ("getTimers", "WorkflowExecutionTaskHandler.handle", "WfRun"),
    ("getWorkflow", "SkipperEngine.runSignal", "SignalStart"),
]

READS = {"WfRead", "WfRun", "SignalStart", "SignalRejected"}

# Statuses runSignal refuses a signal in (isTerminal or isCompensationInProgress).
SIGNAL_REFUSED = {"COMPLETED", "ERROR", "TIMEOUT", "COMPENSATION_COMPLETED", "CANCELLED",
                  "COMPENSATION_IN_PROGRESS", "COMPENSATION_ERROR"}

# Writes to rows the model does not track. storeActionCheckpoints never touches the workflow row
# on the JDBC stores.
IGNORED_OPS = {"storeActionCheckpoints", "persistSignal", "updateSignalStatus"}

# Engine features the model does not cover yet, recognised by call site, for the skip report.
OUT_OF_SCOPE = [
    ("Compensation", "compensation"),
    ("ExecutionTimeout", "execution timeout"),
    ("handleWorkflowTimeout", "execution timeout"),
    ("cancelWorkflow", "cancellation"),
    ("forceDeleteWorkflow", "deletion"),
    ("rewindWorkflow", "rewind"),
    ("resetWorkflowFromError", "reset from error"),
    ("cloneWorkflowInstance", "clone"),
]

WORKERS = 3
EARLY_EXPIRIES = 5


class OutOfScope(Exception):
    pass


def action_for(event):
    site = event["site"]
    for op, fragment, action in ACTIONS:
        if event["op"] == op and any(fragment in frame for frame in site):
            if op == "schedule" and event["args"]["type"] in ("EXECUTION_TIMEOUT", "COMPENSATION"):
                break
            return action
    for fragment, feature in OUT_OF_SCOPE:
        if fragment in event["op"] or any(fragment in frame for frame in site):
            raise OutOfScope(feature)
    if event["op"] == "schedule" and event["args"]["type"] in ("EXECUTION_TIMEOUT", "COMPENSATION"):
        raise OutOfScope(event["args"]["type"].lower().replace("_", " "))
    raise OutOfScope(f"unmapped write {event['op']} from {site[:3]}")


class Workflow:
    """One workflow's events, normalised to model values."""

    def __init__(self, workflow_id):
        self.id = workflow_id
        self.events = []
        self.timers = []          # timer ids in order of first appearance: model timer k = index + 1
        self.lease_values = {}    # task id -> run_after values a lease-setting write produced

    def timer_index(self, timer_id):
        if timer_id not in self.timers:
            self.timers.append(timer_id)
        return self.timers.index(timer_id) + 1

    def task_index(self, task_id):
        if task_id == self.id:
            return 0
        prefix = self.id + ":"
        if task_id.startswith(prefix):
            return self.timer_index(task_id[len(prefix):])
        raise OutOfScope(f"task {task_id}")


def normalise(workflow, event, header, action):
    state = event["state"]
    lease_ms = header["leaseMs"]
    now = event["t"]
    for task_id in state["tasks"]:
        if task_id.endswith("-compensation"):
            raise OutOfScope("compensation")
        if task_id.endswith(":timeout"):
            raise OutOfScope("execution timeout")
    for timer_id in state["timers"]:
        workflow.timer_index(timer_id)

    # A lease-setting write leaves its run_after on the task: remember it, so a later snapshot of
    # the same value reads as a lease rather than as a short backoff.
    task = event.get("task")
    row = state["tasks"].get(task) if task else None
    if row and event["ok"]:
        in_memory = event["op"] == "schedule" and any("SchedulerExecutionQueue.schedule" in f for f in event["site"])
        if event["op"] in ("fetch", "renewLease") or in_memory:
            workflow.lease_values.setdefault(task, set()).add(row["ra"])

    def cls(task_id, r):
        if r["ra"] <= now:
            return "DUE"
        if r["ra"] in workflow.lease_values.get(task_id, ()):
            return "LEASE"
        return "SOON" if r["ra"] <= now + lease_ms else "LATER"

    rows = {}
    for task_id, r in state["tasks"].items():
        rows[workflow.task_index(task_id)] = {"st": r["st"], "ver": r["ver"], "rc": r["rc"], "cls": cls(task_id, r)}
    wf = state["wf"]
    if action == "SignalStart" and wf and wf["st"] in SIGNAL_REFUSED:
        action = "SignalRejected"   # runSignal throws TerminalWorkflowError before touching anything
    return {
        "act": action,
        "seq": event["seq"],
        "hv": event["args"].get("heldVersion") or 0,
        "wf": {"ex": wf is not None, "st": wf["st"] if wf else "NONE", "ver": wf["ver"] if wf else 0},
        "timers": {workflow.timer_index(k): v for k, v in state["timers"].items()},
        "rows": rows,
    }


def durable(e):
    """What a write can change in the model: everything but run_after classes."""
    return (e["wf"], e["timers"], {k: (r["st"], r["ver"], r["rc"]) for k, r in e["rows"].items()})


def tla_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def tla_event(e, n_waits):
    none_row = '[st |-> "NONE", ver |-> 0, rc |-> 0, cls |-> "ANY"]'
    rows = []
    for t in range(n_waits + 1):
        r = e["rows"].get(t)
        rows.append(f"{t} :> " + (none_row if r is None else
                    f'[st |-> "{r["st"]}", ver |-> {r["ver"]}, rc |-> {r["rc"]}, cls |-> "{r["cls"]}"]'))
    timers = ", ".join(tla_string(e["timers"].get(k, "NONE")) for k in range(1, n_waits + 1))
    wf = e["wf"]
    return (f'[act |-> "{e["act"]}", noop |-> {"TRUE" if e["noop"] else "FALSE"}, '
            f'read |-> {"TRUE" if e["act"] in READS else "FALSE"}, seq |-> {e["seq"]}, hv |-> {e["hv"]}, '
            f'wf |-> [ex |-> {"TRUE" if wf["ex"] else "FALSE"}, st |-> "{wf["st"]}", ver |-> {wf["ver"]}], '
            f'tm |-> <<{timers}>>, row |-> ({" @@ ".join(rows)})]')


def model_files(workflow, header):
    events = workflow.events
    n_waits = len(workflow.timers)
    gates = header["gates"]
    count = lambda act: sum(1 for e in events if e["act"] == act)
    constants = {
        "Workers": "{" + ", ".join(f"p{i}" for i in range(1, WORKERS + 1)) + "}",
        "NWaits": n_waits,
        "MaxRetries": header["maxRetries"],
        "MaxSignals": count("SignalStart"),
        "MaxFaults": EARLY_EXPIRIES,
        "MaxRenewals": count("Renew"),
        "MaxRequeues": count("Requeue"),
        "Faults": '{"early_expiry"}',
        "NoopOnExisting": gates["CREATE_EXISTING_WORKFLOW_IS_NOOP"],
        "BumpOnHonoredLease": gates["BUMP_TASK_VERSION_ON_HONORED_LEASE"],
        "InMemoryStart": True,
        "SignalInMemory": not gates["FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER"],
        "MaxStartCalls": max(1, count("StartCreate")),
        "UserFailures": True,
        "FreeBody": True,
    }
    fmt = lambda v: ("TRUE" if v else "FALSE") if isinstance(v, bool) else str(v)
    cfg = "SPECIFICATION TraceSpec\nCONSTANTS\n"
    cfg += "".join(f"    {k} = {fmt(v)}\n" for k, v in constants.items())
    cfg += "    TraceLog <- TraceLogValue\n    ReportProgress = FALSE\n    Results <- TraceResults\n"
    cfg += "    FaultsCounted <- NoFaultCount\n"
    cfg += "SYMMETRY WorkerSymmetry\nINVARIANT TraceNotDone\nCHECK_DEADLOCK FALSE\n"
    body = ",\n    ".join(tla_event(e, n_waits) for e in events)
    tla = f"---- MODULE MC ----\nEXTENDS SkipperTrace\n\nTraceLogValue == <<\n    {body}\n>>\n====\n"
    return tla, cfg


def convert(path, out_dir):
    lines = [json.loads(line) for line in open(path)]
    if not lines:
        return
    header, events = lines[0], lines[1:]
    workflows = {}
    skipped = {}
    for event in events:
        wid = event["wf"]
        if wid in skipped:
            continue
        workflow = workflows.setdefault(wid, Workflow(wid))
        if event["op"] in IGNORED_OPS:
            continue
        try:
            e = normalise(workflow, event, header, action_for(event))
        except OutOfScope as reason:
            skipped[wid] = str(reason)
            continue
        previous = workflow.events[-1] if workflow.events else None
        e["noop"] = durable(e) == (durable(previous) if previous else durable_initial())
        workflow.events.append(e)

    base = os.path.splitext(os.path.basename(path))[0]
    for wid, workflow in workflows.items():
        report = {"trace": path, "workflow": wid, "origin": test_name(header)}
        if wid in skipped:
            print(json.dumps({**report, "status": "skipped", "reason": skipped[wid]}))
            continue
        name = f"{base}-{wid}"
        target = os.path.join(out_dir, name)
        os.makedirs(target, exist_ok=True)
        tla, cfg = model_files(workflow, header)
        open(os.path.join(target, "MC.tla"), "w").write(tla)
        open(os.path.join(target, "MC.cfg"), "w").write(cfg)
        print(json.dumps({**report, "status": "model", "dir": target, "events": len(workflow.events)}))


def test_name(header):
    """The test a trace came from: the first test-looking frame, else where tracing was installed."""
    frames = [f for f in header.get("origin", []) if not f.startswith(("TimeoutExtension", "TestMethodTestDescriptor"))]
    return (frames or header.get("installedBy", []) or ["?"])[:1]


def durable_initial():
    return ({"ex": False, "st": "NONE", "ver": 0}, {}, {})


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("traces", nargs="+")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    for path in args.traces:
        convert(path, args.out)


if __name__ == "__main__":
    sys.exit(main())
