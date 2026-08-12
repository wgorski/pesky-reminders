#!/usr/bin/env bash
#
# Build the preview APK and serve it on the LAN so it can be sideloaded.
#
# LAN ONLY. This binds 0.0.0.0 so a phone on the same Wi-Fi can reach it, and
# that is the whole intent — do not put it behind a tunnel or any public host.
# See CLAUDE.md, "expose the apk locally".
set -euo pipefail

PORT="${PESKY_PREVIEW_PORT:-9998}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

SERVE_DIR="$ROOT/dist/preview"

# ---- build -----------------------------------------------------------------

echo "==> Building the preview APK"
./gradlew :app:assemblePreview -q

BUILT="$(ls -t app/build/outputs/apk/preview/pesky-reminders-*-preview.apk 2>/dev/null | head -1 || true)"
if [ -z "$BUILT" ]; then
    echo "ERROR: assemblePreview produced no APK in app/build/outputs/apk/preview/" >&2
    exit 1
fi

VERSION_FILE="$(basename "$BUILT")"

# ---- stage -----------------------------------------------------------------
#
# Its own directory, not dist/ itself: the served page is meant to be a short
# list you can pick from on a phone, and dist/ holds every release ever staged.
# It also keeps this off port 9999, which the release sideload flow uses.
mkdir -p "$SERVE_DIR"
cp "$BUILT" "$SERVE_DIR/$VERSION_FILE"

# NO un-versioned "latest" copy. Every preview file carries its version, always.
# A stable pesky-reminders-preview.apk saves a bookmark and costs you the one
# thing a preview exists to tell you: which build is on the phone. Tapping it in
# the listing, or finding it in Downloads afterwards, you cannot tell 0.26.0 from
# a stale 0.22.0 — and since the preview is debug-signed and installs in place,
# the wrong one goes on silently. The version-pinned name makes that visible on
# the phone, before the install. (The release flow in CLAUDE.md still stages a
# dist/pesky-reminders.apk pointer; that is a hand-out link, a different job.)
rm -f "$SERVE_DIR/pesky-reminders-preview.apk"

echo "==> Staged in dist/preview/"
ls -1sh "$SERVE_DIR" | sed 's/^/    /'

# ---- serve -----------------------------------------------------------------

already_serving() {
    # Anything listening on the port at all. If it is not ours the user needs to
    # know rather than have us silently fail to bind.
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
}

if already_serving; then
    OWNER="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -Fc 2>/dev/null | grep '^c' | head -1 | cut -c2-)"
    echo "==> Port $PORT already has a listener ($OWNER) — reusing it."
    echo "    The APK above was refreshed in place, so a reload serves the new build."
else
    echo "==> Starting a static server on port $PORT"
    ( cd "$SERVE_DIR" && nohup python3 -m http.server "$PORT" --bind 0.0.0.0 \
        > /tmp/pesky-preview-server.log 2>&1 & )
    sleep 1
    if ! already_serving; then
        echo "ERROR: server failed to start. See /tmp/pesky-preview-server.log" >&2
        exit 1
    fi
fi

# ---- report ----------------------------------------------------------------

lan_ip() {
    for iface in en0 en1 en2; do
        ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
        [ -n "$ip" ] && { echo "$ip"; return; }
    done
    # Fall back to whichever interface the default route uses.
    iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
    [ -n "$iface" ] && ipconfig getifaddr "$iface" 2>/dev/null || true
}

IP="$(lan_ip)"

echo
echo "Serving $SERVE_DIR on port $PORT — LAN only, no tunnel."
echo
echo "  Directory listing"
if [ -n "$IP" ]; then
    echo "    phone on the same Wi-Fi:  http://$IP:$PORT/"
else
    echo "    phone on the same Wi-Fi:  (no LAN address found — is Wi-Fi on?)"
fi
echo "    this machine:             http://localhost:$PORT/"
echo "    android emulator:         http://10.0.2.2:$PORT/"
echo
echo "  Straight at the APK"
if [ -n "$IP" ]; then
    echo "    http://$IP:$PORT/$VERSION_FILE"
fi
echo "    http://localhost:$PORT/$VERSION_FILE"
echo
echo "Stop it with:  kill \$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN)"
