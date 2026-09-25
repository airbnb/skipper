#!/usr/bin/env bash
# Model-checks SkipperWorkflowLiveness.tla against each config in models/ (or the ones named) and
# compares every verdict with the one the config records on its `\* expect:` line.
#
#   TLA2TOOLS=/path/to/tla2tools.jar formal/tla/check.sh [model ...]
#
# A verdict is `ok`, the name of the invariant or action property TLC reports as violated, or
# `temporal` for a violated liveness property. Exits 1 when any verdict differs from its
# expectation, so a model whose known bug gets fixed fails too, until its `expect:` line is
# updated. Full TLC output, counterexample traces included, goes to $OUT_DIR (default: a temp
# dir), one <model>.out per model.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
jar="${TLA2TOOLS:?set TLA2TOOLS to the path of tla2tools.jar (github.com/tlaplus/tlaplus/releases)}"
if [ ! -f "$jar" ]; then
  echo "TLA2TOOLS=$jar: no such file (download tla2tools.jar from github.com/tlaplus/tlaplus/releases)" >&2
  exit 1
fi
out_dir="${OUT_DIR:-$(mktemp -d)}"
mkdir -p "$out_dir"

if [ "$#" -eq 0 ]; then
  set -- $(cd "$here/models" && ls *.cfg | sed 's/\.cfg$//')
fi

verdict_of() {
  local out="$1" name
  if grep -q "No error has been found" "$out"; then
    echo ok
  elif name="$(grep -m1 -oE '(Invariant|Action property) [A-Za-z0-9_]+ is violated' "$out")"; then
    echo "$name" | awk '{ print $(NF - 2) }'
  elif grep -q "Temporal properties were violated" "$out"; then
    echo temporal
  else
    echo error
  fi
}

cd "$here"
mismatches=0
for model in "$@"; do
  cfg="models/$model.cfg"
  out="$out_dir/$model.out"
  if [ ! -f "$cfg" ]; then
    echo "no model $model ($cfg does not exist)" >&2
    exit 1
  fi
  expected="$(sed -nE 's/^\\\* expect: *([A-Za-z0-9_]+).*/\1/p' "$cfg" | head -1)"
  java -XX:+UseParallelGC -cp "$jar" tlc2.TLC \
    -config "$cfg" -workers auto -metadir "$out_dir/states/$model" \
    SkipperWorkflowLiveness.tla >"$out" 2>&1 || true
  rm -rf "$out_dir/states/$model" # TLC's on-disk state queue; only the .out is kept
  actual="$(verdict_of "$out")"
  if [ -n "$expected" ] && [ "$actual" = "$expected" ]; then
    status="as expected"
  else
    status="MISMATCH: expected ${expected:-nothing (add an \\* expect: line)}"
    mismatches=$((mismatches + 1))
  fi
  printf '%-26s %-18s %s\n' "$model" "$actual" "$status"
done
echo "TLC output: $out_dir"

if [ "$mismatches" -gt 0 ]; then
  echo "$mismatches model(s) did not match their expected verdict; see the .out files above." >&2
  exit 1
fi
