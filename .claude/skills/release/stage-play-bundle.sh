#!/usr/bin/env bash
#
# Build the Play App Bundle and run every gate that can be checked locally.
#
# Play's feedback loop is slow and some of its mistakes cost a versionCode or two
# weeks of closed testing, so nothing here should ever be done by hand again.
# Every check either PASSes, WARNs (known-benign, explained) or FAILs — and any
# FAIL exits non-zero, so this is safe to put in front of an upload.
#
# Usage:
#   .claude/skills/release/stage-play-bundle.sh              # build + all offline gates
#   .claude/skills/release/stage-play-bundle.sh --install     # ALSO install on a device (DESTRUCTIVE, see below)
#
# --install is opt-in because it is destructive: the bundle is upload-signed and
# a sideloaded build is debug-signed, so Android refuses the key change and the
# app must be uninstalled first — which takes the task list with it. That is
# exactly the trap the two-channels rule in CLAUDE.md exists to describe, and it
# is not something to trigger as a side effect of a verification run.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"

DO_INSTALL=0
[ "${1:-}" = "--install" ] && DO_INSTALL=1

# Play's floor for new apps and updates. Kept here as the value the build is
# checked against; the Console shows the live requirement, so if Play starts
# asking for more, raise this and targetSdk together.
MIN_TARGET_SDK=36

PASS=0; WARN=0; FAIL=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS+1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; WARN=$((WARN+1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not on PATH" >&2; exit 1; }
}
need bundletool
need keytool

# ---- version --------------------------------------------------------------

VERSION="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"
VERSION_CODE="$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | awk '{print $3}')"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] || {
    echo "ERROR: could not read versionName/versionCode from app/build.gradle.kts" >&2; exit 1; }

AAB="app/build/outputs/bundle/play/pesky-reminders-${VERSION}.aab"

echo "Play preflight — pesky-reminders $VERSION (versionCode $VERSION_CODE)"

# ---- build ----------------------------------------------------------------

section "Building the bundle"
# NO -PuseDebugSigning here, ever. This artifact must carry the upload key; the
# debug key belongs to the sideload channel (cut-release.sh).
./gradlew :app:stageReleaseBundle -q
[ -f "$AAB" ] || { echo "ERROR: expected $AAB" >&2; exit 1; }
echo "  $AAB ($(du -h "$AAB" | cut -f1))"

# ---- signing --------------------------------------------------------------
#
# The gate that gets people rejected, and the one where a wrong answer is
# invisible in the file listing.
section "Signing"
SIGNER="$(keytool -printcert -jarfile "$AAB" 2>/dev/null | awk -F'Owner: ' '/Owner:/{print $2; exit}')"
case "$SIGNER" in
    "")            fail "no signature found on the bundle — Play rejects unsigned uploads" ;;
    *"CN=Android Debug"*)
                   fail "bundle is DEBUG-signed ($SIGNER). Play rejects this. Is keystore.properties present?" ;;
    *)             pass "upload-signed: $SIGNER" ;;
esac

# The certificate has to outlive Play's 22 Oct 2033 floor.
CERT_UNTIL="$(keytool -printcert -jarfile "$AAB" 2>/dev/null | awk -F'until: ' '/Valid from:/{print $2; exit}')"
if [ -n "$CERT_UNTIL" ]; then
    UNTIL_EPOCH="$(date -j -f "%a %b %d %T %Z %Y" "$CERT_UNTIL" +%s 2>/dev/null || echo 0)"
    FLOOR_EPOCH="$(date -j -f "%Y-%m-%d" "2033-10-22" +%s 2>/dev/null || echo 0)"
    if [ "$UNTIL_EPOCH" -gt 0 ] && [ "$FLOOR_EPOCH" -gt 0 ]; then
        if [ "$UNTIL_EPOCH" -gt "$FLOOR_EPOCH" ]; then
            pass "certificate valid past 22 Oct 2033 (until $CERT_UNTIL)"
        else
            fail "certificate expires $CERT_UNTIL — Play requires validity past 22 Oct 2033"
        fi
    else
        warn "could not parse the certificate expiry ($CERT_UNTIL) — check it by hand"
    fi
fi

# The sibling APK is the same version signed with the *other* key. Naming it is
# the point: both sit in one tree and handing over the wrong one burns a slot.
SIBLING_APK="app/build/outputs/apk/release/pesky-reminders-${VERSION}.apk"
if [ -f "$SIBLING_APK" ]; then
    APK_DN="$("$BUILD_TOOLS"/apksigner verify --print-certs "$SIBLING_APK" 2>/dev/null \
        | awk -F'DN: ' '/Signer #1 certificate DN/{print $2; exit}')"
    case "$APK_DN" in
        *"CN=Android Debug"*) pass "sibling APK is debug-signed, as the sideload channel needs" ;;
        "")                   warn "could not read the sibling APK's signer" ;;
        *) fail "$SIBLING_APK is signed by '$APK_DN', not the debug key — it will not install over a sideloaded build" ;;
    esac
fi

# ---- the manifest inside the bundle ---------------------------------------
#
# grep cannot read it: the AAB manifest is protobuf. bundletool can.
section "Bundle manifest"
MANIFEST="$(bundletool dump manifest --bundle="$AAB" 2>/dev/null)"

manifest_attr() { printf '%s' "$MANIFEST" | grep -oE "$1=\"[^\"]+\"" | head -1 | cut -d'"' -f2; }

M_VERSION="$(manifest_attr 'android:versionName')"
M_CODE="$(manifest_attr 'android:versionCode')"
M_PACKAGE="$(manifest_attr 'package')"
M_TARGET="$(manifest_attr 'android:targetSdkVersion')"
M_MIN="$(manifest_attr 'android:minSdkVersion')"

[ "$M_VERSION" = "$VERSION" ] \
    && pass "versionName $M_VERSION matches build.gradle.kts" \
    || fail "bundle says versionName $M_VERSION, build.gradle.kts says $VERSION — stale artifact?"

[ "$M_CODE" = "$VERSION_CODE" ] \
    && pass "versionCode $M_CODE matches build.gradle.kts" \
    || fail "bundle says versionCode $M_CODE, build.gradle.kts says $VERSION_CODE — stale artifact?"

[ "$M_PACKAGE" = "com.wgorski.peskyreminders" ] \
    && pass "package $M_PACKAGE (permanent — a change means a new listing)" \
    || fail "package is $M_PACKAGE, expected com.wgorski.peskyreminders"

if [ -n "$M_TARGET" ] && [ "$M_TARGET" -ge "$MIN_TARGET_SDK" ]; then
    pass "targetSdk $M_TARGET (Play floor $MIN_TARGET_SDK)"
else
    fail "targetSdk $M_TARGET is below Play's floor of $MIN_TARGET_SDK"
fi
pass "minSdk $M_MIN — unchanged by the target, so Android 8 devices still install"

# ---- permissions ----------------------------------------------------------
#
# Two claims are pinned here because both are made *outside* the code and would
# otherwise rot silently: the Data safety form's "no data collected", and the
# API 31-32 exact-alarm gap that no device on either side of it can catch.
section "Permissions"
perm_present() { printf '%s' "$MANIFEST" | grep -q "android.permission.$1"; }

if perm_present INTERNET; then
    fail "INTERNET is declared — this breaks the Data safety answer of 'no data collected'"
else
    pass "no INTERNET permission — nothing can leave the device, as Data safety claims"
fi

perm_present USE_EXACT_ALARM \
    && pass "USE_EXACT_ALARM present (restricted — needs the declaration in LISTING.md)" \
    || fail "USE_EXACT_ALARM missing — exact alarms will not work on API 33+"

if printf '%s' "$MANIFEST" | grep -q 'android:maxSdkVersion="32" android:name="android.permission.SCHEDULE_EXACT_ALARM"'; then
    pass "SCHEDULE_EXACT_ALARM capped at API 32 — closes the 31-32 gap without applying later"
else
    fail "SCHEDULE_EXACT_ALARM is missing or uncapped; setAlarmClock throws on API 31-32 only"
fi

# ---- the two warnings Play will show --------------------------------------
#
# Reported here so nobody chases the unfixable one. See the skill for why.
section "Warnings Play will raise"
if grep -q 'isMinifyEnabled = true' app/build.gradle.kts; then
    pass "minification on — the AAB carries its own mapping file, nothing to upload"
else
    warn "'No deobfuscation file': isMinifyEnabled = false. Optional; a good second release."
fi

SO_COUNT="$(unzip -l "$AAB" | grep -c '\.so$' || true)"
if [ "$SO_COUNT" -gt 0 ]; then
    SO_BYTES="$(unzip -l "$AAB" | awk '/\.so$/{s+=$1} END{print s+0}')"
    NON_ANDROIDX="$(unzip -l "$AAB" | grep '\.so$' | grep -vc 'libandroidx\.' || true)"
    if [ "$NON_ANDROIDX" -eq 0 ]; then
        warn "'debug symbols not uploaded': $SO_COUNT stripped AndroidX .so ($SO_BYTES bytes). Not actionable, recurs forever."
    else
        fail "$NON_ANDROIDX native library(ies) are not AndroidX prebuilts — check whether symbols are uploadable"
    fi
fi

# ---- listing assets -------------------------------------------------------
#
# Dimensions and alpha, read out of the PNG headers, because Play rejects an
# alpha channel on the icon and the feature graphic and says so only on upload.
section "Listing assets"
png_info() {
    python3 - "$1" <<'PY'
import struct, sys, zlib
p = sys.argv[1]
d = open(p, 'rb').read()
if d[:8] != b'\x89PNG\r\n\x1a\n':
    print("notpng"); raise SystemExit
w, h = struct.unpack('>II', d[16:24])
colour = d[25]
# 4 = grey+alpha, 6 = RGBA. A tRNS chunk is alpha on any other type.
alpha = colour in (4, 6) or b'tRNS' in d
print(f"{w} {h} {'alpha' if alpha else 'noalpha'}")
PY
}

check_png() { # path expected_w expected_h allow_alpha
    local path="$1" ew="$2" eh="$3" allow="$4"
    if [ ! -f "$path" ]; then fail "$path missing"; return; fi
    read -r w h a <<<"$(png_info "$path")"
    if [ "$w" != "$ew" ] || [ "$h" != "$eh" ]; then
        fail "$(basename "$path") is ${w}x${h}, Play wants ${ew}x${eh}"
    elif [ "$a" = "alpha" ] && [ "$allow" != "yes" ]; then
        fail "$(basename "$path") has an alpha channel; Play rejects it"
    else
        pass "$(basename "$path") ${w}x${h}, no alpha"
    fi
}

check_png docs/play/icon-512.png 512 512 no
check_png docs/play/feature-graphic-1024x500.png 1024 500 no

SHOTS=$(find docs/play/screenshots -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
if [ "$SHOTS" -ge 4 ]; then
    BAD_RATIO=0
    for s in docs/play/screenshots/*.png; do
        read -r w h _ <<<"$(png_info "$s")"
        # 9:16 or 16:9, within a pixel of rounding.
        R=$(python3 -c "print(round($w/$h, 4))")
        python3 -c "import sys; sys.exit(0 if abs($R-0.5625)<0.01 or abs($R-1.7778)<0.01 else 1)" \
            || { fail "$(basename "$s") is ${w}x${h} (ratio $R) — Play wants 9:16 or 16:9"; BAD_RATIO=1; }
    done
    [ "$BAD_RATIO" -eq 0 ] && pass "$SHOTS screenshots, all 9:16 or 16:9 (4+ keeps listing-feature eligibility)"
else
    fail "only $SHOTS screenshots; 4+ keeps listing-feature eligibility (Play's minimum is 2)"
fi

# ---- listing copy ---------------------------------------------------------

section "Listing copy"
POLICY_URL="$(grep -oE 'https://[^ |]+' docs/play/LISTING.md | grep -i privacy | head -1)"
if [ -z "$POLICY_URL" ]; then
    fail "no privacy policy URL found in docs/play/LISTING.md"
else
    CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$POLICY_URL" || echo 000)"
    case "$CODE" in
        200) pass "privacy policy live: $POLICY_URL" ;;
        000) warn "could not reach $POLICY_URL (offline?) — it must be publicly reachable" ;;
        *)   fail "privacy policy returned HTTP $CODE: $POLICY_URL (a dead URL is grounds for removal)" ;;
    esac
fi

# "What's new" must exist for THIS version and fit the 500-character field.
NOTES="$(python3 - "$VERSION" <<'PY'
import re, sys
v = sys.argv[1]
t = open('docs/play/release-notes.md').read()
blocks = re.findall(rf'^## {re.escape(v)} — ([^\n]+)\n(.*?)(?=^## |\Z)', t, re.S | re.M)
if not blocks:
    print("MISSING"); raise SystemExit
for lang, body in blocks:
    fences = re.findall(r'```\n(.*?)```', body, re.S)
    if not fences:
        print(f"NOBODY {lang}"); continue
    print(f"OK {lang.split()[0]} {len(fences[0].strip())}")
PY
)"
if [ "$NOTES" = "MISSING" ]; then
    fail "docs/play/release-notes.md has no '## $VERSION' section"
else
    while read -r status lang count; do
        case "$status" in
            OK) if [ "$count" -le 500 ]; then
                    pass "release notes $lang: $count/500 characters"
                else
                    fail "release notes $lang: $count characters, the field caps at 500"
                fi ;;
            NOBODY) fail "release notes $lang: section has no fenced block" ;;
        esac
    done <<<"$NOTES"
fi

# ---- does it actually install? -------------------------------------------

section "Installability"
if [ "$DO_INSTALL" -eq 1 ]; then
    if ! adb get-state >/dev/null 2>&1; then
        fail "--install given but no device is connected"
    elif [ ! -f keystore.properties ]; then
        fail "--install needs keystore.properties to sign the generated APKs"
    else
        echo "  --install given: uninstalling first (a debug-signed build cannot be replaced by an"
        echo "  upload-signed one, and the uninstall takes the task list with it)."
        set -a; . ./keystore.properties; set +a
        OUT="$(mktemp -d)/pesky-${VERSION}.apks"
        adb uninstall com.wgorski.peskyreminders >/dev/null 2>&1 || true
        bundletool build-apks --bundle="$AAB" --output="$OUT" --overwrite --connected-device \
            --ks="$storeFile" --ks-key-alias="$keyAlias" \
            --ks-pass=pass:"$storePassword" --key-pass=pass:"$keyPassword" >/dev/null
        bundletool install-apks --apks="$OUT" >/dev/null
        GOT_NAME="$(adb shell dumpsys package com.wgorski.peskyreminders | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')"
        GOT_CODE="$(adb shell dumpsys package com.wgorski.peskyreminders | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)"
        if [ "$GOT_NAME" = "$VERSION" ] && [ "$GOT_CODE" = "$VERSION_CODE" ]; then
            pass "split APKs generated from the bundle installed: $GOT_NAME ($GOT_CODE)"
        else
            fail "installed build reports $GOT_NAME ($GOT_CODE), expected $VERSION ($VERSION_CODE)"
        fi
    fi
else
    warn "install check skipped (destructive — pass --install to run it against a device)"
fi

# ---- git ------------------------------------------------------------------

section "Repository state"
if [ -n "$(git status --porcelain)" ]; then
    warn "working tree is dirty — the bundle may not match any commit"
else
    pass "working tree clean at $(git rev-parse --short HEAD)"
fi
# Check the remote as well as locally: `gh release create` makes the tag
# server-side, so a machine that has not fetched since would report it missing.
if git rev-parse "v${VERSION}" >/dev/null 2>&1; then
    pass "tag v${VERSION} exists locally"
elif git ls-remote --tags origin "refs/tags/v${VERSION}" 2>/dev/null | grep -q .; then
    pass "tag v${VERSION} exists on origin (created by the GitHub release; not fetched locally)"
else
    warn "no v${VERSION} tag here or on origin — cut the GitHub release too (cut-release.sh)"
fi

# ---- verdict --------------------------------------------------------------

printf '\n\033[1mUpload this file\033[0m\n  %s\n' "$AAB"
if [ -f "$SIBLING_APK" ]; then
    printf '\033[1mNot this one\033[0m (same version, debug key, for sideloading)\n  %s\n' "$SIBLING_APK"
fi

printf '\n%d passed, %d warned, %d failed\n' "$PASS" "$WARN" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
    echo "NOT ready to upload." >&2
    exit 1
fi
echo "Offline gates clear. What is left is Console work and, for this account, the"
echo "closed-test clock — see the Google Play section of CLAUDE.md."
