#!/usr/bin/env bash
#
# Publishes a built copy of web/ to the gh-pages branch, which GitHub Pages serves.
#
#   publish-web.sh [built-site-dir]    defaults to web/dist
#
# GitHub Pages has two publishing models. The Actions model uploads the site as a run
# artifact and needs an OIDC token only GitHub Actions can mint, which this repository
# cannot use while it is private. The branch model, used here, needs nothing but push
# access: land the site on a branch and GitHub builds it from there. That makes the whole
# job a `git push`, so CircleCI needs no Pages permission and no API token - a deploy key
# with write access is enough.
#
# The commit is assembled with plumbing rather than by checking the branch out and copying
# files into it. The published branch holds generated output that shares no history with
# main, so a worktree buys nothing and costs a surprise: FETCH_HEAD is per-worktree, so it
# does not resolve inside a linked one. Writing a tree from a throwaway index sidesteps
# that, never touches the real index, and needs no local branch or cleanup beyond a
# temporary file.
#
# Nothing is pushed when the built site is byte-for-byte what is already published. That
# guard is load-bearing rather than tidy. GitHub builds a branch-model Pages site with its
# own internal Actions workflow, and on a private repository those builds draw on the org's
# Actions minutes - the same constrained resource that blocks the Actions model here. A
# no-op push would spend them for nothing, and would add a commit to the branch on every
# unrelated change to main.
#
# Optional environment:
#
#   SKIPPER_PAGES_REMOTE   push target. Default `origin`. CircleCI checks out over SSH, so
#                          the default already carries the deploy key added to the job.
#   SKIPPER_PAGES_BRANCH   branch to publish to. Default `gh-pages`. Must match
#                          Settings > Pages > Branch.
#
set -euo pipefail

SITE_DIR="${1:-web/dist}"
REMOTE="${SKIPPER_PAGES_REMOTE:-origin}"
BRANCH="${SKIPPER_PAGES_BRANCH:-gh-pages}"

die() { printf 'publish-web: %s\n' "$1" >&2; exit 1; }

[ -d "$SITE_DIR" ] || die "no such directory: $SITE_DIR (run the site build first)"
[ -f "$SITE_DIR/index.html" ] || die "$SITE_DIR has no index.html; the build did not produce a site"

# Astro emits its hashed bundles into `_astro/`, and Pages runs Jekyll over a branch-model
# site by default. Jekyll excludes every path beginning with an underscore, which would
# strip the whole bundle directory and leave the site with no CSS or JS - a fully rendered
# page with nothing styling it, which is easy to misread as a broken build. This marker
# turns Jekyll off. It is inert under the Actions model, which never runs Jekyll, so it
# stays correct if the publishing model ever changes.
touch "$SITE_DIR/.nojekyll"

# Absolute, because the tree is written from inside SITE_DIR where a relative path would
# no longer point at the repository.
GIT_DIR_ABS="$(git rev-parse --absolute-git-dir)"
SOURCE_SHA="$(git rev-parse --short HEAD)"

# What is published right now, if the branch exists at all. Shallow: only the tip's tree is
# read, and the branch's history is generated output that nothing here needs.
PUBLISHED=""
if git ls-remote --exit-code --heads "$REMOTE" "$BRANCH" >/dev/null 2>&1; then
  git fetch --quiet --depth 1 "$REMOTE" "$BRANCH"
  PUBLISHED="$(git rev-parse FETCH_HEAD)"
fi

# A path inside a temporary directory, not `mktemp` directly: git requires GIT_INDEX_FILE
# to be either absent or a well-formed index, and the empty file mktemp leaves behind is
# neither ("index file smaller than expected").
TMP_DIR="$(mktemp -d)"
TMP_INDEX="$TMP_DIR/index"
trap 'rm -rf "$TMP_DIR"' EXIT

# Snapshot the built site as a git tree. `--force` because the site lives at web/dist,
# which web/.gitignore excludes by design - the ignore rule is about keeping build output
# out of main, and says nothing about what belongs on the published branch.
(
  cd "$SITE_DIR"
  GIT_DIR="$GIT_DIR_ABS" GIT_WORK_TREE="$PWD" GIT_INDEX_FILE="$TMP_INDEX" \
    git add --all --force .
)
TREE="$(GIT_DIR="$GIT_DIR_ABS" GIT_INDEX_FILE="$TMP_INDEX" git write-tree)"

if [ -n "$PUBLISHED" ] && [ "$TREE" = "$(git rev-parse "$PUBLISHED^{tree}")" ]; then
  printf 'publish-web: %s already matches the built site; nothing to publish\n' "$BRANCH"
  exit 0
fi

# git needs an identity to write a commit, and CI images rarely have one configured.
export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-skipper-ci}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-skipper-ci@users.noreply.github.com}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-$GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-$GIT_AUTHOR_EMAIL}"

# Naming the source commit is what makes the published branch auditable: its own history is
# a flat sequence of generated snapshots, so the useful question is always "which commit on
# main produced this?".
MESSAGE="docs: publish web/ from $SOURCE_SHA"

if [ -n "$PUBLISHED" ]; then
  COMMIT="$(git commit-tree "$TREE" -p "$PUBLISHED" -m "$MESSAGE")"
else
  printf 'publish-web: %s does not exist on %s; creating it\n' "$BRANCH" "$REMOTE"
  COMMIT="$(git commit-tree "$TREE" -m "$MESSAGE")"
fi

git push "$REMOTE" "$COMMIT:refs/heads/$BRANCH"
printf 'publish-web: published %s to %s/%s as %s\n' "$SOURCE_SHA" "$REMOTE" "$BRANCH" "${COMMIT:0:12}"
