#!/usr/bin/env bash
# Builds TIMELINE.md: one row per released version, with its date and commit, read from the git history of
# app/build.gradle.kts (the version is raised there). Run it from the repo root after raising versionName:
#   bash tools/timeline.sh
# Only the part between the two marker lines is rewritten; the rest of TIMELINE.md (milestones, notes) is yours.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

GRADLE=app/build.gradle.kts
OUT=TIMELINE.md
START='<!-- timeline:start -->'
END='<!-- timeline:end -->'

ver_of() { grep -m1 'versionName' | sed -E 's/.*"([^"]+)".*/\1/'; }

rows=$(mktemp)
prev=""
while read -r c; do
  v=$(git show "$c:$GRADLE" 2>/dev/null | ver_of || true)
  [ -n "$v" ] || continue
  if [ "$v" != "$prev" ]; then
    d=$(git show -s --format=%ad --date=short "$c")
    s=$(git show -s --format=%s "$c" | sed 's/|/\\|/g')
    echo "| $v | $d | \`$(git rev-parse --short "$c")\` | $s |" >> "$rows"
    prev=$v
  fi
done < <(git log --reverse --format=%H -- "$GRADLE")

# a version raised in the working tree but not committed yet
now=$(ver_of < "$GRADLE" || true)
if [ -n "$now" ] && [ "$now" != "$prev" ]; then
  echo "| $now | $(date +%F) | _not committed yet_ | |" >> "$rows"
fi

table=$(mktemp)
{
  echo "$START"
  echo "| Version | Date | Commit | Commit message |"
  echo "|---|---|---|---|"
  tac "$rows"          # newest first
  echo "$END"
} > "$table"

if [ ! -f "$OUT" ]; then
  {
    echo "# Timeline"
    echo
    echo "When each version of Expressive Keyboard Pro was released. The table is made by \`tools/timeline.sh\` from the git history,"
    echo "so run \`bash tools/timeline.sh\` after raising \`versionName\` in \`app/build.gradle.kts\`. What changed in each version:"
    echo "[\`CHANGELOG.md\`](CHANGELOG.md) and \`release-notes/\`."
    echo
    cat "$table"
    echo
    echo "## Milestones"
    echo
    echo "- **22 Nov 2026**: GLSL layers were first committed to this fork (commit \`544021f\`, 21:12 UTC+8)."
  } > "$OUT"
  echo "Created $OUT"
elif grep -qF "$START" "$OUT" && grep -qF "$END" "$OUT"; then
  tmp=$(mktemp)
  awk -v s="$START" -v e="$END" -v tf="$table" '
    $0==s { while ((getline line < tf) > 0) print line; skip=1; next }
    $0==e { skip=0; next }
    !skip { print }
  ' "$OUT" > "$tmp"
  cat "$tmp" > "$OUT"
  echo "Updated the table in $OUT"
else
  echo "$OUT exists but has no '$START' / '$END' lines. Put those two lines where the table should go and run this again." >&2
  exit 1
fi
