#!/usr/bin/env bash
# Extracts one version's section from CHANGELOG.md (Keep a Changelog format) for use
# as GitHub release notes.
#
# Usage:
#   release-notes.sh <version>   Section for "## [<version>]" — exact match, leading "v" stripped
#   release-notes.sh --latest    Section for the newest "## [" entry — used by the unstable
#                                 channel, whose build version never matches a heading exactly
set -euo pipefail

changelog="${CHANGELOG_FILE:-CHANGELOG.md}"
[ -f "$changelog" ] || { echo "::error::$changelog not found" >&2; exit 1; }

mode="${1:?usage: release-notes.sh <version>|--latest}"
if [ "$mode" = "--latest" ]; then
  want_latest=1
  version=""
else
  want_latest=0
  version="${mode#v}"
fi

section=$(awk -v ver="$version" -v want_latest="$want_latest" '
  /^## \[/ {
    if (want_latest == "1") {
      if (latest_started && latest_has_content) {
        printf "%s", latest_section
        latest_emitted = 1
        exit
      }
      latest_started = 1
      latest_section = ""
      latest_has_content = 0
      next
    }
    if (found) exit
    prefix = "## [" ver "]"
    if (substr($0, 1, length(prefix)) == prefix) found = 1
    next
  }
  want_latest == "1" && latest_started {
    latest_section = latest_section $0 ORS
    if ($0 !~ /^[[:space:]]*$/) latest_has_content = 1
    next
  }
  found { print }
  END {
    if (want_latest == "1" && !latest_emitted && latest_started && latest_has_content)
      printf "%s", latest_section
  }
' "$changelog")

# Trim leading/trailing blank lines.
section=$(printf '%s\n' "$section" | sed -e '/./,$!d' -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')

if [ -z "$section" ]; then
  echo "::error::No changelog section found for '$mode' in $changelog" >&2
  exit 1
fi

printf '%s\n' "$section"
