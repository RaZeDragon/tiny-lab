package com.razedragon.tinylab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** One mushroom living in your garden. */
class Shroom(
    val id: Long,
    var species: Int,
    var xp: Float = 0f,
    var hunger: Float = 70f,      // 100 = full, 0 = starving
    var happy: Float = 70f,
    var dust: Float = 0f,         // spore dust waiting to be collected
    var restUntil: Long = 0L,     // can't crossbreed again until this time
    var petAt: Long = 0L,
    // Where it stands in the garden: gx = left..right (0..1), gz = far..near (0..1)
    var gx: Float = 0.15f + kotlin.random.Random.nextFloat() * 0.7f,
    var gz: Float = 0.2f + kotlin.random.Random.nextFloat() * 0.7f,
    var shiny: Boolean = false,
) {
    // Walking state (not saved)
    var tx = gx
    var tz = gz
    var phase = 0f
    var idle = kotlin.random.Random.nextFloat() * 2f
    var facing = 1f
    var moving = false
    var held = false

    fun stage(): Int {
        var s = 0
        for (i in Data.STAGE_XP.indices) if (xp >= Data.STAGE_XP[i]) s = i
        return s
    }

    fun tier() = Data.SPECIES[species].tier

    /** Hungry or sad mushrooms grow slower; happy, well-fed ones grow fastest. */
    fun mood(): Int = when {   // 0 happy, 1 okay, 2 hungry, 3 sad
        hunger < 25f -> 2
        happy < 30f -> 3
        happy > 65f && hunger > 50f -> 0
        else -> 1
    }

    fun dustCap() = 40f * Data.TIER_MUL[tier()] * (1 + stage())
}

/** A decoration placed in the garden. */
class Placed(val kind: Int, var gx: Float, var gz: Float)

/** Everything that gets saved: dust, inventory, garden, discoveries, tutorial progress. */
class LabState(context: Context) {
    private val prefs = context.getSharedPreferences("tinylab", Context.MODE_PRIVATE)

    var dust = 60
    val ingredients = IntArray(Data.INGREDIENTS.size)
    val foods = IntArray(Data.FOODS.size)
    val garden = ArrayList<Shroom>()
    val discovered = BooleanArray(Data.SPECIES.size)
    val shinyFound = BooleanArray(Data.SPECIES.size)
    val decorOwned = IntArray(Data.DECOR.size)
    val placed = ArrayList<Placed>()
    var themesOwned = 1
    var theme = 0
    var tutorial = 0
    var brewed = 0
    private var nextId = 1L
    private var lastTick = System.currentTimeMillis()

    val gardenSize = 8

    init {
        load()
    }

    fun newId() = nextId++

    // ---------- Simulation ----------

    /** Advances time for every mushroom. Called every second, and on launch for time spent away. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val hours = (min(now - lastTick, 72L * 3600_000L)).coerceAtLeast(0L) / 3600_000f
        lastTick = now
        if (hours <= 0f) return
        for (s in garden) {
            val growing = s.hunger > 0f
            val moodMul = when (s.mood()) { 0 -> 1.4f; 1 -> 1f; 2 -> 0.4f; else -> 0.6f }
            if (growing) s.xp += 20f * moodMul * hours
            s.hunger = max(0f, s.hunger - 9f * hours)
            // Decorations make mushrooms happier: each one slows the happiness drop a little.
            val charm = max(0.4f, 1f - 0.06f * placed.size)
            s.happy = max(0f, s.happy - 5f * charm * hours)
            // Grown-up mushrooms puff out more spore dust; rarer species puff even more.
            val rate = floatArrayOf(2f, 6f, 12f, 20f)[s.stage()] * Data.TIER_MUL[s.tier()] *
                    (if (s.hunger <= 0f) 0.5f else 1f) * (if (s.shiny) 2f else 1f)
            s.dust = min(s.dustCap(), s.dust + rate * hours)
        }
    }

    // ---------- Save / load ----------

    fun save() {
        val o = JSONObject()
        o.put("dust", dust)
        o.put("ing", JSONArray(ingredients.toList()))
        o.put("food", JSONArray(foods.toList()))
        o.put("disc", JSONArray(discovered.toList()))
        o.put("shinyFound", JSONArray(shinyFound.toList()))
        o.put("decor", JSONArray(decorOwned.toList()))
        o.put("themes", themesOwned)
        o.put("theme", theme)
        val pl = JSONArray()
        for (d in placed) pl.put(JSONObject().apply { put("k", d.kind); put("x", d.gx.toDouble()); put("z", d.gz.toDouble()) })
        o.put("placed", pl)
        o.put("tut", tutorial)
        o.put("brewed", brewed)
        o.put("nextId", nextId)
        o.put("lastTick", lastTick)
        val g = JSONArray()
        for (s in garden) {
            g.put(JSONObject().apply {
                put("id", s.id); put("sp", s.species); put("xp", s.xp.toDouble())
                put("hu", s.hunger.toDouble()); put("ha", s.happy.toDouble()); put("du", s.dust.toDouble())
                put("rest", s.restUntil); put("pet", s.petAt)
                put("gx", s.gx.toDouble()); put("gz", s.gz.toDouble()); put("sh", s.shiny)
            })
        }
        o.put("garden", g)
        prefs.edit().putString("save", o.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("save", null)
        if (raw == null) {
            // Starter kit
            ingredients[Data.WATER] = 4; ingredients[Data.SOIL] = 4
            ingredients[Data.EMBER] = 2; ingredients[Data.FROST] = 2
            ingredients[Data.SULFUR] = 2; ingredients[Data.IRON] = 1
            ingredients[Data.CRYSTAL] = 1; ingredients[Data.MOONDEW] = 1
            foods[0] = 5; foods[1] = 5; foods[2] = 2
            return
        }
        try {
            val o = JSONObject(raw)
            dust = o.optInt("dust", 60)
            o.optJSONArray("ing")?.let { a -> for (i in ingredients.indices) ingredients[i] = a.optInt(i, 0) }
            o.optJSONArray("food")?.let { a -> for (i in foods.indices) foods[i] = a.optInt(i, 0) }
            o.optJSONArray("disc")?.let { a -> for (i in discovered.indices) discovered[i] = a.optBoolean(i, false) }
            o.optJSONArray("shinyFound")?.let { a -> for (i in shinyFound.indices) shinyFound[i] = a.optBoolean(i, false) }
            o.optJSONArray("decor")?.let { a -> for (i in decorOwned.indices) decorOwned[i] = a.optInt(i, 0) }
            themesOwned = o.optInt("themes", 1) or 1
            theme = o.optInt("theme", 0).coerceIn(0, Data.THEMES.size - 1)
            o.optJSONArray("placed")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.getJSONObject(i)
                    val k = d.getInt("k")
                    if (k in Data.DECOR.indices) placed.add(Placed(k, d.getDouble("x").toFloat(), d.getDouble("z").toFloat()))
                }
            }
            tutorial = o.optInt("tut", 0)
            brewed = o.optInt("brewed", 0)
            nextId = o.optLong("nextId", 1L)
            lastTick = o.optLong("lastTick", System.currentTimeMillis())
            o.optJSONArray("garden")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    garden.add(Shroom(s.getLong("id"), s.getInt("sp").coerceIn(0, Data.SPECIES.size - 1),
                        s.optDouble("xp", 0.0).toFloat(), s.optDouble("hu", 70.0).toFloat(),
                        s.optDouble("ha", 70.0).toFloat(), s.optDouble("du", 0.0).toFloat(),
                        s.optLong("rest", 0L), s.optLong("pet", 0L),
                        s.optDouble("gx", 0.2 + Math.random() * 0.6).toFloat(),
                        s.optDouble("gz", 0.2 + Math.random() * 0.6).toFloat(),
                        s.optBoolean("sh", false)))
                }
            }
        } catch (_: Exception) {
            // A broken save shouldn't crash the game; start fresh instead.
        }
    }

    fun wipe() {
        prefs.edit().clear().commit()
    }
}
