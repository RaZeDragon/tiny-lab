package com.razedragon.tinylab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File

/** Home-screen widget: your favorite shroom, gently bobbing, with how it's feeling. */
class ShroomWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        /** Redraws every Shroomology widget on the home screen. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ShroomWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(context)
            for (id in ids) manager.updateAppWidget(id, v)
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_shroom)
            val f0 = File(context.filesDir, "widget0.png")
            val f1 = File(context.filesDir, "widget1.png")
            val wp = context.getSharedPreferences("tinylab_widget", Context.MODE_PRIVATE)
            if (f0.exists() && f1.exists()) {
                BitmapFactory.decodeFile(f0.path)?.let { v.setImageViewBitmap(R.id.widget_img0, it) }
                BitmapFactory.decodeFile(f1.path)?.let { v.setImageViewBitmap(R.id.widget_img1, it) }
                val name = wp.getString("name", "Your shroom") ?: "Your shroom"
                v.setTextViewText(R.id.widget_text, "$name ${liveMood(context, wp.getLong("id", -1L)) ?: wp.getString("mood", "says hi! 👋")}")
            } else {
                v.setTextViewText(R.id.widget_text, "Open Shroomology to meet your shroom!")
            }
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }

        /** Works out how the shroom feels now, from the saved game and the time since you last played. */
        private fun liveMood(context: Context, id: Long): String? = try {
            val raw = context.getSharedPreferences("tinylab", Context.MODE_PRIVATE).getString("save", null)
            val o = JSONObject(raw ?: "")
            val hours = (System.currentTimeMillis() - o.optLong("lastTick", System.currentTimeMillis())) / 3_600_000f
            val g = o.getJSONArray("garden")
            var mood: String? = null
            for (i in 0 until g.length()) {
                val s = g.getJSONObject(i)
                if (s.getLong("id") != id) continue
                val away = s.optInt("az", -1) >= 0 && s.optLong("au", 0L) > System.currentTimeMillis()
                val hunger = s.optDouble("hu", 70.0) - 9.0 * hours
                val happy = s.optDouble("ha", 70.0) - 5.0 * hours
                mood = when {
                    away -> "is out exploring 🧭"
                    hunger < 25 -> "is hungry! 🍂"
                    happy < 30 || hours > 12 -> "misses you 💭"
                    happy > 65 && hunger > 50 -> "is happy 😊"
                    else -> "says hi! 👋"
                }
            }
            mood
        } catch (_: Exception) { null }
    }
}
