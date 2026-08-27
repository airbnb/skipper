#!/usr/bin/env bash
#
# Prints the next semantic version implied by the commits since the last v* tag.
#
#   next-version.sh              the version to release, or exit 3 if nothing releasable landed
#   next-version.sh --snapshot   always prints a version (treats "nothing releasable" as a patch)
#   next-version.sh --build      a unique, monotonic version for THIS commit; never fails
#
# --build exists so every merge to main can publish an immutable artifact that consumers
# can pin, with no human ever choosing a version. It prints 0.<minor>.<commit count>, and
# because main takes squash merges its history is linear - so the count is monotonic and
# unique per commit, and sorts correctly under Maven's version ordering. It deliberately
# carries no semantic meaning: it is a build number, not a release.
#
# The bump signal is the commit subject. Because main takes squash merges, that subject is
# the pull request title, so one required check on PR titles is the whole enforcement story
# and no per-commit discipline is needed.
#
#   feat!: / BREAKING CHANGE:   major   (minor while the major version is 0 - semver 4)
#   feat:                       minor
#   fix: / perf:                patch
#   anything else               no release; snapshots only
#
# The highest bump across the range wins, so a feat batched with three fixes is one minor.

set -euo pipefail

snapshot=false
build=false
case ${1:-} in
    "") ;;
    --snapshot) snapshot=true ;;
    --build) build=true ;;
    *)
        echo "usage: $(basename "$0") [--snapshot | --build]" >&2
        exit 64
        ;;
esac

# --build ignores commit prefixes entirely: every commit gets a version, and the minor
# component tracks the last tag so a later `git tag v0.2.0` moves the whole series along.
if [[ $build == true ]]; then
    build_tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
    if [[ -z $build_tag ]]; then
        build_minor=1
    else
        IFS=. read -r _ build_minor _ <<<"${build_tag#v}"
    fi
    echo "0.${build_minor}.$(git rev-list --count HEAD)"
    exit 0
fi

# --match keeps stray tags (release candidates, internal markers) out of the calculation.
last_tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)

# No tags yet: this is the first release.
if [[ -z $last_tag ]]; then
    echo "0.1.0"
    exit 0
fi

IFS=. read -r major minor patch <<<"${last_tag#v}"

bump=none
while IFS= read -r subject; do
    case $subject in
        # `!` before the colon marks a breaking change, e.g. "feat(api)!: drop X".
        *'!:'*)
            bump=major
            ;;
        feat:* | feat\(*)
            if [[ $bump != major ]]; then bump=minor; fi
            ;;
        fix:* | fix\(* | perf:* | perf\(*)
            if [[ $bump == none ]]; then bump=patch; fi
            ;;
    esac
done < <(git log --format='%s' "${last_tag}..HEAD")

# A body trailer carries the same weight as the `!` shorthand.
if git log --format='%b' "${last_tag}..HEAD" | grep -q '^BREAKING CHANGE'; then
    bump=major
fi

# Semver 4: anything may change in 0.x, so a break is only a minor bump until 1.0.0.
if [[ $major -eq 0 && $bump == major ]]; then
    bump=minor
fi

# A snapshot still needs a number even when nothing releasable landed.
if [[ $bump == none && $snapshot == true ]]; then
    bump=patch
fi

case $bump in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "$major.$((minor + 1)).0" ;;
    patch) echo "$major.$minor.$((patch + 1))" ;;
    none) exit 3 ;;
esac
