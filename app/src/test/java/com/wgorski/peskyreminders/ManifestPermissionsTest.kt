package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The exact-alarm permissions are a *pair*, and the pairing is load-bearing.
 *
 * `USE_EXACT_ALARM` only exists from API 33. The app's `minSdk` is 26 and it
 * targets 36, so on Android 12 and 12L (API 31–32) the platform does not know
 * that permission at all — and because the app targets ≥ 31, those versions
 * demand one of the "Alarms & reminders" permissions or `setAlarmClock` throws
 * `SecurityException`. `SCHEDULE_EXACT_ALARM`, capped at 32, covers exactly that
 * window, and Android 12 pre-grants it so no runtime code is needed.
 *
 * This is a test rather than a comment because the failure mode is invisible from
 * here: the JVM suite, the `pesky` emulator (API 35) and every real device from
 * Android 13 up all stay green while reminders throw on 12 and 12L. Nothing else
 * in the suite would notice the declaration being dropped.
 */
class ManifestPermissionsTest {

    @Test fun use_exact_alarm_applies_to_every_version_that_knows_it() {
        val permission = declared("android.permission.USE_EXACT_ALARM")
        assertNotNull("USE_EXACT_ALARM is the permission the app runs on from API 33 up", permission)
        assertNull(
            "USE_EXACT_ALARM must not be capped — it has to apply on every version from 33 onwards",
            permission!!.maxSdkVersion,
        )
    }

    @Test fun schedule_exact_alarm_covers_android_12_and_stops_there() {
        val permission = declared("android.permission.SCHEDULE_EXACT_ALARM")
        assertNotNull(
            "Android 12 and 12L need SCHEDULE_EXACT_ALARM — USE_EXACT_ALARM does not exist below API 33, " +
                "so without this setAlarmClock throws SecurityException there",
            permission,
        )
        assertEquals(
            "Cap it at 32. Above that USE_EXACT_ALARM already covers us, and leaving it uncapped puts the " +
                "app in Settings → Alarms & reminders with a toggle that cannot change anything",
            "32",
            permission!!.maxSdkVersion,
        )
    }

    @Test fun the_notification_permission_is_still_declared() {
        // Cheap guard that this test is reading the real manifest and not an empty
        // document — a parse that silently returned nothing would make the two
        // assertions above vacuous rather than failing.
        assertNotNull(declared("android.permission.POST_NOTIFICATIONS"))
    }

    private data class UsesPermission(val name: String, val maxSdkVersion: String?)

    private fun declared(name: String): UsesPermission? =
        permissions().firstOrNull { it.name == name }

    private fun permissions(): List<UsesPermission> {
        // Gradle runs unit tests with the module directory as the working dir, but
        // an IDE may use the repo root. Try both rather than depend on which.
        val manifest = sequenceOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("no AndroidManifest.xml found from ${File(".").absolutePath}")

        val nodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
            .getElementsByTagName("uses-permission")

        return (0 until nodes.length).map { i ->
            val element = nodes.item(i) as Element
            UsesPermission(
                name = element.getAttribute("android:name"),
                maxSdkVersion = element.getAttribute("android:maxSdkVersion").ifEmpty { null },
            )
        }
    }
}
