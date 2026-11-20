#!/usr/bin/env bash
# Fails when a change edits upstream (non-turtle) paths without updating turtle/HOOKS.md.
# Usage: check-hooks.sh <base-ref>   (e.g. origin/main)
# Upstream merges (sync/upstream-* branches) are exempt: they bring upstream's own changes.
set -euo pipefail
base="${1:-origin/main}"
branch="${GITHUB_HEAD_REF:-$(git rev-parse --abbrev-ref HEAD)}"
if [[ "$branch" == sync/upstream-* ]]; then
  echo "Upstream sync branch; hook ledger check skipped."
  exit 0
fi
changed=$(git diff --name-only "$(git merge-base "$base" HEAD)" HEAD)
upstream_changes=$(echo "$changed" | grep -vE '^(turtle/|\.github/workflows/turtle-|$)' || true)
if [[ -z "$upstream_changes" ]]; then
  echo "No upstream files changed."
  exit 0
fi
if echo "$changed" | grep -qx 'turtle/HOOKS.md'; then
  echo "Upstream files changed and turtle/HOOKS.md updated:"
  echo "$upstream_changes"
  exit 0
fi
echo "::error::These upstream files changed but turtle/HOOKS.md was not updated:"
echo "$upstream_changes"
exit 1
