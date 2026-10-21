package com.razedragon.tinylab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suggestion & bug box. Messages are kept in a small outbox on the phone and sent automatically
 * to ENDPOINT, which forwards them to your email (so players never see the address).
 *
 * ENDPOINT is blank for now, so messages just wait in the outbox. Once it's set (for example a
 * Google Apps Script web app that emails you), every waiting message goes out on the next launch.
 */
object Feedback {
    /** Where messages are POSTed as JSON: {type, message, version, device, android, time}. */
    const val ENDPOINT = ""

    private const val PREFS = "tinylab_feedback"

    fun submit(context: Context, type: String, message: String, version: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val box = JSONArray(p.getString("outbox", "[]"))
        box.put(JSONObject().apply {
            put("type", type); put("message", message); put("version", version)
            put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            put("android", android.os.Build.VERSION.RELEASE); put("time", System.currentTimeMillis())
        })
        p.edit().putString("outbox", box.toString()).apply()
        flush(context)
    }

    /** Tries to send everything in the outbox (in the background). */
    fun flush(context: Context) {
        if (ENDPOINT.isBlank()) return
        val app = context.applicationContext
        Thread {
            try {
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val box = JSONArray(p.getString("outbox", "[]"))
                val left = JSONArray()
                for (i in 0 until box.length()) {
                    val item = box.getJSONObject(i)
                    if (!post(item)) left.put(item)
                }
                p.edit().putString("outbox", left.toString()).apply()
            } catch (_: Exception) {}
        }.start()
    }

    private fun post(item: JSONObject): Boolean = try {
        val conn = java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(item.toString().toByteArray()) }
        val ok = conn.responseCode in 200..399
        conn.disconnect()
        ok
    } catch (_: Exception) { false }

    fun waiting(context: Context): Int = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("outbox", "[]")).length()
}
