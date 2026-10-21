package com.razedragon.tinylab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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
    var gx: Float = 0.15f + Random.nextFloat() * 0.7f,
    var gz: Float = 0.2f + Random.nextFloat() * 0.7f,
    var shiny: Boolean = false,
    var name: String = "Shroom",
    var trait: Int = 0,
    // Genes
    var tint: Float = 0f,          // hue shift in degrees
    var size: Float = 1f,          // body size multiplier
    var spots: Int = 0,            // 0 normal, 1 extra spots, 2 no spots, 3 big spots
    var variant: Int = 0,          // 0 normal, 1 albino, 2 neon
    // Worn accessories by slot (cap, face, stem); -1 = nothing
    val wear: IntArray = intArrayOf(-1, -1, -1),
    // Expedition
    var awayZone: Int = -1,
    var awayUntil: Long = 0L,
    // Friendship with you (0..100, 20 per heart)
    var bond: Float = 0f,
    var parentId: Long = -1L,
    // What it's asking for: 0 nothing, 1 a food, 2 a hug, 3 to play (be tossed)
    var want: Int = 0,
    var wantFood: Int = 0,
    var wantUntil: Long = 0L,
    var nextWantAt: Long = 0L,
) {
    // Touch reactions & expressions (not saved)
    var jiggle = 0f
    var expr = 0
    var exprT = 0f
    var airH = 0f
    var airV = 0f
    var fvx = 0f
    var fvz = 0f
    var bounces = 0
    var idleAnim = 0
    var idleAnimT = 0f
    var waveT = 0f
    var spinT = 0f
    var hugAt = 0L
    var lastPoke = 0f

    fun hearts() = (bond / 20f).toInt().coerceIn(0, 5)
    fun airborne() = airH > 0f || airV != 0f

    // Walking & behaviour state (not saved)
    var tx = gx
    var tz = gz
    var phase = 0f
    var idle = Random.nextFloat() * 2f
    var facing = 1f
    var moving = false
    var held = false
    var act = 0                 // what it's doing: see GameView.ACT_*
    var actTimer = 0f
    var actTarget = -1          // decoration index or other mushroom's position in the list
    var pendingAct = 0          // activity to start on arrival
    var awakeUntil = 0L         // tapped awake at night
    var sleeping = false

    fun stage(): Int {
        var s = 0
        for (i in Data.STAGE_XP.indices) if (xp >= Data.STAGE_XP[i]) s = i
        return s
    }

    fun tier() = Data.SPECIES[species].tier
    fun away() = awayZone >= 0

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

/** A request from Professor Spore. */
class Request(val type: Int, val target: Int, val param: Int, val base: Int, val reward: Int)

/** One entrant in a beauty contest. */
class Entrant(val name: String, val score: Int, val species: Int, val mine: Boolean)

/** Everything that gets saved: dust, inventory, garden, discoveries, progress. */
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
    val upgrades = IntArray(Data.UPGRADES.size)
    val accOwned = IntArray(Data.ACCESSORIES.size)
    val achieved = BooleanArray(Data.ACHIEVEMENTS.size)
    val requests = ArrayList<Request>()

    // Stats
    var crosses = 0
    var feeds = 0
    var perfect = 0
    var expSent = 0
    var expDone = 0
    var contestWins = 0
    var mutants = 0

    // Daily visits & contest
    var lastLoginDay = 0
    var streak = 0
    var giftDay = 0             // the day whose gift was claimed
    var nextContest = 0L
    var lastContest: List<Entrant> = emptyList()

    /** The garden diary: newest entries first. */
    val diary = ArrayList<String>()

    fun addDiary(text: String) {
        val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date())
        diary.add(0, "$date · $text")
        while (diary.size > 60) diary.removeAt(diary.size - 1)
    }

    /** Messages for the screen to show (expedition returns, auto-feeder...). */
    val events = ArrayList<String>()

    private var nextId = 1L
    private var lastTick = System.currentTimeMillis()

    init {
        load()
        while (requests.size < 3) requests.add(newRequest())
    }

    fun newId() = nextId++
    fun gardenSize() = 8 + 2 * upgrades[Data.UP_GARDEN]
    fun home() = garden.filter { !it.away() }
    fun accAvailable(i: Int) = accOwned[i] - garden.count { s -> s.wear.contains(i) }

    // ---------- Time, weather ----------

    companion object {
        fun dayKey(cal: Calendar = Calendar.getInstance()) = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        fun hour() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }

        /** 0 = full day, 1 = deep night, smoothly blended at dusk (18-21) and dawn (5-7). */
        fun darkness(h: Float = hour()): Float = when {
            h >= 21f || h < 5f -> 1f
            h >= 18f -> (h - 18f) / 3f
            h < 7f -> 1f - (h - 5f) / 2f
            else -> 0f
        }

        /** About one day in four is rainy. */
        fun rainy(day: Int = dayKey()) = Random(day * 31 + 7).nextFloat() < 0.27f

        /** On rainy days the rain stops in the late afternoon and a rainbow comes out. */
        fun raining() = rainy() && hour() !in 15f..18f
        fun rainbow() = rainy() && hour() in 15f..18f
    }

    // ---------- New mushrooms & genetics ----------

    fun newShroom(species: Int, xp: Float, shiny: Boolean, a: Shroom? = null, b: Shroom? = null): Shroom {
        val used = garden.map { it.name }.toSet()
        val name = Data.NAMES.filter { it !in used }.randomOrNull() ?: Data.NAMES.random()
        val s = Shroom(newId(), species, xp = xp, shiny = shiny, name = name, trait = Random.nextInt(Data.TRAITS.size))
        if (a != null && b != null) {
            // Babies take after their parents, with a little mutation
            s.tint = ((a.tint + b.tint) / 2f + (Random.nextFloat() - 0.5f) * 16f).coerceIn(-40f, 40f)
            s.size = ((a.size + b.size) / 2f + (Random.nextFloat() - 0.5f) * 0.1f).coerceIn(0.8f, 1.22f)
            s.spots = if (Random.nextFloat() < 0.1f) Random.nextInt(4) else if (Random.nextBoolean()) a.spots else b.spots
            val parentVariant = listOf(a.variant, b.variant).filter { it > 0 }
            s.variant = when {
                parentVariant.isNotEmpty() && Random.nextFloat() < 0.25f -> parentVariant.random()
                Random.nextFloat() < 0.03f -> 1 + Random.nextInt(2)
                else -> 0
            }
            s.trait = if (Random.nextFloat() < 0.6f) (if (Random.nextBoolean()) a.trait else b.trait) else s.trait
            s.parentId = if (Random.nextBoolean()) a.id else b.id
        } else {
            s.tint = (Random.nextFloat() - 0.5f) * 24f
            s.size = 0.9f + Random.nextFloat() * 0.2f
            val r = Random.nextFloat()
            s.spots = when { r < 0.7f -> 0; r < 0.85f -> 1; r < 0.95f -> 3; else -> 2 }
            val v = Random.nextFloat()
            s.variant = when { v < 0.01f -> 1; v < 0.017f -> 2; else -> 0 }
        }
        if (s.variant > 0) mutants++
        return s
    }

    // ---------- Simulation ----------

    /** Advances time for every mushroom. Called every second, and on launch for time spent away. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val hours = (min(now - lastTick, 72L * 3600_000L)).coerceAtLeast(0L) / 3600_000f
        lastTick = now
        expireEventIngredients()
        if (hours <= 0f) return
        val rainMul = if (rainy()) 1.5f else 1f
        val charm = max(0.4f, 1f - 0.06f * placed.size)
        for (s in garden) {
            if (s.away()) {
                // Out exploring: they forage for themselves and grow a little
                s.xp += 8f * hours
                if (now >= s.awayUntil) returnHome(s)
                continue
            }
            val growing = s.hunger > 0f
            val moodMul = when (s.mood()) { 0 -> 1.4f; 1 -> 1f; 2 -> 0.4f; else -> 0.6f }
            if (growing) s.xp += 20f * moodMul * rainMul * hours
            s.hunger = max(0f, s.hunger - 9f * hours * (if (s.trait == 3) 1.3f else 1f))
            s.happy = max(0f, s.happy - 5f * charm * hours * (if (s.trait == 5) 0.6f else 1f))
            val rate = floatArrayOf(2f, 6f, 12f, 20f)[s.stage()] * Data.TIER_MUL[s.tier()] *
                    (if (s.hunger <= 0f) 0.5f else 1f) * (if (s.shiny) 2f else 1f)
            s.dust = min(s.dustCap(), s.dust + rate * hours)

            if (upgrades[Data.UP_FEEDER] > 0 && s.hunger < 30f) {
                val f = foods.indices.firstOrNull { foods[it] > 0 }
                if (f != null) {
                    foods[f]--
                    s.hunger = min(100f, s.hunger + Data.FOODS[f].hunger)
                    s.xp += Data.FOODS[f].xp
                    feeds++
                    events.add("Auto-feeder gave ${s.name} some ${Data.FOODS[f].name}")
                }
            }
            if (upgrades[Data.UP_COLLECTOR] > 0 && s.dust >= 1f) {
                val n = s.dust.toInt()
                s.dust -= n
                dust += n
            }
        }
    }

    // ---------- Expeditions ----------

    fun expeditionHours(zone: Int) = Data.ZONES[zone].hours * (if (upgrades[Data.UP_BOOTS] > 0) 0.75f else 1f)

    fun sendAway(s: Shroom, zone: Int) {
        s.awayZone = zone
        s.awayUntil = System.currentTimeMillis() + (expeditionHours(zone) * 3600_000f).toLong()
        s.act = 0; s.sleeping = false
        expSent++
    }

    private fun returnHome(s: Shroom) {
        val z = Data.ZONES[s.awayZone]
        val haul = IntArray(Data.INGREDIENTS.size)
        val n = 2 + Random.nextInt(3) + (if (s.trait == 4) 2 else 0) + (z.hours / 4f).toInt()
        repeat(n) { haul[z.loot.random()]++ }
        for (i in haul.indices) ingredients[i] += haul[i]
        val dustFound = ((10 + z.hours * 12f) * (1 + s.stage() * 0.3f)).toInt()
        dust += dustFound
        s.xp += z.hours * 15f
        s.hunger = max(s.hunger, 60f)
        s.happy = min(100f, s.happy + 20f)
        val parts = haul.indices.filter { haul[it] > 0 }.map { "${haul[it]} ${Data.INGREDIENTS[it].name}" }.toMutableList()
        parts.add("$dustFound ✦")
        if (Random.nextFloat() < 0.12f + (if (s.trait == 4) 0.1f else 0f)) {
            if (Random.nextBoolean()) {
                val a = Random.nextInt(6); accOwned[a]++; parts.add("a ${Data.ACCESSORIES[a].name}!")
            } else {
                val d = Random.nextInt(Data.DECOR.size); decorOwned[d]++; parts.add("a ${Data.DECOR[d].name}!")
            }
        }
        val story = Data.STORIES.random()
        events.add("${s.name} is back from ${z.name}! It $story and brought ${parts.joinToString(", ")}")
        addDiary("${s.name} explored ${z.name} and $story.")
        s.awayZone = -1
        s.gx = if (Random.nextBoolean()) 0.06f else 0.94f
        s.gz = 0.3f + Random.nextFloat() * 0.5f
        s.tx = 0.5f; s.tz = s.gz
        expDone++
    }

    // ---------- Professor's requests ----------

    fun discoveredCount() = discovered.count { it }

    private fun stat(type: Int): Int = when (type) {
        1 -> discoveredCount(); 2 -> brewed; 3 -> feeds; 5 -> expDone; 6 -> perfect; 7 -> crosses; else -> 0
    }

    /** Event ingredients fade away once their event is over. */
    fun expireEventIngredients() {
        val ev = Data.currentEvent()
        val gone = ArrayList<String>()
        for (i in ingredients.indices) {
            val e = Data.INGREDIENTS[i].event
            if (e != 0 && e != ev && ingredients[i] > 0) { ingredients[i] = 0; gone.add(Data.INGREDIENTS[i].name) }
        }
        if (gone.isNotEmpty()) {
            val msg = "${gone.joinToString(" and ")} faded away until next year"
            events.add(msg); addDiary(msg)
        }
    }

    fun newRequest(): Request {
        val known = discovered.indices.filter { discovered[it] && Data.SPECIES[it].tier == 1 && !Data.offSeason(it) }
        val type = if (known.isEmpty()) listOf(2, 3).random() else Random.nextInt(8)
        return when (type) {
            0 -> Request(0, 1, known.random(), 0, 60)
            1 -> Request(1, 1 + Random.nextInt(2), 0, stat(1), 80)
            2 -> Request(2, 3 + Random.nextInt(4), 0, stat(2), 50)
            3 -> Request(3, 5 + Random.nextInt(6), 0, stat(3), 40)
            4 -> Request(4, placed.size + 2 + Random.nextInt(3), 0, 0, 60)
            5 -> Request(5, 1 + Random.nextInt(2), 0, stat(5), 90)
            6 -> Request(6, 1, 0, stat(6), 70)
            else -> Request(7, 1 + Random.nextInt(2), 0, stat(7), 70)
        }
    }

    fun requestText(r: Request) = when (r.type) {
        0 -> "Raise a Grown ${Data.SPECIES[r.param].name}"
        1 -> "Discover ${r.target} new species"
        2 -> "Brew ${r.target} mushrooms"
        3 -> "Feed your mushrooms ${r.target} times"
        4 -> "Have ${r.target} decorations in your garden"
        5 -> "Finish ${r.target} expedition${if (r.target > 1) "s" else ""}"
        6 -> "Make a perfect brew"
        else -> "Crossbreed ${r.target} time${if (r.target > 1) "s" else ""}"
    }

    /** (progress, goal) */
    fun requestProgress(r: Request): Pair<Int, Int> = when (r.type) {
        0 -> Pair(if (garden.any { it.species == r.param && it.stage() >= 2 }) 1 else 0, 1)
        4 -> Pair(min(placed.size, r.target), r.target)
        else -> Pair(min(stat(r.type) - r.base, r.target), r.target)
    }

    fun claimRequest(i: Int): Int {
        val r = requests[i]
        dust += r.reward
        requests[i] = newRequest()
        return r.reward
    }

    // ---------- Daily gift ----------

    /** Call on launch: updates the visit streak. */
    fun visit() {
        val today = dayKey()
        if (lastLoginDay == today) return
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        streak = if (lastLoginDay == dayKey(y)) streak + 1 else 1
        lastLoginDay = today
    }

    fun giftReady() = giftDay != lastLoginDay && lastLoginDay != 0
    fun giftIndex() = (streak - 1).coerceAtLeast(0) % 7

    fun giftText(i: Int) = arrayOf("20 ✦", "3 Compost", "40 ✦", "Ingredient pack", "60 ✦", "Moon Jelly + Gold", "100 ✦ + a hat")[i]

    fun claimGift(): String {
        val i = giftIndex()
        when (i) {
            0 -> dust += 20
            1 -> foods[1] += 3
            2 -> dust += 40
            3 -> repeat(6) { ingredients[Random.nextInt(8)]++ }
            4 -> dust += 60
            5 -> { foods[4]++; ingredients[Data.GOLD]++ }
            else -> { dust += 100; accOwned[Random.nextInt(9)]++ }
        }
        giftDay = lastLoginDay
        // during an event, every gift comes with a couple of its special ingredients
        val ev = Data.currentEvent()
        if (ev > 0) {
            val evIngs = Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event == ev }
            for (k in evIngs) ingredients[k] += 2
            return giftText(i) + " + 2 each of " + evIngs.joinToString(" & ") { Data.INGREDIENTS[it].name }
        }
        return giftText(i)
    }

    // ---------- Beauty contest ----------

    fun contestScore(s: Shroom): Int {
        var score = s.happy * 0.4f + s.stage() * 12f + Data.TIER_MUL[s.tier()] * 10f
        if (s.shiny) score += 25f
        if (s.variant > 0) score += 20f
        score += s.wear.count { it >= 0 } * 9f
        score += min(placed.size, 12) * 2f
        score += (s.size - 1f) * 30f
        if (s.hunger > 50f) score += 5f
        return score.toInt()
    }

    private val rivals = arrayOf("Prof. Morel's Biscuit", "Granny Fern's Toadie", "Sir Puffington", "Lady Chanterelle",
        "Bolete Bros' Chunk", "Little Mia's Dotty", "The Cave Hermit's Glim")

    /** Runs the contest; returns your place (1-4). */
    fun runContest(s: Shroom): Int {
        val mine = contestScore(s)
        val list = ArrayList<Entrant>()
        list.add(Entrant(s.name, mine, s.species, true))
        val base = max(40, mine)
        for (n in rivals.toList().shuffled().take(3)) {
            list.add(Entrant(n, (base * (0.72f + Random.nextFloat() * 0.5f)).toInt(), Random.nextInt(34), false))
        }
        list.sortByDescending { it.score }
        lastContest = list
        val place = list.indexOfFirst { it.mine } + 1
        dust += intArrayOf(120, 60, 30, 10)[place - 1]
        s.happy = min(100f, s.happy + 10f)
        if (place == 1) contestWins++
        addDiary("${s.name} came ${arrayOf("1st 🥇", "2nd 🥈", "3rd 🥉", "4th")[place - 1]} in the beauty contest.")
        nextContest = System.currentTimeMillis() + 3 * 3600_000L
        return place
    }

    // ---------- Achievements ----------

    private fun achievementMet(i: Int): Boolean = when (i) {
        0 -> brewed >= 1
        1 -> discoveredCount() >= 10
        2 -> discoveredCount() >= 25
        3 -> Data.BASE_SPECIES.all { discovered[it] }
        4 -> discovered.indices.any { discovered[it] && Data.SPECIES[it].tier == 2 }
        5 -> discovered.indices.any { discovered[it] && Data.SPECIES[it].tier == 3 }
        6 -> discovered.indices.any { discovered[it] && Data.SPECIES[it].tier == 4 }
        7 -> shinyFound.any { it }
        8 -> perfect >= 10
        9 -> garden.any { it.stage() >= 3 }
        10 -> placed.size >= 10
        11 -> accOwned.sum() >= 5
        12 -> expDone >= 10
        13 -> contestWins >= 1
        14 -> garden.size >= gardenSize()
        15 -> streak >= 7
        else -> mutants >= 1
    }

    /** Unlocks any newly earned achievements, pays their rewards, and returns them. */
    fun checkAchievements(): List<Int> {
        val out = ArrayList<Int>()
        for (i in achieved.indices) if (!achieved[i] && achievementMet(i)) {
            achieved[i] = true
            dust += Data.ACHIEVEMENTS[i].reward
            out.add(i)
            addDiary("Earned the \"${Data.ACHIEVEMENTS[i].name}\" award! 🏆")
        }
        return out
    }

    // ---------- Save / load ----------

    private fun ints(a: IntArray) = JSONArray(a.toList())
    private fun bools(a: BooleanArray) = JSONArray(a.toList())
    private fun readInts(o: JSONObject, k: String, into: IntArray) =
        o.optJSONArray(k)?.let { a -> for (i in into.indices) into[i] = a.optInt(i, into[i]) }
    private fun readBools(o: JSONObject, k: String, into: BooleanArray) =
        o.optJSONArray(k)?.let { a -> for (i in into.indices) into[i] = a.optBoolean(i, false) }

    fun save() {
        val o = JSONObject()
        o.put("dust", dust)
        o.put("ing", ints(ingredients))
        o.put("food", ints(foods))
        o.put("disc", bools(discovered))
        o.put("shinyFound", bools(shinyFound))
        o.put("decor", ints(decorOwned))
        o.put("themes", themesOwned)
        o.put("theme", theme)
        o.put("upg", ints(upgrades))
        o.put("acc", ints(accOwned))
        o.put("ach", bools(achieved))
        val pl = JSONArray()
        for (d in placed) pl.put(JSONObject().apply { put("k", d.kind); put("x", d.gx.toDouble()); put("z", d.gz.toDouble()) })
        o.put("placed", pl)
        val rq = JSONArray()
        for (r in requests) rq.put(JSONObject().apply { put("t", r.type); put("g", r.target); put("p", r.param); put("b", r.base); put("r", r.reward) })
        o.put("req", rq)
        o.put("tut", tutorial)
        o.put("brewed", brewed)
        o.put("crosses", crosses); o.put("feeds", feeds); o.put("perfect", perfect)
        o.put("expSent", expSent); o.put("expDone", expDone); o.put("wins", contestWins); o.put("mutants", mutants)
        o.put("login", lastLoginDay); o.put("streak", streak); o.put("gift", giftDay); o.put("contest", nextContest)
        o.put("nextId", nextId)
        o.put("lastTick", lastTick)
        val g = JSONArray()
        for (s in garden) {
            g.put(JSONObject().apply {
                put("id", s.id); put("sp", s.species); put("xp", s.xp.toDouble())
                put("hu", s.hunger.toDouble()); put("ha", s.happy.toDouble()); put("du", s.dust.toDouble())
                put("rest", s.restUntil); put("pet", s.petAt)
                put("gx", s.gx.toDouble()); put("gz", s.gz.toDouble()); put("sh", s.shiny)
                put("nm", s.name); put("tr", s.trait); put("ti", s.tint.toDouble()); put("sz", s.size.toDouble())
                put("sp2", s.spots); put("va", s.variant); put("wear", ints(s.wear))
                put("az", s.awayZone); put("au", s.awayUntil)
                put("bond", s.bond.toDouble()); put("par", s.parentId)
            })
        }
        o.put("garden", g)
        o.put("diary", JSONArray(diary))
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
            readInts(o, "ing", ingredients)
            readInts(o, "food", foods)
            readBools(o, "disc", discovered)
            readBools(o, "shinyFound", shinyFound)
            readInts(o, "decor", decorOwned)
            readInts(o, "upg", upgrades)
            readInts(o, "acc", accOwned)
            readBools(o, "ach", achieved)
            themesOwned = o.optInt("themes", 1) or 1
            theme = o.optInt("theme", 0).coerceIn(0, Data.THEMES.size - 1)
            o.optJSONArray("placed")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.getJSONObject(i)
                    val k = d.getInt("k")
                    if (k in Data.DECOR.indices) placed.add(Placed(k, d.getDouble("x").toFloat(), d.getDouble("z").toFloat()))
                }
            }
            o.optJSONArray("req")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    requests.add(Request(r.getInt("t"), r.getInt("g"), r.getInt("p"), r.getInt("b"), r.getInt("r")))
                }
            }
            tutorial = o.optInt("tut", 0)
            brewed = o.optInt("brewed", 0)
            crosses = o.optInt("crosses"); feeds = o.optInt("feeds"); perfect = o.optInt("perfect")
            expSent = o.optInt("expSent"); expDone = o.optInt("expDone"); contestWins = o.optInt("wins"); mutants = o.optInt("mutants")
            lastLoginDay = o.optInt("login"); streak = o.optInt("streak"); giftDay = o.optInt("gift"); nextContest = o.optLong("contest")
            nextId = o.optLong("nextId", 1L)
            lastTick = o.optLong("lastTick", System.currentTimeMillis())
            o.optJSONArray("diary")?.let { a -> for (i in 0 until a.length()) diary.add(a.optString(i)) }
            o.optJSONArray("garden")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val sh = Shroom(s.getLong("id"), s.getInt("sp").coerceIn(0, Data.SPECIES.size - 1),
                        s.optDouble("xp", 0.0).toFloat(), s.optDouble("hu", 70.0).toFloat(),
                        s.optDouble("ha", 70.0).toFloat(), s.optDouble("du", 0.0).toFloat(),
                        s.optLong("rest", 0L), s.optLong("pet", 0L),
                        s.optDouble("gx", 0.2 + Math.random() * 0.6).toFloat(),
                        s.optDouble("gz", 0.2 + Math.random() * 0.6).toFloat(),
                        s.optBoolean("sh", false),
                        s.optString("nm", Data.NAMES.random()),
                        s.optInt("tr", Random.nextInt(Data.TRAITS.size)),
                        s.optDouble("ti", 0.0).toFloat(), s.optDouble("sz", 1.0).toFloat(),
                        s.optInt("sp2", 0), s.optInt("va", 0))
                    s.optJSONArray("wear")?.let { w -> for (k in 0..2) sh.wear[k] = w.optInt(k, -1) }
                    sh.awayZone = s.optInt("az", -1).coerceIn(-1, Data.ZONES.size - 1)
                    sh.awayUntil = s.optLong("au", 0L)
                    sh.bond = s.optDouble("bond", 0.0).toFloat()
                    sh.parentId = s.optLong("par", -1L)
                    garden.add(sh)
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
