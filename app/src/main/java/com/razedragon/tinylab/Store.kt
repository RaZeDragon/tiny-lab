package com.razedragon.tinylab

import android.app.Activity

/**
 * Real-money purchases. Google Play Billing only works for apps installed from Google Play,
 * so until Shroomology is released there, buy() reports "not available" and nothing is charged.
 *
 * At launch: add the Play Billing library, create products in Play Console with the same ids as
 * Data.PACKS (spores_small, spores_medium, spores_large, starter, event_boost), and complete the
 * purchase flow here, calling onDone(true) only after Google Play confirms the payment.
 */
object Store {
    /** Flip to true once Play Billing is connected. */
    var available = false

    fun buy(activity: Activity, pack: Pack, onDone: (Boolean) -> Unit) {
        if (!available) { onDone(false); return }
        // Play Billing purchase flow goes here.
        onDone(false)
    }
}
