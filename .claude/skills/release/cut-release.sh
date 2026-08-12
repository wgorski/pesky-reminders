#!/usr/bin/env bash
# Build the release APK and publish a GitHub release for the version currently
# in app/build.gradle.kts. Mechanical tail of the release workflow — the agent
# is expected to have already committed, merged to master, and pushed.
#
# Usage:  cut-release.sh "<short release title>" <notes-file>
#   e.g.  cut-release.sh "Task list and add sheet" /tmp/relnotes.txt
#
# The release is tagged vX.Y.Z and the title becomes "vX.Y.Z — <short title>".
# Refuses to run if that tag/release already exists (version wasn't bumped).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# The SDK is not on the default PATH and shell env does not persist between
# runs, so export it here rather than relying on the caller.
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

TITLE="${1:?release title required (e.g. \"Task list and add sheet\")}"
NOTES_FILE="${2:?notes file required (path to a file containing the release body)}"
[ -f "$NOTES_FILE" ] || { echo "ERROR: notes file not found: $NOTES_FILE" >&2; exit 1; }

# Single source of truth for the version is app/build.gradle.kts.
VERSION="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$VERSION" ] || { echo "ERROR: could not read versionName from app/build.gradle.kts" >&2; exit 1; }
TAG="v${VERSION}"

# Guard: the version must have been bumped this session — never clobber an
# existing release (CLAUDE.md: bump 'versionName' once per branch/session).
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "ERROR: release $TAG already exists. Bump 'versionName' in app/build.gradle.kts first." >&2
  exit 1
fi

# Releases are cut from master; warn loudly if the local checkout isn't there.
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "master" ]; then
  echo "WARNING: on branch '$BRANCH', not 'master'. Releases target master — merge first." >&2
fi

echo ">> Building release APK for $TAG ..."
# -PuseDebugSigning is REQUIRED, not optional. A GitHub release APK exists to be
# sideloaded, and once keystore.properties is present a plain assembleRelease
# signs with the *upload* key instead — which Android refuses to install over an
# existing debug-signed copy (INSTALL_FAILED_UPDATE_INCOMPATIBLE), forcing an
# uninstall that takes the user's task list with it. It would also make the
# "signed with the debug key" line in every release note a lie. The upload key
# belongs to the Play bundle alone: :app:stageReleaseBundle.
./gradlew :app:assembleRelease -PuseDebugSigning

APK="app/build/outputs/apk/release/pesky-reminders-${VERSION}.apk"
[ -f "$APK" ] || { echo "ERROR: expected artifact not found: $APK" >&2; exit 1; }
echo ">> Built $APK ($(du -h "$APK" | cut -f1))"

# Prove the claim the notes make, rather than trusting the flag. The debug
# keystore's certificate is the well-known "Android Debug" CN; an upload-signed
# APK is not.
SIGNER_CN="$("$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$APK" 2>/dev/null \
  | awk -F'CN=' '/Signer #1 certificate DN/{print $2}')"
case "$SIGNER_CN" in
  "Android Debug"*) echo ">> Signed with the debug key — sideloadable. ($SIGNER_CN)" ;;
  "")               echo "WARNING: could not read the signer certificate; check it by hand." >&2 ;;
  *)                echo "ERROR: $APK is signed by '$SIGNER_CN', not the debug key." >&2
                    echo "       It would not install over an existing sideloaded build." >&2
                    exit 1 ;;
esac

echo ">> Creating GitHub release $TAG ..."
gh release create "$TAG" \
  --title "$TAG — $TITLE" \
  --notes-file "$NOTES_FILE" \
  --target master \
  "$APK"
