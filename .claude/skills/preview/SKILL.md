---
name: preview
description: Use when the user wants to try a Pesky Reminders build on a real phone before it goes to Google Play — "build the preview", "give me the preview apk", "serve the preview over lan", "let me sideload this", "I want to test this on my phone first". Builds the parallel-installable preview APK, stages it, and serves the directory over the LAN.
---

# Build and serve the preview APK

## Overview

The `preview` build type is a dress rehearsal for the Play build that can sit on
the phone **next to** the real app: its own launcher entry, its own green icon,
its own task list. You can try a release candidate without risking the list you
actually rely on, and without uninstalling anything.

This skill builds it, stages it in `dist/preview/`, and serves that directory
over the LAN so the phone can download and install it from a browser.

**LAN only.** The server binds `0.0.0.0` so a phone on the same Wi-Fi can reach
it. Do **not** put it behind a tunnel or any public host — see CLAUDE.md. If the
user wants a public URL they will say so explicitly.

## Workflow

One script does all of it, from the repo root:

```bash
.claude/skills/preview/serve-preview.sh
```

It exports the Android SDK env, runs `./gradlew :app:assemblePreview`, copies the
APK into `dist/preview/` under its **version-pinned name only**, starts a static
server on port **9998** (reusing one that is already listening), and prints the
URLs.

Then **report the URLs to the user** — lead with the phone one, since that is
what they asked for. The directory listing is the useful link: they can open it
on the phone and tap the APK.

### Every preview file carries its version — no un-versioned copy

`dist/preview/` holds `pesky-reminders-0.26.0-preview.apk` and nothing shorter.
There is deliberately **no** stable `pesky-reminders-preview.apk`, and the script
deletes one if it finds it.

A "latest" pointer saves a bookmark and costs the one thing a preview exists to
tell you: *which build is on the phone*. Tapping it in the listing — or finding it
in Downloads a day later — you cannot tell 0.26.0 from a stale 0.22.0, and because
the preview is debug-signed and installs in place, the wrong one goes on
silently with no signature error to stop it. The version in the filename is the
only cue that reaches the phone **before** the install.

So: don't reintroduce the short name, don't hand out a URL without a version in
it, and if the user asks for a stable link, give them the **directory listing**
instead — it always shows the newest build, and it shows what it is.

This is a rule about the *preview* only. The release flow in CLAUDE.md still
stages both `dist/pesky-reminders-$V.apk` and a `dist/pesky-reminders.apk`
pointer, because that one is a hand-out link for a build that has been published
under a version people already know.

If the phone refuses the install, it is almost always one of:

- **"App not installed"** after a previous *release*-signed preview — can't
  happen, the preview is always debug-signed. But if they once installed a
  differently-signed preview, Android refuses the signature change and they must
  uninstall "Pesky preview" first. The real app is untouched.
- **Chrome blocking it** — they need "install unknown apps" for the browser.

## What makes it install alongside

Three overrides in the `preview` build type in `app/build.gradle.kts`. All three
matter; drop any one and it stops being a *parallel* install:

| | value | why |
|---|---|---|
| `applicationIdSuffix` | `.preview` | a different package, so Android treats it as a different app — own data, own notification channels, own launcher entry |
| `versionNameSuffix` | `-preview` | `dumpsys` and the artifact name can't be confused with the Play build |
| `signingConfig` | always **debug** | it must sideload, and must be replaceable in place by the next preview |

It is `initWith(release)`, **not** debug: the point is to exercise what actually
ships, so it inherits release's configuration — not `debuggable`, no test
manifest, release resource processing.

**It is not minified, so don't claim a preview pass exercises R8.** Release sets
`isMinifyEnabled = false`, and the preview inherits that along with everything
else. `initWith` is still the right shape — switch minification on for release and
the preview picks it up automatically, which is precisely the change that would
deserve a dress rehearsal — but today there is no R8 in either build, and
reporting a preview as proof of minified behaviour overstates it.

The green icon and the "Pesky preview" label come from `app/src/preview/res`,
which overrides exactly two resources — `ic_launcher_background` and
`app_name`. Nothing in the app's own UI changes; `PeskyColors.Accent` stays
crimson on purpose, so the preview looks and behaves like the real build
everywhere except the launcher.

## Quick reference

| Thing | Value |
|-------|-------|
| Gradle task | `:app:assemblePreview` |
| Build output | `app/build/outputs/apk/preview/pesky-reminders-X.Y.Z-preview.apk` |
| Served directory | `dist/preview/` (gitignored) |
| Served filenames | version-pinned only — never an un-versioned `pesky-reminders-preview.apk` |
| Stable link to hand out | the directory listing, not a file |
| Port | `9998` (release sideload flow owns 9999) |
| Package | `com.wgorski.peskyreminders.preview` |
| Override the port | `PESKY_PREVIEW_PORT=… .claude/skills/preview/serve-preview.sh` |
| Stop the server | `kill $(lsof -nP -tiTCP:9998 -sTCP:LISTEN)` |

## Common mistakes

- **Bumping the version to make a preview.** Don't. The point is to test the
  build you are about to ship, so it must carry that version; the `-preview`
  suffix is what distinguishes the artifact.
- **Staging or linking an un-versioned `pesky-reminders-preview.apk`.** Don't —
  see above. Point the user at the directory listing if they want one stable URL.
- **Serving `dist/` instead of `dist/preview/`.** `dist/` holds every release APK
  ever staged, so the listing is unreadable on a phone — and it collides with the
  port-9999 release flow.
- **Reaching for a tunnel** because the phone can't connect. Check they are on
  the same Wi-Fi first; a tunnel is explicitly not wanted here.
- **Assuming it replaced the real app.** It cannot — different package. If the
  user wants the *real* app updated, that is `assembleRelease -PuseDebugSigning`
  and the port-9999 flow in CLAUDE.md.
- **Using the system `gradle`.** Always the wrapper; the script does this for you.
