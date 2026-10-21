package com.razedragon.tinylab

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Swaps the home-screen icon to match the current event: Classic, Spooky or Festive. */
object IconSwitcher {
    private val ALIASES = arrayOf("LauncherClassic", "LauncherSpooky", "LauncherFestive")

    /** Which alias should be showing right now: 0 classic, 1 spooky, 2 festive. */
    fun wanted(): Int {
        return when (Data.currentEvent()) {
            1 -> 1
            2 -> 2
            else -> 0
        }
    }

    /** Called when the app goes to the background, so the swap never interrupts play. */
    fun apply(context: Context) {
        try {
            val pm = context.packageManager
            val want = wanted()
            for ((i, alias) in ALIASES.withIndex()) {
                val cn = ComponentName(context.packageName, "com.razedragon.tinylab.$alias")
                val target = if (i == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                             else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val now = pm.getComponentEnabledSetting(cn)
                val isOn = if (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) i == 0
                           else now == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                if (isOn != (i == want)) pm.setComponentEnabledSetting(cn, target, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) { }
    }
}
