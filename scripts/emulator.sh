#!/usr/bin/env bash
#
# Boot the `pesky` AVD headless, with host GPU acceleration, and wait until it
# can take an install. Safe to run at any time: if it is already up it says so
# and exits, so "is the emulator running?" and "start the emulator" are the same
# command.
#
#   scripts/emulator.sh            boot it headless, or confirm it is running
#   scripts/emulator.sh --window   the same, with a window on screen — only when
#                                  someone has asked to watch it
#   scripts/emulator.sh --stop     shut it down
#
# The emulator is detached (nohup) and outlives this script. Its log goes to
# $PESKY_EMULATOR_LOG, default $TMPDIR/pesky-emulator.log.
#
# Straight-line adb calls only, no shell functions around them: a function
# called in a polling loop has exhausted the process table and killed the
# emulator before (see CLAUDE.md).
set -euo pipefail

AVD=pesky
IMAGE_PKG="system-images;android-35;google_apis;arm64-v8a"
BOOT_TIMEOUT="${PESKY_BOOT_TIMEOUT:-240}"
TMP="${TMPDIR:-/tmp}"
LOG="${PESKY_EMULATOR_LOG:-${TMP%/}/pesky-emulator.log}"

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

SERIAL="$(adb devices | awk '/^emulator-[0-9]+\tdevice$/ { print $1; exit }')"

WINDOW_ARGS=(-no-window)
[ "${1:-}" = "--window" ] && WINDOW_ARGS=()

# ---- stop ----------------------------------------------------------------------

if [ "${1:-}" = "--stop" ]; then
    if [ -z "$SERIAL" ]; then
        echo "==> No emulator running"
        exit 0
    fi
    echo "==> Stopping $SERIAL"
    adb -s "$SERIAL" emu kill >/dev/null
    for _ in $(seq 1 30); do
        adb devices | grep -q "^$SERIAL" || { echo "==> Stopped"; exit 0; }
        sleep 1
    done
    echo "ERROR: $SERIAL still listed after 30s" >&2
    exit 1
fi

# ---- already up? ---------------------------------------------------------------

if [ -n "$SERIAL" ] && [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    # A headless emulator cannot grow a window, so asking for one is a restart —
    # which is the caller's call to make, not something to do behind their back.
    if [ "${1:-}" = "--window" ] && pgrep -f "^[^ ]*qemu-system[^ ]* .*-avd $AVD .*-no-window" >/dev/null; then
        echo "ERROR: $SERIAL is running headless. Run --stop first, then --window." >&2
        exit 1
    fi
    echo "==> Already running: $SERIAL"
    exit 0
fi

# ---- boot ----------------------------------------------------------------------

if [ -z "$SERIAL" ]; then
    # The AVD points at this image by path; without it the boot dies with
    # "Cannot find AVD system path" and nothing else to go on.
    if [ ! -f "$ANDROID_HOME/system-images/android-35/google_apis/arm64-v8a/system.img" ]; then
        echo "==> System image missing, installing $IMAGE_PKG (about 1.5 GB)"
        yes | sdkmanager --sdk_root="$ANDROID_HOME" "$IMAGE_PKG" >/dev/null
    fi

    echo "==> Booting $AVD with host GPU (log: $LOG)"
    # -gpu host renders through the Mac's GPU (Metal). Never swiftshader: software
    # rendering is several times slower and changes nothing we test for.
    nohup emulator -avd "$AVD" ${WINDOW_ARGS[@]+"${WINDOW_ARGS[@]}"} -no-audio -no-boot-anim \
        -no-snapshot -gpu host >"$LOG" 2>&1 &
    EMU_PID=$!
    disown "$EMU_PID" 2>/dev/null || true
else
    echo "==> $SERIAL is up but still booting"
    EMU_PID=""
fi

# ---- wait ----------------------------------------------------------------------

DEADLINE=$(( SECONDS + BOOT_TIMEOUT ))
BOOTED=""
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if [ -n "$EMU_PID" ] && ! kill -0 "$EMU_PID" 2>/dev/null; then
        echo "ERROR: the emulator exited during boot. Last lines of $LOG:" >&2
        tail -15 "$LOG" >&2
        exit 1
    fi
    SERIAL="$(adb devices | awk '/^emulator-[0-9]+\tdevice$/ { print $1; exit }')"
    if [ -n "$SERIAL" ] && [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        BOOTED=1
        break
    fi
    sleep 2
done

if [ -z "$BOOTED" ]; then
    echo "ERROR: not booted after ${BOOT_TIMEOUT}s (raise PESKY_BOOT_TIMEOUT?). Log: $LOG" >&2
    exit 1
fi

adb -s "$SERIAL" shell wm dismiss-keyguard

# ---- prove the GPU -------------------------------------------------------------

# Only checkable on a boot we started — an emulator someone else launched has
# its log elsewhere.
if [ -n "$EMU_PID" ]; then
    if grep -qi "swiftshader" "$LOG"; then
        echo "ERROR: the emulator fell back to software rendering (swiftshader). Log: $LOG" >&2
        exit 1
    fi
    RENDERER="$(grep -m1 "Graphics Adapter Android Emulator" "$LOG" | sed 's/.*Graphics Adapter //' || true)"
    echo "==> GPU: ${RENDERER:-see $LOG}"
fi

echo "==> Ready: $SERIAL"
