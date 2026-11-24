#!/usr/bin/env bash
# Fails when app code points an update or release check at upstream Metrolist's GitHub releases
# instead of Turtle's (TurtleRelease, HOOKS.md #64 and #65). Matches on content, not line numbers, so
# upstream merges that move lines do not break it.
# Usage: check-release-endpoints.sh   (from the repository root)
set -euo pipefail
pattern='api\.github\.com/repos/MetrolistGroup|github\.com/MetrolistGroup/Metrolist/releases'
# Allowed: iOS only (out of Turtle's scope), and the nightly lookup that runs only after a release
# tagged "nightly" was downloaded from Turtle's repository, which never publishes one.
allowed='LATEST_RELEASE_URL = |NIGHTLY_RELEASE_URL = '
hits=$(grep -rnE "$pattern" androidApp/src desktopApp/src shared/src --include='*.kt' || true)
bad=$(echo "$hits" | grep -vE "$allowed" | grep -v '^$' || true)
if [[ -n "$bad" ]]; then
  echo "::error::Release endpoints still point at upstream Metrolist; use app.turtleplayer.bridge.TurtleRelease:"
  echo "$bad"
  exit 1
fi
echo "Release endpoints point at Turtle's releases."
