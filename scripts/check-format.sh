#!/usr/bin/env bash
#
# Checks (or, with --fix, rewrites) the formatting of every Kotlin and Java source file, using
# the same formatters, versions and settings that `yak lint` applies to this code in treehouse:
#
#   *.kt    ktlint 1.2.1, configured by the .editorconfig at the repository root
#   *.java  google-java-format 1.26.0, Google style (it has no configuration)
#
# Like `yak lint`, only files under a `src/` directory are checked, so build scripts and the
# `web/` site are not. Unlike `yak lint`, which touches only the files changed on the branch,
# this checks every file, so a stale file fails here even when it was not touched.
#
# Usage:
#   scripts/check-format.sh          exit 1 and list the files that are not formatted
#   scripts/check-format.sh --fix    rewrite them in place
#
# Needs curl, git and a JDK 17 or newer on PATH (google-java-format 1.26 refuses older ones).
# The formatter binaries are downloaded once, checksum-verified, and cached in
# $SKIPPER_FORMAT_TOOLS_DIR (default ~/.cache/skipper-format). The CircleCI format-check job
# runs exactly this script, so a green local run means a green CI check.

set -euo pipefail

KTLINT_VERSION=1.2.1
KTLINT_SHA256=2e28cf46c27d38076bf63beeba0bdef6a845688d6c5dccd26505ce876094eb92
KTLINT_URL="https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint"

GJF_VERSION=1.26.0
GJF_SHA256=02a361357297fa962918c1d08830d50b17d62984d2a8649159b95b9a6d9f82b2
GJF_URL="https://github.com/google/google-java-format/releases/download/v${GJF_VERSION}/google-java-format-${GJF_VERSION}-all-deps.jar"

TOOLS_DIR="${SKIPPER_FORMAT_TOOLS_DIR:-$HOME/.cache/skipper-format}"

fix=false
case "${1:-}" in
  "") ;;
  --fix) fix=true ;;
  *) echo "usage: $0 [--fix]" >&2; exit 2 ;;
esac

repo_root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$repo_root"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# fetch <url> <sha256> <destination>: download unless a file with the right checksum is
# already there. A checksum mismatch, whether from a corrupt download or a replaced release
# asset, is fatal rather than silently accepted.
fetch() {
  local url="$1" expected="$2" dest="$3"
  if [ -f "$dest" ] && [ "$(sha256 "$dest")" = "$expected" ]; then
    return
  fi
  echo "Downloading $url"
  mkdir -p "$(dirname "$dest")"
  curl -fsSL --retry 3 -o "$dest.tmp" "$url"
  local actual
  actual="$(sha256 "$dest.tmp")"
  if [ "$actual" != "$expected" ]; then
    rm -f "$dest.tmp"
    echo "Checksum mismatch for $url" >&2
    echo "  expected $expected" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
  mv "$dest.tmp" "$dest"
}

ktlint="$TOOLS_DIR/ktlint-$KTLINT_VERSION"
gjf="$TOOLS_DIR/google-java-format-$GJF_VERSION.jar"
fetch "$KTLINT_URL" "$KTLINT_SHA256" "$ktlint"
fetch "$GJF_URL" "$GJF_SHA256" "$gjf"
chmod +x "$ktlint"

# Tracked files only, so a scratch file in the working tree cannot fail the check, and only
# those under a src/ directory, matching what yak lint considers source.
list_sources() {
  git ls-files -- "*.$1" | grep -E '(^|/)src/' || true
}
# Read into arrays with a loop rather than mapfile: macOS ships bash 3.2, which lacks it.
kt_files=()
while IFS= read -r f; do kt_files+=("$f"); done < <(list_sources kt)
java_files=()
while IFS= read -r f; do java_files+=("$f"); done < <(list_sources java)

status=0

if [ "${#kt_files[@]}" -gt 0 ]; then
  echo "ktlint $KTLINT_VERSION: ${#kt_files[@]} Kotlin files"
  # Same flags yak lint passes. --relative prints repository-relative paths; --limit caps the
  # report so one badly formatted file cannot flood the log.
  args=(--relative --editorconfig=.editorconfig --limit=100)
  if $fix; then args+=(--format); fi
  "$ktlint" "${args[@]}" "${kt_files[@]}" || status=1
fi

if [ "${#java_files[@]}" -gt 0 ]; then
  echo "google-java-format $GJF_VERSION: ${#java_files[@]} Java files"
  if $fix; then
    java -jar "$gjf" -i "${java_files[@]}" || status=1
  else
    # Prints the files that would change and exits non-zero if there are any.
    java -jar "$gjf" --dry-run --set-exit-if-changed "${java_files[@]}" || status=1
  fi
fi

if [ "$status" -ne 0 ]; then
  if $fix; then
    echo "Formatting failed for some files; see above." >&2
  else
    echo >&2
    echo "Some files are not formatted. Run scripts/check-format.sh --fix and commit the result." >&2
  fi
fi
exit "$status"
