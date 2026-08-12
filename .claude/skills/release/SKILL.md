---
name: release
description: Use when the user wants to ship, cut, or publish a Pesky Reminders release — "create a release", "cut a release", "release this", "publish the APK", "make a GitHub release". Commits and pushes pending work, merges to master if on a branch, builds the release APK, and creates the GitHub release with the APK attached.
---

# Cut a Pesky Reminders release

## Overview

Turns the committed (or pending) work on the current branch into a published
GitHub release: a `vX.Y.Z` tag on `master` with release notes and the
`pesky-reminders-X.Y.Z.apk` artifact attached. Version is whatever
`app/build.gradle.kts` already says — this skill does **not** bump it
(CLAUDE.md: bump `versionName` once per branch/session, which happens during
the work itself).

## Workflow

Do these in order. Steps 1–5 are normal git (you write the messages); step 6
runs the helper script for the mechanical build-and-publish tail.

1. **Check the version.** Read `versionName` from `app/build.gradle.kts` →
   `X.Y.Z`. If it still matches the latest existing release (`gh release list`),
   the version wasn't bumped — stop and bump it (minor for features, patch for
   fixes) before continuing. Bump `versionCode` too. The script also refuses to
   clobber an existing tag.

2. **Commit pending work.** If `git status` is dirty, commit it with a clear
   message (end with the `Co-Authored-By` trailer per the global git rule).
   Nothing to commit is fine — a release can be cut from already-committed work.

3. **Merge to master if needed.** Releases target `master`. If on a feature
   branch:
   ```bash
   git checkout master && git merge --no-ff <branch>
   ```
   This repo usually commits straight to `master`, in which case skip this.

4. **Push.** `git push origin master` (and the branch, if you used one).

5. **Write release notes to a file.** Use a temp file (e.g. `/tmp/relnotes.txt`)
   and pass it via `--notes-file` — **never** inline the body in a `-m`/heredoc,
   because apostrophes in the notes break shell quoting. Match the house style
   of prior releases (`gh release view <last-tag>`):
   - Opens with `Bugfix release.` or `Feature release.`
   - `**Fixed:**` / `**Added:**` paragraphs explaining the user-visible change
   - Footer: ``**APK:** `pesky-reminders-X.Y.Z.apk` is signed with the debug key
     for sideloading. Install with `adb install -r pesky-reminders-X.Y.Z.apk`.``

6. **Build + publish.** Run the helper from the repo root:
   ```bash
   .claude/skills/release/cut-release.sh "<short release title>" /tmp/relnotes.txt
   ```
   It exports the Android SDK env, derives the version/tag from
   `app/build.gradle.kts`, refuses to overwrite an existing release, runs
   `./gradlew :app:assembleRelease -PuseDebugSigning`, **verifies the APK really is
   debug-signed**, and creates the release on `master` with the APK attached. The
   release title becomes `vX.Y.Z — <short title>`.

   **Why the flag and the check.** A GitHub release APK exists to be sideloaded.
   Once `keystore.properties` is present, a plain `assembleRelease` signs with the
   **upload** key, and Android refuses to install that over an existing
   debug-signed copy — `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the uninstall it
   forces takes the user's task list with it. It would also make the "signed with
   the debug key" line in the notes false. The script reads the signer's
   certificate CN back out of the finished APK and aborts if it isn't
   `Android Debug`, so the notes cannot drift from the artifact. The upload key
   belongs to the Play bundle alone (`:app:stageReleaseBundle`).

7. **Report the release URL** that `gh` prints back to the user.

## Quick reference

| Thing | Value |
|-------|-------|
| Version source of truth | `versionName` in `app/build.gradle.kts` |
| Tag format | `vX.Y.Z` |
| Release title | `vX.Y.Z — <short title>` |
| APK artifact | `app/build/outputs/apk/release/pesky-reminders-X.Y.Z.apk` (auto-named) |
| Release target | `master` |
| Notes | temp file + `--notes-file` (apostrophes break inline `-m`) |

## Common mistakes

- **Inlining notes with `-m "..."`** — an apostrophe (e.g. "doesn't") breaks the
  shell quoting. Always write to a file and use `--notes-file`.
- **Forgetting to bump the version** — the script aborts if the tag exists, but
  check first so you don't get halfway through. Bump `versionCode` alongside
  `versionName`, or Android will refuse to install over the previous build.
- **Cutting from a branch** — the release is `--target master`, so unmerged
  branch work won't be in it. Merge and push to `master` first.
- **Forgetting the SDK env** — the script exports it, but if you build manually
  first, `ANDROID_HOME` and the platform-tools PATH must be set (see CLAUDE.md).
- **Using the system `gradle`** — always the wrapper (`./gradlew`). The system
  Gradle is too new to configure this project's AGP.
