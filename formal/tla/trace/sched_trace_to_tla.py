#!/usr/bin/env python3
"""Turns recorded scheduler traces into TLC models of SkipperSchedulerTrace, one per runtime.

    sched_trace_to_tla.py <sched.ndjson>... --out <dir>

A trace file (written by testutils' SchedulerTraceRecorder) holds what one runtime's scheduler did
in memory: fetches, tasks added to the in-memory queue, taken, handled and finished, and slots
given back. For each file this either writes <dir>/<name>/MC.tla and MC.cfg, a model TLC checks
against SkipperSchedulerTrace, or reports why the run is out of the model's scope. It prints one
JSON line per file, in the shape trace_to_tla.py uses, so check_traces.sh runs and reports both
kinds the same way.

The mapping:
  * each line becomes the model action it stands for (ACTIONS below);
  * task ids become 1..n, in order of first appearance;
  * the model's limit and fetch size are the ones in force at the run's fetches. A run in which
    they changed is skipped: they are constants of the model.

A run in which the test itself adds a task to the queue, doing the fetch loop's job by hand, is
skipped too. A task the fetch loop adds that no recorded fetch came back with is not: it is left
for the model to reject, so a recorder that stops seeing fetches fails the check rather than
quietly emptying it.
"""

import argparse
import json
import os
import sys

ACTIONS = {"fetch": "Fetch", "add": "Add", "take": "Take", "start": "Start", "report": "Report",
           "done": "Done", "release": "Release"}


class OutOfScope(Exception):
    pass


def normalise(lines):
    """The events as model values, plus the constants they imply."""
    tasks = {}
    number = lambda task_id: tasks.setdefault(task_id, len(tasks) + 1)
    limits = set()
    events = []
    for line in lines:
        act = ACTIONS[line["op"]]
        event = {"act": act, "seq": line["seq"], "task": 0, "origin": "none", "added": False,
                 "admitted": line["admitted"], "requested": 0, "tasks": []}
        if act == "Fetch":
            limits.add((line["limit"], line["fetchSize"]))
            event["requested"] = line["requested"]
            event["tasks"] = sorted(number(t) for t in line["tasks"])
        else:
            if act != "Release":
                event["task"] = number(line["task"])
            if act == "Add":
                if line["origin"] == "direct":
                    raise OutOfScope("the test adds a task to the queue itself")
                event["origin"], event["added"] = line["origin"], line["added"]
        events.append(event)
    if len(limits) > 1:
        raise OutOfScope("the concurrency limit or fetch size changed during the run")
    limit, fetch_size = next(iter(limits), (0, 1))
    if fetch_size < 1:
        raise OutOfScope("the fetch size is zero, or sized from the backlog")
    return events, {"NTasks": max(1, len(tasks)), "Limit": limit, "FetchSize": fetch_size}


def tla_event(e):
    tasks = "{" + ", ".join(map(str, e["tasks"])) + "}"
    added = "TRUE" if e["added"] else "FALSE"
    return (f'[act |-> "{e["act"]}", seq |-> {e["seq"]}, task |-> {e["task"]}, origin |-> "{e["origin"]}", '
            f'added |-> {added}, admitted |-> {e["admitted"]}, requested |-> {e["requested"]}, tasks |-> {tasks}]')


def model_files(events, constants):
    constants = {
        **constants,
        "NInstances": 1,
        "NBuckets": 1,
        "Partitioning": "FALSE",
        "InMemTasks": "{}",
        # A copy that outlives its lease in the queue, and a run that ends before it has a handler,
        # both happen in real runs. A slot given back while its handler still runs (finding 1) is
        # left out, so a run that shows it is rejected.
        "Faults": '{"lapse", "abort"}',
        "MaxFaults": 1000000,
        "MaxHangs": 0,
    }
    cfg = "SPECIFICATION TraceSpec\nCONSTANTS\n"
    cfg += "".join(f"    {k} = {v}\n" for k, v in constants.items())
    cfg += "    TraceLog <- TraceLogValue\n    ReportProgress = FALSE\n"
    cfg += "    Fetchable <- TraceFetchable\n    Schedulable <- TraceSchedulable\n"
    cfg += "    LeaseMoved <- TraceLeaseMoved\n    FetchChoices <- TraceFetchChoices\n"
    cfg += "INVARIANT TraceNotDone\nCHECK_DEADLOCK FALSE\n"
    body = ",\n    ".join(tla_event(e) for e in events)
    tla = f"---- MODULE MC ----\nEXTENDS SkipperSchedulerTrace\n\nTraceLogValue == <<\n    {body}\n>>\n====\n"
    return tla, cfg


def test_name(header):
    frames = [f for f in header.get("origin", []) if not f.startswith(("TimeoutExtension", "TestMethodTestDescriptor"))]
    return (frames or ["?"])[:1]


def convert(path, out_dir):
    lines = [json.loads(line) for line in open(path)]
    if len(lines) < 2:
        return
    header, lines = lines[0], lines[1:]
    base = os.path.splitext(os.path.basename(path))[0]
    report = {"trace": path, "workflow": base, "origin": test_name(header), "kind": "scheduler"}
    try:
        events, constants = normalise(lines)
    except OutOfScope as reason:
        print(json.dumps({**report, "status": "skipped", "reason": str(reason)}))
        return
    target = os.path.join(out_dir, base)
    os.makedirs(target, exist_ok=True)
    tla, cfg = model_files(events, constants)
    open(os.path.join(target, "MC.tla"), "w").write(tla)
    open(os.path.join(target, "MC.cfg"), "w").write(cfg)
    fetched = sum(1 for e in events if e["act"] == "Add" and e["origin"] == "fetched")
    print(json.dumps({**report, "status": "model", "dir": target, "events": len(events), "fetched": fetched}))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("traces", nargs="+")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    for path in args.traces:
        convert(path, args.out)


if __name__ == "__main__":
    sys.exit(main())
