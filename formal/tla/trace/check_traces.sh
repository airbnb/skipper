#!/usr/bin/env bash
# Trace validation: checks recorded engine runs against the TLA+ model.
#
#   TLA2TOOLS=/path/to/tla2tools.jar formal/tla/trace/check_traces.sh [--self-test] <trace-dir>
#
# <trace-dir> holds the trace-*.ndjson files a test run writes with -PskipperTraceDir=<trace-dir>.
# Each workflow in them becomes a TLC model of SkipperTrace (trace_to_tla.py) and is checked in
# parallel. One line per workflow:
#
#   accepted   the model can take exactly the steps the engine took
#   REJECTED   it cannot; the line names the first write it could not explain
#   skipped    the run uses an engine feature the model does not cover yet
#   INCONCLUSIVE  TLC did not decide within $TRACE_BUDGET seconds (default 300)
#
# Exits 1 when any workflow is rejected. An inconclusive one does not fail the run but is listed
# (and flagged as a warning on GitHub Actions): a rejection needs TLC to exhaust every
# interleaving, so a slow trace may be hiding one. With --self-test it then corrupts every accepted trace in a
# few ways (mutants.py) and exits 1 unless the checker rejects all of them, which guards against a
# checker loose enough to accept anything. Models and TLC output go to $OUT_DIR (default: a temp dir).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
jar="${TLA2TOOLS:?set TLA2TOOLS to the path of tla2tools.jar (github.com/tlaplus/tlaplus/releases)}"
if [ ! -f "$jar" ]; then
  echo "TLA2TOOLS=$jar: no such file (download tla2tools.jar from github.com/tlaplus/tlaplus/releases)" >&2
  exit 1
fi
self_test=false
if [ "${1:-}" = "--self-test" ]; then
  self_test=true
  shift
fi
trace_dir="${1:?usage: check_traces.sh [--self-test] <trace-dir>}"
out_dir="${OUT_DIR:-$(mktemp -d)}"
mkdir -p "$out_dir/models"

shopt -s nullglob
traces=("$trace_dir"/trace-*.ndjson)
if [ "${#traces[@]}" -eq 0 ]; then
  echo "no trace-*.ndjson files in $trace_dir" >&2
  exit 1
fi
python3 "$here/trace_to_tla.py" "${traces[@]}" --out "$out_dir/models" >"$out_dir/workflows.jsonl"

# TLC runs one model per JVM; a single worker keeps each run's PrintT output in order, and a private
# java.io.tmpdir keeps parallel runs from clobbering the standard modules TLC unpacks there.
run_models() {
  # Each model directory goes to one sh as $1; the rest travels in the environment (xargs -I would
  # cap the command line at 255 bytes on macOS).
  python3 -c 'import json,sys; [print(r["dir"]) for r in map(json.loads, open(sys.argv[1])) if r["status"] == "model"]' "$1" |
    TLA_LIBRARY="$here/..:$here" TLA_JAR="$jar" BUDGET="${TRACE_BUDGET:-300}" xargs -P "${JOBS:-4}" -n 1 sh -c '
      mkdir -p "$1/tmp" && cd "$1" && perl -e "alarm shift; exec @ARGV" "$BUDGET" \
        java -XX:+UseParallelGC -Djava.io.tmpdir="$1/tmp" -DTLA-Library="$TLA_LIBRARY" -cp "$TLA_JAR" tlc2.TLC \
        -config MC.cfg -workers 1 -metadir "$1/states" MC.tla >"$1/tlc.out" 2>&1
      rm -rf "$1/states" "$1/tmp"; true' _
}

run_models "$out_dir/workflows.jsonl"
# A rejected run is checked again with progress printing on, to find the first write the model
# cannot explain. Printing on every run would cost more than the check itself.
python3 "$here/report.py" "$out_dir/workflows.jsonl" --rejected-dirs >"$out_dir/rejected.txt" || true
if [ -s "$out_dir/rejected.txt" ]; then
  while read -r dir; do
    sed -i.bak 's/ReportProgress = FALSE/ReportProgress = TRUE/' "$dir/MC.cfg"
  done <"$out_dir/rejected.txt"
  python3 -c 'import json,sys; ds=set(open(sys.argv[2]).read().split()); [print(l, end="") for l in open(sys.argv[1]) if json.loads(l).get("dir") in ds]' \
    "$out_dir/workflows.jsonl" "$out_dir/rejected.txt" >"$out_dir/rejected.jsonl"
  run_models "$out_dir/rejected.jsonl"
fi
status=0
python3 "$here/report.py" "$out_dir/workflows.jsonl" || status=1

if $self_test; then
  python3 "$here/mutants.py" "$out_dir/workflows.jsonl" --out "$out_dir/mutants" >"$out_dir/mutants.jsonl"
  run_models "$out_dir/mutants.jsonl"
  python3 "$here/report.py" "$out_dir/mutants.jsonl" --expect-rejected || status=1
fi
exit $status
