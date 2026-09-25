#!/usr/bin/env python3
"""Self-test for trace validation: corrupts accepted traces, each of which the checker must reject.

    mutants.py <workflows.jsonl> --out <dir>

For every workflow model check_traces.sh built, writes one mutant per kind below into <dir> and
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
    for r in map(json.loads, open(args.workflows)):
        if r["status"] != "model":
            continue
        tla = open(os.path.join(r["dir"], "MC.tla")).read().splitlines()
        cfg = open(os.path.join(r["dir"], "MC.cfg")).read()
        idx = [k for k, line in enumerate(tla) if line.strip().startswith("[act")]
        events = [tla[k].strip().rstrip(",") for k in idx]
        for kind in ("wf_version", "wf_status", "task_version", "drop", "swap"):
            mutated = mutate(events, kind)
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
