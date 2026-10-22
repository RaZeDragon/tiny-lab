package com.razedragon.tinylab

import android.app.Activity

/**
 * Optional rewarded video ads. Shroomology never forces ads: they only play when a player taps a
 * "watch an ad" button to get a bonus. Until an ad network (for example Google AdMob) is connected
 * at the Google Play release, show() reports "not available" and the player keeps the normal reward.
 */
object Ads {
    /** Flip to true once a rewarded-ad SDK is connected. */
    var available = false

    /** Plays a rewarded ad; onDone(true) only if the player watched it to the end. */
    fun showRewarded(activity: Activity, onDone: (Boolean) -> Unit) {
        if (!available) { onDone(false); return }
        // Rewarded ad flow goes here.
        onDone(false)
    }
}
