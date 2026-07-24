# Pesky Reminders POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android POC proving a scheduled reminder can appear as a notification the user cannot swipe away — only its own Snooze (+5 min) and Done actions can clear it.

**Architecture:** `AlarmManager.setAlarmClock()` fires a `BroadcastReceiver` at the scheduled time. The receiver posts an ongoing (`setOngoing(true)`) notification whose `deleteIntent` re-posts it if ever dismissed — so only the Snooze/Done action buttons (also routed through the receiver) can remove it. A single Compose screen schedules the reminder. No database, no foreground service.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), AndroidX, `AlarmManager`, `NotificationCompat`. Build: Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle wrapper 8.11.1. Verification: JVM unit tests (`testDebugUnitTest`) + instrumented tests on a local emulator (`connectedDebugAndroidTest`).

## Global Constraints

- Package / namespace: `com.peskyreminders.poc` — verbatim in every file.
- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`.
- Java/JVM target 17 (Corretto 17 is installed).
- AGP `8.7.3`, Kotlin `2.0.21`, Gradle wrapper `8.11.1`, Compose BOM `2024.10.01`.
- No third-party libraries beyond AndroidX + Compose + AndroidX Test.
- Snooze interval is exactly 5 minutes (`5 * 60 * 1000` ms). Do not shorten it in product code.
- Un-dismissability mechanism is `setOngoing(true)` + a `deleteIntent` that re-posts. No foreground service, no full-screen intent. **On API 35 the ongoing flag does NOT block an individual swipe** (Android 14+ change); it blocks "clear all" and swipe-while-locked. The delete-intent re-post is what defeats a swipe. `NotificationManager.cancel()` (used by Snooze/Done) does not fire the delete-intent, so those do not re-post.
- **Shell env does not persist between Bash calls, and `adb`/`emulator`/`sdkmanager` are not on the default PATH.** Every command block that uses them MUST begin with this preamble (or use absolute paths):
  ```bash
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```
- One reminder at a time: fixed `NOTIFICATION_ID` and alarm request code; re-scheduling replaces the previous.
- Emulator is Apple-Silicon native: system image `system-images;android-35;google_apis;arm64-v8a`.
- `ANDROID_HOME` for this machine: `/opt/homebrew/share/android-commandlinetools` (set by the Homebrew cask in Task 1). `local.properties` must contain `sdk.dir=/opt/homebrew/share/android-commandlinetools` so Gradle finds the SDK without relying on shell env.

---

### Task 1: Install Android SDK + boot an emulator

**Files:** none in the repo (installs tooling + creates an AVD).

**Interfaces:**
- Produces: a booted emulator reachable via `adb`, and an SDK at `/opt/homebrew/share/android-commandlinetools` with `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, `emulator`, and the arm64 system image installed. Later tasks assume `adb` sees one online device.

- [ ] **Step 1: Install the Android command-line tools**

```bash
brew install --cask android-commandlinetools
```
Expected: cask installs to `/opt/homebrew/share/android-commandlinetools`.

- [ ] **Step 2: Install SDK packages and accept licenses**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "emulator" "system-images;android-35;google_apis;arm64-v8a"
```
Expected: `sdkmanager --sdk_root="$ANDROID_HOME" --list_installed` lists all five packages.

- [ ] **Step 3: Create an AVD**

```bash
echo no | avdmanager --clear-cache create avd -n pesky --force \
  -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
avdmanager list avd
```
`--force` overwrites an AVD of the same name if one already exists (the SDK and
an AVD named `pesky` may already be present on this machine — earlier steps are
then no-ops). Expected: `avdmanager list avd` shows `Name: pesky`.

- [ ] **Step 4: Boot the emulator headless (background) and wait for boot**

```bash
emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect >/tmp/emulator.log 2>&1 &
adb wait-for-device
# poll until fully booted
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell wm dismiss-keyguard   # unlock — ongoing notifications are non-swipeable while locked
```
(Run the `emulator` line via the Bash tool's `run_in_background`.)

- [ ] **Step 5: Verify the device is online and booted**

Run:
```bash
adb devices
adb shell getprop sys.boot_completed
```
Expected: `adb devices` shows `emulator-5554   device`; boot prop prints `1`.

---

### Task 2: Buildable Compose project skeleton that launches on the emulator

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `local.properties` (gitignored)
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/peskyreminders/poc/MainActivity.kt`
- Create: Gradle wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.properties`, jar)

**Interfaces:**
- Produces: `MainActivity` (a `ComponentActivity` in package `com.peskyreminders.poc`) — later tasks target it with a `PendingIntent`. The `:app` module with namespace `com.peskyreminders.poc`, applicationId `com.peskyreminders.poc`.

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PeskyReminders"
include(":app")
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `local.properties` and create the app module dir**

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```
Then create the module directory so the wrapper step can evaluate `include(":app")`:
```bash
mkdir -p app
```

- [ ] **Step 4: Generate the Gradle wrapper pinned to 8.11.1**

The system Gradle is 9.5.1, which refuses to run in a directory with no settings
file and cannot configure AGP 8.7.3. Generate the wrapper only AFTER
`settings.gradle.kts` and the `app/` dir exist, and BEFORE any AGP build scripts
are written (so the wrapper task never triggers configuration of the Android
plugin). All later Gradle commands use `./gradlew` (8.11.1), never system `gradle`.

```bash
cd /Users/ket/dev/ai/pesky-reminders
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```
Expected: creates `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties` pinned to `gradle-8.11.1-bin.zip`.

- [ ] **Step 5: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

- [ ] **Step 6: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peskyreminders.poc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peskyreminders.poc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
```

- [ ] **Step 7: Write `app/src/main/AndroidManifest.xml`**

(Declares both permissions now so later tasks don't re-touch it for perms. The `<receiver>` is added in Task 4.)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

    <application
        android:allowBackup="true"
        android:label="Pesky Reminders"
        android:icon="@android:drawable/ic_popup_reminder"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8: Write minimal `MainActivity.kt`**

```kotlin
package com.peskyreminders.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PlaceholderScreen() } }
    }
}

@Composable
private fun PlaceholderScreen() {
    Column(Modifier.padding(24.dp)) {
        Text("Pesky Reminders", style = MaterialTheme.typography.headlineSmall)
    }
}
```

- [ ] **Step 9: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (First run downloads AGP/Compose — may take minutes.)

- [ ] **Step 10: Install, launch, screenshot**

Run (the export preamble is required — see Global Constraints):
```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.peskyreminders.poc/.MainActivity
sleep 2
adb exec-out screencap -p > /tmp/pr-skeleton.png
```
Expected: install `Success`; `/tmp/pr-skeleton.png` shows a screen with the "Pesky Reminders" heading.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: buildable Compose skeleton launching on emulator"
```

---

### Task 3: ReminderContract — constants + pure trigger-time logic (TDD, JVM)

**Files:**
- Create: `app/src/main/java/com/peskyreminders/poc/ReminderContract.kt`
- Test: `app/src/test/java/com/peskyreminders/poc/ReminderContractTest.kt`

**Interfaces:**
- Produces:
  - `ReminderContract.CHANNEL_ID: String`, `NOTIFICATION_ID: Int = 1001`, `REQUEST_CODE: Int = 2001`
  - `ACTION_FIRE`, `ACTION_SNOOZE`, `ACTION_DONE`, `ACTION_REPOST` (String)
  - `EXTRA_TEXT: String`
  - `SNOOZE_MILLIS: Long = 300_000`
  - `fun triggerAtMillis(nowMillis: Long, offsetMillis: Long): Long`
  - `fun snoozeTriggerAtMillis(nowMillis: Long): Long`
- Consumed by: Tasks 4, 5, 6, 7.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/peskyreminders/poc/ReminderContractTest.kt`:
```kotlin
package com.peskyreminders.poc

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderContractTest {

    @Test fun triggerAt_adds_offset_to_now() {
        assertEquals(1_015_000L, ReminderContract.triggerAtMillis(1_000_000L, 15_000L))
    }

    @Test fun snooze_is_exactly_five_minutes_out() {
        assertEquals(1_000_000L + 300_000L, ReminderContract.snoozeTriggerAtMillis(1_000_000L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.peskyreminders.poc.ReminderContractTest"`
Expected: FAIL — `Unresolved reference: ReminderContract`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/peskyreminders/poc/ReminderContract.kt`:
```kotlin
package com.peskyreminders.poc

/** Shared constants and pure scheduling math for the reminder model. */
object ReminderContract {
    const val CHANNEL_ID = "pesky_reminders"
    const val NOTIFICATION_ID = 1001
    const val REQUEST_CODE = 2001

    const val ACTION_FIRE = "com.peskyreminders.poc.FIRE"
    const val ACTION_SNOOZE = "com.peskyreminders.poc.SNOOZE"
    const val ACTION_DONE = "com.peskyreminders.poc.DONE"
    const val ACTION_REPOST = "com.peskyreminders.poc.REPOST"

    const val EXTRA_TEXT = "extra_text"

    const val SNOOZE_MILLIS = 5 * 60 * 1000L

    fun triggerAtMillis(nowMillis: Long, offsetMillis: Long): Long = nowMillis + offsetMillis

    fun snoozeTriggerAtMillis(nowMillis: Long): Long = nowMillis + SNOOZE_MILLIS
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.peskyreminders.poc.ReminderContractTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ReminderContract constants and trigger-time logic"
```

---

### Task 4: ReminderNotifier + ReminderReceiver (FIRE / REPOST / DONE) — TDD, instrumented

**Files:**
- Create: `app/src/main/java/com/peskyreminders/poc/ReminderNotifier.kt`
- Create: `app/src/main/java/com/peskyreminders/poc/ReminderReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `<receiver>`)
- Test: `app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt`

**Interfaces:**
- Consumes: `ReminderContract` (Task 3).
- Produces:
  - `ReminderNotifier.post(context: Context, text: String)` — posts the ongoing notification with Snooze + Done actions and a REPOST delete-intent.
  - `ReminderNotifier.cancel(context: Context)` — cancels `NOTIFICATION_ID`.
  - `ReminderNotifier.ensureChannel(context: Context)`.
  - `ReminderReceiver : BroadcastReceiver` handling `ACTION_FIRE`, `ACTION_REPOST`, `ACTION_DONE` (SNOOZE added in Task 6).
- Consumed by: Tasks 5, 6, 7.

- [ ] **Step 1: Write the failing instrumented test**

`app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt`:
```kotlin
package com.peskyreminders.poc

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderModelTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun active() =
        nm.activeNotifications.firstOrNull { it.id == ReminderContract.NOTIFICATION_ID }

    private fun deliver(action: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TEXT, "Buy milk")
        }
        ReminderReceiver().onReceive(context, intent)
        Thread.sleep(300)
    }

    @Before fun clear() {
        ReminderNotifier.cancel(context)
        Thread.sleep(200)
    }

    @Test fun fire_posts_ongoing_notification_with_two_actions() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("expected a posted notification", n)
        assertTrue(
            "ongoing flag must be set (blocks clear-all + swipe-while-locked)",
            (n!!.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
        assertEquals("Snooze + Done", 2, n.notification.actions.size)
    }

    @Test fun dismissing_notification_triggers_repost() {
        deliver(ReminderContract.ACTION_FIRE)
        val posted = active()
        assertNotNull("precondition: posted", posted)
        // Fire the notification's OWN delete-intent — this is exactly what the OS
        // sends when the user swipes the notification away (on Android 14+ the
        // ongoing flag no longer blocks the swipe, so this path is what matters).
        // We do NOT call ReminderNotifier.cancel() + ACTION_REPOST by hand, because
        // that would bypass the real delete-intent wiring and prove nothing.
        posted!!.notification.deleteIntent.send()
        Thread.sleep(400)
        assertNotNull("dismissal must immediately re-post the notification", active())
    }

    @Test fun done_clears_it() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        deliver(ReminderContract.ACTION_DONE)
        assertNull("Done must clear the notification", active())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest`
Expected: FAIL — `Unresolved reference: ReminderNotifier` / `ReminderReceiver`.

- [ ] **Step 3: Write `ReminderNotifier.kt`**

```kotlin
package com.peskyreminders.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Builds and posts the ongoing, re-posting reminder notification. */
object ReminderNotifier {

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ReminderContract.CHANNEL_ID,
            "Pesky Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Reminders you must snooze or complete" }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, text: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Pesky Reminder")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(broadcast(context, ReminderContract.ACTION_REPOST, text))
            .addAction(0, "Snooze", broadcast(context, ReminderContract.ACTION_SNOOZE, text))
            .addAction(0, "Done", broadcast(context, ReminderContract.ACTION_DONE, text))
            .build()

        manager.notify(ReminderContract.NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ReminderContract.NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(), // distinct request code per action
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

Note: `NotificationManager.cancel()` does **not** fire the delete-intent — only a user dismissal does. So Snooze/Done cancelling the notification will not trigger a REPOST loop.

- [ ] **Step 4: Write `ReminderReceiver.kt`** (SNOOZE branch is a placeholder completed in Task 6)

```kotlin
package com.peskyreminders.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Central hub: fires, re-posts, snoozes, and completes the reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderContract.EXTRA_TEXT) ?: "Reminder"
        when (intent.action) {
            ReminderContract.ACTION_FIRE -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_REPOST -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_DONE -> ReminderNotifier.cancel(context)
        }
    }
}
```

- [ ] **Step 5: Register the receiver in `AndroidManifest.xml`**

Add inside `<application>`, after the `<activity>` block:
```xml
        <receiver
            android:name=".ReminderReceiver"
            android:exported="false" />
```

- [ ] **Step 6: Run the instrumented tests to verify they pass**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest`
Expected: PASS — `fire_posts_ongoing_notification_with_two_actions`, `dismissing_notification_triggers_repost`, `done_clears_it`. (The scheduler and snooze tests do not exist yet.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: ongoing re-posting notification + receiver (fire/repost/done)"
```

---

### Task 5: ReminderScheduler — exact alarm via setAlarmClock (TDD, instrumented)

**Files:**
- Create: `app/src/main/java/com/peskyreminders/poc/ReminderScheduler.kt`
- Modify: `app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt` (add one test)

**Interfaces:**
- Consumes: `ReminderContract` (Task 3), `MainActivity` (Task 2, used as the alarm's show-intent target).
- Produces: `ReminderScheduler.schedule(context: Context, text: String, triggerAtMillis: Long)` — schedules an exact alarm-clock that broadcasts `ACTION_FIRE` to `ReminderReceiver`.
- Consumed by: Tasks 6, 7.

- [ ] **Step 1: Write the failing test** (append to `ReminderModelTest`)

Add these imports to the test file:
```kotlin
import android.app.AlarmManager
```
Add this test method inside the class:
```kotlin
    @Test fun schedule_sets_an_exact_alarm_clock() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val target = System.currentTimeMillis() + 60_000L
        ReminderScheduler.schedule(context, "Buy milk", target)
        val next = alarmManager.nextAlarmClock
        assertNotNull("an alarm clock must be scheduled", next)
        val delta = Math.abs(next!!.triggerTime - target)
        assertTrue("alarm within 2s of target (delta=$delta ms)", delta < 2_000L)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest#schedule_sets_an_exact_alarm_clock`
Expected: FAIL — `Unresolved reference: ReminderScheduler`.

- [ ] **Step 3: Write `ReminderScheduler.kt`**

```kotlin
package com.peskyreminders.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Schedules the reminder to fire at an exact time using an alarm clock. */
object ReminderScheduler {

    fun schedule(context: Context, text: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context))
        alarmManager.setAlarmClock(info, firePendingIntent(context, text))
    }

    private fun firePendingIntent(context: Context, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_FIRE
            putExtra(ReminderContract.EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderContract.REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest#schedule_sets_an_exact_alarm_clock`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ReminderScheduler exact alarm via setAlarmClock"
```

---

### Task 6: Complete the SNOOZE branch (TDD, instrumented)

**Files:**
- Modify: `app/src/main/java/com/peskyreminders/poc/ReminderReceiver.kt`
- Modify: `app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt` (add one test)

**Interfaces:**
- Consumes: `ReminderNotifier`, `ReminderScheduler`, `ReminderContract`.
- Produces: `ReminderReceiver` now also handles `ACTION_SNOOZE` (cancel + reschedule +5 min).

- [ ] **Step 1: Write the failing test** (append to `ReminderModelTest`)

```kotlin
    @Test fun snooze_clears_and_reschedules_five_minutes_out() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        deliver(ReminderContract.ACTION_SNOOZE)
        assertNull("snooze clears the current notification", active())
        val next = alarmManager.nextAlarmClock
        assertNotNull("snooze must schedule a new alarm", next)
        val deltaMin = (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
        assertTrue("alarm ~5 min out (was $deltaMin min)", deltaMin in 4.0..5.5)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest#snooze_clears_and_reschedules_five_minutes_out`
Expected: FAIL — notification still present / `nextAlarmClock` null (SNOOZE not handled).

- [ ] **Step 3: Add the SNOOZE branch to `ReminderReceiver.onReceive`**

Replace the `when` block with:
```kotlin
        when (intent.action) {
            ReminderContract.ACTION_FIRE -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_REPOST -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_DONE -> ReminderNotifier.cancel(context)
            ReminderContract.ACTION_SNOOZE -> {
                ReminderNotifier.cancel(context)
                ReminderScheduler.schedule(
                    context,
                    text,
                    ReminderContract.snoozeTriggerAtMillis(System.currentTimeMillis()),
                )
            }
        }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest#snooze_clears_and_reschedules_five_minutes_out`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: snooze cancels and reschedules +5 min"
```

---

### Task 7: MainActivity UI — text field, offset picker, Schedule button, permission

**Files:**
- Modify: `app/src/main/java/com/peskyreminders/poc/MainActivity.kt`

**Interfaces:**
- Consumes: `ReminderContract`, `ReminderScheduler`.
- Produces: the working reminder-creation screen. No new public API.

- [ ] **Step 1: Replace `MainActivity.kt` with the full screen**

```kotlin
package com.peskyreminders.poc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { ReminderScreen(::scheduleReminder) } }
    }

    /** Schedules the reminder [offsetSeconds] from now via ReminderScheduler. */
    private fun scheduleReminder(text: String, offsetSeconds: Long) {
        val triggerAt = ReminderContract.triggerAtMillis(
            System.currentTimeMillis(), offsetSeconds * 1000L
        )
        ReminderScheduler.schedule(this, text, triggerAt)
    }
}

@Composable
private fun ReminderScreen(onSchedule: (String, Long) -> Unit) {
    var text by remember { mutableStateOf("Buy milk") }
    var offset by remember { mutableStateOf("15") }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pesky Reminders", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Reminder text") },
        )
        OutlinedTextField(
            value = offset,
            onValueChange = { offset = it.filter(Char::isDigit) },
            label = { Text("Remind me in (seconds)") },
        )
        Button(onClick = {
            val secs = offset.toLongOrNull() ?: 0L
            onSchedule(text, secs)
            status = "Scheduled to fire in ${secs}s"
        }) { Text("Schedule") }
        if (status.isNotEmpty()) Text(status)
    }
}
```

- [ ] **Step 2: Build and install**

Run (preamble required — see Global Constraints):
```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `BUILD SUCCESSFUL`; install `Success`.

- [ ] **Step 3: Launch and screenshot the UI**

Grant the notification permission up front (via `pm grant`) so the runtime
permission dialog does not overlay the screenshot:
```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell pm grant com.peskyreminders.poc android.permission.POST_NOTIFICATIONS
adb shell am start -n com.peskyreminders.poc/.MainActivity
sleep 2
adb exec-out screencap -p > /tmp/pr-ui.png
```
Expected: `/tmp/pr-ui.png` shows the text field, seconds field, and Schedule button.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: reminder-creation screen with permission request"
```

---

### Task 8: End-to-end verification + demo screenshots

**Files:** none (verification only; produces screenshot artifacts).

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (ReminderContractTest, 2 tests).

- [ ] **Step 2: Run the full instrumented suite**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — all **five** `ReminderModelTest` tests green:
`fire_posts_ongoing_notification_with_two_actions`,
`dismissing_notification_triggers_repost` (fires the notification's own
delete-intent — the authoritative, repeatable proof of un-dismissability),
`done_clears_it`, `schedule_sets_an_exact_alarm_clock`,
`snooze_clears_and_reschedules_five_minutes_out`.

- [ ] **Step 3: Reset app state, grant permission, schedule and fire**

`connectedDebugAndroidTest` may uninstall the app and leaves stray alarms, so
reset to a clean, granted state first. (Preamble required — see Global Constraints.)
```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.peskyreminders.poc                 # wipe data + cancel any stray alarms
adb shell pm grant com.peskyreminders.poc android.permission.POST_NOTIFICATIONS
adb shell wm dismiss-keyguard                             # must be unlocked for the swipe test
adb shell am start -n com.peskyreminders.poc/.MainActivity
sleep 2
adb exec-out screencap -p > /tmp/pr-demo-1-screen.png     # read Schedule button coords from this
adb shell input tap <x> <y>                               # tap Schedule (coords from screenshot)
sleep 16
adb shell cmd statusbar expand-notifications
sleep 1
adb exec-out screencap -p > /tmp/pr-demo-2-fired.png      # notification visible
```
Expected: `/tmp/pr-demo-2-fired.png` shows the "Pesky Reminder / Buy milk" notification with Snooze + Done.

- [ ] **Step 4: Prove un-dismissability by actually swiping it away (spec's core claim)**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
# Read the notification row's Y from /tmp/pr-demo-2-fired.png, then swipe it right to dismiss:
adb shell input swipe <x_left> <y_row> <x_right> <y_row> 200
sleep 1
adb shell cmd statusbar expand-notifications
sleep 1
adb exec-out screencap -p > /tmp/pr-demo-3-after-swipe.png   # notification is BACK (re-posted)
adb shell dumpsys notification --noredact | grep -i "pesky\|Buy milk" | head -10
```
Expected: after the swipe the notification **reappears** — `/tmp/pr-demo-3-after-swipe.png` still shows it and the `dumpsys` grep still finds it. This is the OS delete-intent → receiver → re-post chain in action (the same chain proven repeatably by `dismissing_notification_triggers_repost`). The ongoing flag additionally blocks "clear all" and swipe-while-locked, but the re-post is what defeats an ordinary swipe on API 35.

- [ ] **Step 5: Prove Done clears it for good**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell cmd statusbar expand-notifications
sleep 1
adb exec-out screencap -p > /tmp/pr-demo-4-actions.png    # locate the Done button
adb shell input tap <x> <y>                               # tap Done (coords from screenshot)
sleep 1
adb exec-out screencap -p > /tmp/pr-demo-5-done.png       # notification gone
adb shell dumpsys notification --noredact | grep -i "pesky\|Buy milk" | head -5
```
Expected: after Done, no reminder notification remains, and it does **not** re-post (Done calls `cancel()`, which does not fire the delete-intent).

- [ ] **Step 6: Final commit (artifacts + notes)**

```bash
git add -A
git commit -m "docs: POC verification artifacts and notes"
```

---

## Notes for the implementer

- If a version listed in Global Constraints fails to resolve (SDK licensing, Compose/AGP mismatch), adjust to the nearest compatible published version and record the change — do not silently skip a task's verification.
- `dumpsys notification` output format varies by Android version; the goal is to confirm our reminder notification is (or is not) present — grep for `pesky` / `Buy milk` accordingly.
- The authoritative, repeatable proof of un-dismissability is the instrumented test `dismissing_notification_triggers_repost` (Task 4), which fires the notification's real delete-intent. Task 8's adb swipe (Step 4) is the human-facing demonstration of the same chain; if the shade swipe coordinates prove flaky on the headless emulator, the instrumented test still stands as the proof.
- Tapping notification action buttons via `adb shell input tap` needs coordinates read from the screenshot taken the line before; there is no stable resource id for shade taps.
- The instrumented tests (`ReminderModelTest`) are the authoritative, repeatable proof. The screenshots are the human-facing demonstration.
