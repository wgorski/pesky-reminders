#!/usr/bin/env bash
# PostToolUse hook: run the deterministic JVM test suite after a code change.
#
# Reads the hook payload on stdin, ignores anything that is not project source,
# and runs ./gradlew :app:testDebugUnitTest. Exit 2 reports the failure back to
# Claude; exit 0 stays silent.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

FILE="$(jq -r '.tool_response.filePath // .tool_input.file_path // empty' 2>/dev/null)"
[ -n "$FILE" ] || exit 0

# Only source that can break the build or the tests.
case "$FILE" in
  *.kt | *.kts | *AndroidManifest.xml) ;;
  *) exit 0 ;;
esac

# Only files inside this repo.
case "$FILE" in
  "$ROOT"/*) ;;
  *) exit 0 ;;
esac

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

cd "$ROOT" || exit 0

# --console=plain (but not --quiet) so the per-test "… FAILED" lines survive.
if OUT="$(./gradlew --console=plain :app:testDebugUnitTest 2>&1)"; then
  exit 0
fi

{
  echo "Deterministic tests failed after editing ${FILE#"$ROOT"/}."
  echo
  echo "$OUT" | grep -E "^e: |> [A-Za-z_]+ FAILED| FAILED$|tests? completed" | head -30
  echo
  echo "Run './gradlew :app:testDebugUnitTest' for the full report."
} >&2
exit 2
