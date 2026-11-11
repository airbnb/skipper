#!/usr/bin/env python3
"""Self-test for trace validation: corrupts accepted traces, each of which the checker must reject.

    mutants.py <workflows.jsonl> --out <dir>

For every workflow model check_traces.sh built, and for the longest scheduler runs, writes one
mutant per kind below into <dir> and
prints a workflows.jsonl-style line for each, so the same TLC runner and report.py (with
--expect-rejected) check them. A mutant the checker still accepts means validation is looser than
it should be, or the corruption happens to be another behaviour the engine could have taken; each
survivor needs a look.
"""

import argparse
import json
import os
import re

WF = re.compile(r'wf \|-> \[ex \|-> TRUE, st \|-> "(\w+)", ver \|-> (\d+)\]')
ROW0 = re.compile(r'row \|-> \(0 :> \[st \|-> "(PENDING|RUNNING|FAILED)", ver \|-> (\d+)')
OTHER_STATUS = {
    "RUNNING": "COMPLETED", "WAITING": "RUNNING", "COMPLETED": "ERROR", "ERROR": "COMPLETED",
    "TRANSIENT_ERROR": "RUNNING", "RETRIES_EXHAUSTED": "ERROR",
}


def wf_version(line):
    return WF.sub(lambda m: f'wf |-> [ex |-> TRUE, st |-> "{m.group(1)}", ver |-> {int(m.group(2)) + 1}]', line, 1)


def wf_status(line):
    return WF.sub(lambda m: f'wf |-> [ex |-> TRUE, st |-> "{OTHER_STATUS[m.group(1)]}", ver |-> {m.group(2)}]', line, 1)


def task_version(line):
    return ROW0.sub(lambda m: f'row |-> (0 :> [st |-> "{m.group(1)}", ver |-> {int(m.group(2)) + 1}', line, 1)


ADMITTED = re.compile(r"admitted \|-> (\d+)")
REQUESTED = re.compile(r"requested \|-> (\d+)")
TASK = re.compile(r"task \|-> (\d+)")
SCHEDULER_KINDS = ("count", "requested", "drop_take", "report_first", "release_first")
# The self-test mutates this many scheduler runs, the longest ones: there is one per runtime, and
# five TLC runs for each would dwarf the check itself.
SCHEDULER_SAMPLE = 40


def act(event):
    return event.split('"')[1]


def task(event):
    return TASK.search(event).group(1)


def mutate_scheduler(events, kind):
    """As mutate(), for a scheduler run's events."""
    out = list(events)
    middle = lambda candidates: candidates[len(candidates) // 2] if candidates else None
    if kind == "count":
        # The queue counted one task more than the scheduler took on.
        i = len(events) // 2
        out[i] = ADMITTED.sub(lambda m: f"admitted |-> {int(m.group(1)) + 1}", events[i], 1)
    elif kind == "requested":
        # A fetch asked for one task more than the free slots (or the fetch size) allowed.
        i = middle([k for k, e in enumerate(events) if act(e) == "Fetch"])
        if i is None:
            return None
        out[i] = REQUESTED.sub(lambda m: f"requested |-> {int(m.group(1)) + 1}", events[i], 1)
    elif kind == "drop_take":
        # A task ran without being taken from the queue.
        i = middle([k for k, e in enumerate(events) if act(e) == "Take"
                    and any(act(later) == "Start" and task(later) == task(e) for later in events[k + 1:])])
        if i is None:
            return None
        del out[i]
    elif kind == "report_first":
        # A handler handed back its future before it was called.
        i = middle([k for k in range(len(events) - 1) if act(events[k]) == "Start"
                    and act(events[k + 1]) == "Report" and task(events[k]) == task(events[k + 1])])
        if i is None:
            return None
        out[i], out[i + 1] = out[i + 1], out[i]
    elif kind == "release_first":
        # The last slot in use was given back before its handler finished (finding 1's shape).
        i = middle([k for k in range(len(events) - 1) if act(events[k]) == "Done"
                    and act(events[k + 1]) == "Release" and "admitted |-> 0" in events[k + 1]])
        if i is None:
            return None
        out[i] = ADMITTED.sub("admitted |-> 0", events[i + 1], 1)
        out[i + 1] = ADMITTED.sub("admitted |-> 0", events[i], 1)
    return out


def mutate(events, kind):
    """Returns the mutated event lines, or None when this trace offers nothing to mutate this way."""
    changing = [i for i, e in enumerate(events) if "noop |-> FALSE" in e]
    if not changing:
        return None
    i = changing[len(changing) // 2]
    out = list(events)
    if kind == "drop":
        # Dropping the last write leaves a prefix of the run, which is itself a valid run.
        if len(changing) < 2:
            return None
        del out[changing[:-1][len(changing[:-1]) // 2]]
    elif kind == "swap":
        later = [j for j in changing if j > i]
        if not later:
            return None
        j = later[0]
        out[i], out[j] = out[j], out[i]
    else:
        fn = {"wf_version": wf_version, "wf_status": wf_status, "task_version": task_version}[kind]
        candidates = [j for j in changing if fn(events[j]) != events[j]]
        if not candidates:
            return None
        j = candidates[len(candidates) // 2]
        out[j] = fn(events[j])
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workflows")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    rows = [r for r in map(json.loads, open(args.workflows)) if r["status"] == "model"]
    scheduler_runs = sorted((r for r in rows if r.get("kind") == "scheduler"), key=lambda r: -r["events"])
    skipped = {r["dir"] for r in scheduler_runs[SCHEDULER_SAMPLE:]}
    for r in rows:
        if r["dir"] in skipped:
            continue
        scheduler = r.get("kind") == "scheduler"
        tla = open(os.path.join(r["dir"], "MC.tla")).read().splitlines()
        cfg = open(os.path.join(r["dir"], "MC.cfg")).read()
        idx = [k for k, line in enumerate(tla) if line.strip().startswith("[act")]
        events = [tla[k].strip().rstrip(",") for k in idx]
        for kind in SCHEDULER_KINDS if scheduler else ("wf_version", "wf_status", "task_version", "drop", "swap"):
            mutated = mutate_scheduler(events, kind) if scheduler else mutate(events, kind)
            if mutated is None:
                continue
            body = ",\n    ".join(mutated)
            head, tail = tla[: idx[0]], tla[idx[-1] + 1:]
            target = os.path.join(args.out, f"{os.path.basename(r['dir'])}-{kind}")
            os.makedirs(target, exist_ok=True)
            open(os.path.join(target, "MC.tla"), "w").write("\n".join(head + ["    " + body] + tail) + "\n")
            open(os.path.join(target, "MC.cfg"), "w").write(cfg)
            print(json.dumps({**r, "dir": target, "events": len(mutated), "mutation": kind}))


if __name__ == "__main__":
    main()
