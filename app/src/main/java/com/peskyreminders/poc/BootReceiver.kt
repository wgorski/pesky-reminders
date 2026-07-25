package com.peskyreminders.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pending alarms are dropped when the device reboots and when the app is
 * updated. The task list survives both, so re-arm everything from it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.restoreAll(context)
        }
    }
}
