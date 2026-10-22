#!/usr/bin/env bash
# ABOUTME: Asserts the release zip built by `sbt packageZip` holds the jar, docs, and demo files.
# ABOUTME: Also checks demos/config.txt in the zip is the tracked placeholder, not a local config.
set -euo pipefail

# Usage: scripts/check-zip.sh [path/to/llm-<version>.zip]
# With no argument, uses the single llm-*.zip in the repo root.

root="$(cd "$(dirname "$0")/.." && pwd)"

if [ $# -ge 1 ]; then
  zip="$1"
else
  shopt -s nullglob
  zips=("$root"/llm-*.zip)
  shopt -u nullglob
  if [ ${#zips[@]} -ne 1 ]; then
    echo "check-zip: expected exactly one llm-*.zip in $root, found ${#zips[@]}" >&2
    exit 1
  fi
  zip="${zips[0]}"
fi

if [ ! -f "$zip" ]; then
  echo "check-zip: zip not found: $zip" >&2
  exit 1
fi

listing="$(unzip -Z1 "$zip")"

# Entries may sit under a top-level folder (e.g. llm/), so match by suffix.
required=(
  llm.jar
  docs/README.md
  demos/config-reference.txt
  demos/config.txt
  demos/color-sharing.nlogox
  demos/social-deduction-game.nlogox
  demos/topology-tournament.nlogox
  demos/provider-sensitivity.nlogox
)

status=0
for entry in "${required[@]}"; do
  if ! grep -Eq "(^|/)${entry//./\\.}\$" <<<"$listing"; then
    echo "check-zip: missing $entry in $zip" >&2
    status=1
  fi
done

if [ $status -ne 0 ]; then
  unzip -l "$zip" >&2
  exit $status
fi

config_entry="$(grep -E '(^|/)demos/config\.txt$' <<<"$listing" | head -1)"
if ! unzip -p "$zip" "$config_entry" | cmp -s - "$root/demos/config-placeholder.txt"; then
  echo "check-zip: $config_entry in $zip differs from demos/config-placeholder.txt" >&2
  exit 1
fi

unzip -l "$zip"
echo "check-zip: OK ($zip)"
