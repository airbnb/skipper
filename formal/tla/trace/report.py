#!/usr/bin/env python3
"""Summarises a check_traces.sh run: one line per workflow, then totals. Exits 1 on any rejection.

    report.py <workflows.jsonl> [--expect-rejected | --rejected-dirs]

A model is accepted when TLC reports TraceNotDone violated, meaning some behaviour consumed every
event. Otherwise the highest event TLC printed as matched tells how far the model got, and the next
event is the first write it could not explain.

With --expect-rejected (the mutants.py self-test) the roles swap: every model should be rejected,
and the ones accepted are listed as survivors.
"""

import json
import os
import re
import sys


def verdict(model_dir):
    out = open(os.path.join(model_dir, "tlc.out")).read()
    if "Invariant TraceNotDone is violated" in out:
        return "accepted", None
    matched = [int(m.group(1)) for m in re.finditer(r'<<"matched", (\d+), \d+>>', out)]
    if "Error:" in out and "is violated" not in out and not matched:
        first_error = next(line for line in out.splitlines() if line.startswith("Error:"))
        return "error", first_error
    if "Model checking completed" not in out:
        return "INCONCLUSIVE", None   # stopped by check_traces.sh's time budget
    return "REJECTED", max(matched, default=0)


def first_unmatched(model_dir, matched):
    """The event after the last one TLC matched, as written in MC.tla."""
    events = [line.strip().rstrip(",") for line in open(os.path.join(model_dir, "MC.tla")) if line.strip().startswith("[act")]
    return events[matched] if matched < len(events) else None


def main():
    rows = [json.loads(line) for line in open(sys.argv[1])]
    if "--expect-rejected" in sys.argv[2:]:
        return mutants(rows)
    if "--rejected-dirs" in sys.argv[2:]:
        for r in rows:
            if r["status"] == "model" and verdict(r["dir"])[0] == "REJECTED":
                print(r["dir"])
        return 0
    totals = {}
    for r in rows:
        test = (r["origin"] or ["?"])[0]
        if r["status"] == "skipped":
            status, detail = "skipped", r["reason"]
        else:
            status, detail = verdict(r["dir"])
            if status == "accepted":
                detail = f'{r["events"]} events'
            elif status == "INCONCLUSIVE":
                detail = f'{r["events"]} events, over the time budget\n    model: {r["dir"]}'
                if os.environ.get("GITHUB_ACTIONS"):
                    print(f"::warning::trace validation inconclusive (time budget) for {test}")
            elif status == "REJECTED":
                event = first_unmatched(r["dir"], detail)
                detail = f'matched {detail} of {r["events"]} events; first unexplained: {event}\n    model: {r["dir"]}'
        totals[status] = totals.get(status, 0) + 1
        print(f"{status:9} {test}  ({detail})")
    print("totals: " + ", ".join(f"{k} {v}" for k, v in sorted(totals.items())))
    return 1 if totals.get("REJECTED") or totals.get("error") else 0


def mutants(rows):
    killed, survivors, undecided = 0, [], 0
    for r in rows:
        status, _ = verdict(r["dir"])
        if status == "REJECTED":
            killed += 1
        elif status == "INCONCLUSIVE":
            undecided += 1
        else:
            survivors.append(f'{status:9} {r["mutation"]:13} {(r["origin"] or ["?"])[0]}\n    model: {r["dir"]}')
    for s in survivors:
        print(s)
    print(f"mutants: {killed} rejected, {len(survivors)} survived, {undecided} inconclusive, of {len(rows)}")
    return 1 if survivors else 0


if __name__ == "__main__":
    sys.exit(main())
