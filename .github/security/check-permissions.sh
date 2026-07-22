#!/usr/bin/env bash
# Permission-drift gate.
# Fails if a manifest declares a uses-permission not present in the allowlist.
# Security: this app decides when a car gets written to. A permission added without
# review silently widens what an automation rule can reach.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALLOWLIST="$ROOT/.github/security/permission-allowlist.txt"

# Allowed permissions (comments/blank lines stripped)
mapfile -t allowed < <(grep -vE '^\s*(#|$)' "$ALLOWLIST" | tr -d '\r' | sed 's/[[:space:]]*$//')

declare -A allow_set=()
for p in "${allowed[@]}"; do allow_set["$p"]=1; done

fail=0
while IFS= read -r -d '' manifest; do
  while read -r perm; do
    [ -z "$perm" ] && continue
    if [ -z "${allow_set[$perm]:-}" ]; then
      echo "::error file=$manifest::Disallowed permission: $perm (add it to permission-allowlist.txt after security review)"
      fail=1
    fi
  done < <(grep -oE 'android:name="[^"]+"' "$manifest" \
            | sed -E 's/.*"(.*)"/\1/' \
            | grep -E '^(android|com)\.' \
            | grep -iE 'permission')
done < <(find "$ROOT/app/src" -name AndroidManifest.xml -print0)

if [ "$fail" -ne 0 ]; then
  echo "❌ Permission-drift gate: at least one disallowed permission."
  exit 1
fi
echo "✅ Permission-drift gate: all permissions are allowed."
