#!/usr/bin/env bash
# Runs the engine's end-to-end test suites with trace recording on, writing trace-*.ndjson files
# into <trace-dir> for check_traces.sh:
#
#   formal/tla/trace/record_traces.sh <trace-dir>
#
# The suites are the ones that drive a real runtime on the SQLite backend. Tests that fail still
# leave their traces, but the script exits non-zero, since a trace of a broken run proves little.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
trace_dir="${1:?usage: record_traces.sh <trace-dir>}"
mkdir -p "$trace_dir"
trace_dir="$(cd "$trace_dir" && pwd)"

cd "$here/../../.."
./gradlew --stacktrace \
  :test \
  --tests 'com.airbnb.skipper.integtest.*' \
  --tests 'com.airbnb.skipper.CoroutineWorkflowTest' \
  --tests 'com.airbnb.skipper.WorkflowFactoryTest' \
  --tests 'com.airbnb.skipper.WorkflowServiceTest' \
  --tests 'com.airbnb.skipper.internal.SkipperSchedulerManager*' \
  --tests 'com.airbnb.skipper.internal.scheduler.sqlite.*' \
  --tests 'com.airbnb.skipper.internal.scheduler.LeaseRenewalManagerTest' \
  --tests 'com.airbnb.skipper.internal.scheduler.TimerTaskHandlerTest' \
  :skipper-testutils:test \
  -PskipperTraceDir="$trace_dir"
echo "traces: $(ls "$trace_dir" | wc -l | tr -d ' ') files in $trace_dir"
