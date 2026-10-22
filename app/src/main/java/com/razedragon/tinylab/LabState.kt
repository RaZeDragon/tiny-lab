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
    // Family: [parent1, parent2, grandparents x4] as "name|species" ("" = unknown)
    val family = Array(6) { "" }
    var bestie = -1L            // id of its best friend
    var pet = -1                // Data.PETS index following it around
    var job = 0                 // Data.JOB_*
    var area = 0                // 0 meadow, 1 glow cave
    // pet position (not saved)
    var petGx = Float.NaN
    var petGz = 0f
    var petPhase = 0f

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
    var thought = ""
    var thoughtT = 0f

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
    val petOwned = IntArray(Data.PETS.size)
    /** Recipe notebook: "a,b" ingredient pair -> species it grew. */
    val tried = HashMap<String, Int>()
    /** How many times each rare recipe fizzled. */
    val fizzles = IntArray(Data.SPECIES.size)
    /** Friendship points between pairs of mushrooms ("id1-id2"). */
    val friendship = HashMap<String, Float>()
    var tuckedUntil = 0L        // tucked in for the night until this time
    val questYear = IntArray(4)
    val questStep = IntArray(4)
    var questCount = 0
    var mysteryClaimed = 0      // week whose mystery recipe was claimed
    var forageAcc = 0f
    var noSave = false          // set after restoring a backup so the old lab doesn't overwrite it
    val seeds = IntArray(Data.SEEDS.size)
    val plotSeed = IntArray(6) { -1 }
    val plotAt = LongArray(6)
    val plotWater = BooleanArray(6)
    val stickers = BooleanArray(Data.STICKERS.size)
    val stickerPos = FloatArray(Data.STICKERS.size * 2) { Float.NaN }
    var usedBedtime = false
    var jammed = false
    var harvested = 0
    var cookedCount = 0
    var merchantBuys = 0
    val redeemed = HashSet<String>()
    val packsBought = HashSet<String>()
    val merchantBought = HashSet<String>()
    /** Spore dust made while the game was closed, waiting in the "welcome back" window. */
    var pendingOffline = 0
    var offlineMs = 0L

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
    fun gardenSize() = 10 + 5 * upgrades[Data.UP_GARDEN]
    fun caveOpen() = upgrades[Data.UP_CAVE] > 0
    /** Total room for mushrooms: the meadow plus the cave. */
    fun capacity() = gardenSize() + (if (caveOpen()) 6 else 0)
    fun petAvailable(p: Int) = petOwned[p] - garden.count { it.pet == p }
    fun jobHolder(job: Int) = garden.firstOrNull { it.job == job && !it.away() }

    // ---------- Seeds & plots ----------

    fun plotDuration(i: Int): Long {
        val sd = plotSeed[i]; if (sd < 0) return 0L
        return (Data.SEEDS[sd].hours * 3_600_000f * (if (plotWater[i]) 0.7f else 1f)).toLong()
    }
    fun plotProgress(i: Int, now: Long = System.currentTimeMillis()): Float =
        if (plotSeed[i] < 0) 0f else ((now - plotAt[i]).toFloat() / plotDuration(i)).coerceIn(0f, 1f)
    fun plant(i: Int, seed: Int): Boolean {
        if (plotSeed[i] >= 0 || seeds[seed] <= 0) return false
        seeds[seed]--; plotSeed[i] = seed; plotAt[i] = System.currentTimeMillis(); plotWater[i] = false
        return true
    }
    fun water(i: Int): Boolean { if (plotSeed[i] < 0 || plotWater[i] || plotProgress(i) >= 1f) return false; plotWater[i] = true; return true }
    /** Harvests a ripe plot. Returns how many of the ingredient it gave (0 if not ripe). */
    fun harvest(i: Int): Int {
        if (plotSeed[i] < 0 || plotProgress(i) < 1f) return 0
        val n = 2 + (if (plotWater[i]) 1 else 0) + Random.nextInt(2)
        ingredients[Data.SEEDS[plotSeed[i]].ingredient] += n
        plotSeed[i] = -1; plotWater[i] = false
        harvested++
        return n
    }

    // ---------- Kitchen ----------

    fun canCook(r: Recipe): Boolean = r.ingredients.toList().groupingBy { it }.eachCount().all { (k, n) -> ingredients[k] >= n }
    fun cook(r: Recipe): Boolean {
        if (!canCook(r)) return false
        for (k in r.ingredients) ingredients[k]--
        foods[r.food]++
        cookedCount++
        return true
    }

    // ---------- Gifts: packs, codes, the merchant ----------

    /** Gives everything in a bundle. Returns a short list of what you got. */
    fun grant(b: Reward): String {
        val got = ArrayList<String>()
        if (b.dust > 0) { dust += b.dust; got.add("${b.dust} ✦") }
        for (d in b.decor) { decorOwned[d]++; got.add(Data.DECOR[d].name) }
        for (a in b.acc) { accOwned[a]++; got.add(Data.ACCESSORIES[a].name) }
        for (p in b.pets) { petOwned[p]++; got.add("${Data.PETS[p].name} pet") }
        for (t in b.themes) { themesOwned = themesOwned or (1 shl t); got.add("${Data.THEMES[t].name} theme") }
        for (f in b.foods) foods[f]++
        if (b.foods.isNotEmpty()) got.add("treats")
        for (i in b.ingredients) ingredients[i]++
        for (sd in b.seeds) seeds[sd]++
        return got.joinToString(", ")
    }

    /** Applies a real-money pack after Google Play confirms the purchase. */
    fun grantPack(p: Pack): String {
        var msg = grant(p.bundle)
        if (p.id == "event_boost") {
            val ev = Data.currentEvent()
            if (ev > 0) {
                for (i in Data.INGREDIENTS.indices) if (Data.INGREDIENTS[i].event == ev) ingredients[i] += 10
                for (d in Data.DECOR.indices) if (Data.DECOR[d].event == ev) decorOwned[d]++
                for (t in Data.THEMES.indices) if (Data.THEMES[t].event == ev) themesOwned = themesOwned or (1 shl t)
                msg += ", event ingredients, theme & decor"
            }
        }
        if (p.oneTime) packsBought.add(p.id + (if (p.eventOnly) "-" + Data.currentEvent() + "-" + Calendar.getInstance().get(Calendar.YEAR) else ""))
        addDiary("Opened the ${p.name}! 🎁")
        return msg
    }
    fun packOwned(p: Pack) = p.oneTime && packsBought.contains(p.id + (if (p.eventOnly) "-" + Data.currentEvent() + "-" + Calendar.getInstance().get(Calendar.YEAR) else ""))

    /** Redeems a gift code. Returns (ok, message). */
    fun redeem(raw: String): Pair<Boolean, String> {
        val code = raw.trim().uppercase()
        val b = Data.CODES[code] ?: return Pair(false, "That code doesn't exist")
        if (!redeemed.add(code)) return Pair(false, "You already used that code")
        val got = grant(b)
        addDiary("Redeemed the code $code 🎁")
        return Pair(true, "Code redeemed! You got: $got")
    }

    /** Buys merchant offer k today. Returns a message, or null if it can't be bought. */
    fun buyOffer(day: Int, k: Int): String? {
        val o = Data.merchantOffers(day).getOrNull(k) ?: return null
        val key = "$day-$k"
        if (merchantBought.contains(key) || dust < o.price) return null
        dust -= o.price
        merchantBought.add(key); merchantBuys++
        return when (o.kind) {
            0 -> { ingredients[o.item] += o.qty; "${o.qty} ${Data.INGREDIENTS[o.item].name}" }
            1 -> { seeds[o.item] += o.qty; "${o.qty} ${Data.SEEDS[o.item].name}" }
            2 -> { accOwned[o.item]++; Data.ACCESSORIES[o.item].name }
            else -> {   // mystery egg: a pet you don't have yet, or a pile of goodies
                val missing = Data.PETS.indices.filter { Data.PETS[it].event == 0 && Data.PETS[it].special == 0 && petOwned[it] == 0 }
                if (missing.isNotEmpty()) { val p = missing.random(); petOwned[p]++; "a ${Data.PETS[p].name} hatched from the egg! 🥚" }
                else { dust += 400; seeds[Random.nextInt(seeds.size)] += 3; "400 ✦ and seeds from the egg! 🥚" }
            }
        }
    }

    // ---------- Stickers ----------

    /** Awards any stickers you've newly earned and returns them. */
    fun checkStickers(): List<Int> {
        val now = ArrayList<Int>()
        fun cond(i: Int): Boolean = when (i) {
            0 -> brewed > 0
            1 -> shinyFound.any { it }
            2 -> friendship.values.any { it >= 8f }
            3 -> usedBedtime
            4 -> jammed
            5 -> caveOpen()
            6 -> garden.any { it.pet >= 0 }
            7 -> harvested > 0
            8 -> cookedCount > 0
            9 -> merchantBuys > 0
            10 -> questStep[1] >= 3
            11 -> questStep[2] >= 3
            12 -> questStep[3] >= 3
            13 -> mysteryClaimed > 0
            14 -> crosses > 0
            15 -> contestWins > 0
            16 -> expDone > 0
            17 -> discovered.indices.any { discovered[it] && Data.SPECIES[it].tier == 4 }
            18 -> discovered.indices.any { discovered[it] && Data.SPECIES[it].tier == 3 }
            else -> discoveredCount() >= 25
        }
        for (i in stickers.indices) if (!stickers[i] && cond(i)) { stickers[i] = true; now.add(i) }
        return now
    }

    // ---------- Friends ----------

    private fun pairKey(a: Shroom, b: Shroom) = if (a.id < b.id) "${a.id}-${b.id}" else "${b.id}-${a.id}"

    /** Two mushrooms spent time together. Returns true when they just became best friends. */
    fun befriend(a: Shroom, b: Shroom): Boolean {
        val k = pairKey(a, b)
        val pts = (friendship[k] ?: 0f) + 1f
        friendship[k] = pts
        if (pts >= 8f && a.bestie < 0 && b.bestie < 0) {
            a.bestie = b.id; b.bestie = a.id
            addDiary("${a.name} and ${b.name} became best friends! 💕")
            return true
        }
        return false
    }

    /** Clean up references to a mushroom that's leaving. */
    fun forget(s: Shroom) {
        for (o in garden) if (o.bestie == s.id) o.bestie = -1
        friendship.keys.filter { it.split("-").contains(s.id.toString()) }.forEach { friendship.remove(it) }
    }

    // ---------- Recipe notebook ----------

    fun noteBrew(a: Int, b: Int, species: Int) { tried[if (a <= b) "$a,$b" else "$b,$a"] = species }
    fun triedPair(a: Int, b: Int): Int = tried[if (a <= b) "$a,$b" else "$b,$a"] ?: -1

    // ---------- Event quest ----------

    private fun eventYear(): Int {
        val c = Calendar.getInstance()
        return if (c.get(Calendar.MONTH) == Calendar.JANUARY) c.get(Calendar.YEAR) - 1 else c.get(Calendar.YEAR)
    }

    /** Current event's quest step (0..3, 3 = done), resetting each year. -1 when no event is on. */
    fun questNow(): Int {
        val ev = Data.currentEvent()
        if (ev == 0) return -1
        val y = eventYear()
        if (questYear[ev] != y) { questYear[ev] = y; questStep[ev] = 0; questCount = 0 }
        return questStep[ev]
    }

    fun questFed(food: Int) {
        val ev = Data.currentEvent(); val st = questNow()
        if (st < 0 || st >= 3) return
        val step = Data.QUESTS[ev].steps[st]
        if (step.type == 2 && step.target == food) questCount++
    }

    /** Moves the quest along if the current step is done. Returns a message to show, or null. */
    fun updateQuest(): String? {
        val st = questNow()
        if (st < 0 || st >= 3) return null
        val ev = Data.currentEvent()
        val q = Data.QUESTS[ev]
        val step = q.steps[st]
        val done = when (step.type) {
            0 -> discovered[step.target]
            1 -> placed.any { it.kind == step.target }
            else -> questCount >= step.count
        }
        if (!done) return null
        questStep[ev]++; questCount = 0
        if (questStep[ev] >= q.steps.size) {
            val newPet = petOwned[q.pet] == 0
            if (newPet) petOwned[q.pet] = 1
            dust += 250
            addDiary("${q.title}: ${q.outro}")
            return "🎉 ${q.title} complete! " + (if (newPet) "You got a ${Data.PETS[q.pet].name} pet! " else "") + "+250 ✦"
        }
        return "✓ ${step.text}. Next: ${q.steps[questStep[ev]].text}"
    }

    // ---------- Weekly mystery recipe ----------

    /** Returns the reward if this species is the week's mystery and it hasn't been claimed yet. */
    fun claimMystery(species: Int): Int {
        val wk = Data.weekKey()
        if (mysteryClaimed == wk || Data.mysteryFor(wk) != species) return 0
        mysteryClaimed = wk
        dust += 160
        addDiary("Solved the weekly mystery recipe: ${Data.SPECIES[species].name}! 🔮")
        return 160
    }

    // ---------- Backup ----------

    fun exportCode(): String {
        save()
        val raw = prefs.getString("save", "") ?: ""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(raw.toByteArray()) }
        return "SHROOM1:" + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Replaces the saved lab with a backup code. The game should restart afterwards. */
    fun importCode(code0: String): Boolean = try {
        val code = code0.trim()
        if (!code.startsWith("SHROOM1:")) false else {
            val bytes = android.util.Base64.decode(code.removePrefix("SHROOM1:"), android.util.Base64.DEFAULT)
            val raw = java.util.zip.GZIPInputStream(bytes.inputStream()).use { String(it.readBytes()) }
            val o = JSONObject(raw)
            if (!o.has("garden")) false else {
                prefs.edit().putString("save", raw).commit()
                noSave = true
                true
            }
        }
    } catch (_: Exception) { false }
    fun home() = garden.filter { !it.away() }
    fun accAvailable(i: Int) = accOwned[i] - garden.count { s -> s.wear.contains(i) }

    // ---------- Time, weather ----------

    companion object {
        /** The meadow is twice as wide as the screen; gx runs from 0 to WORLD. */
        const val WORLD = 2f
        /** Gaps longer than this count as time away. */
        const val OFFLINE_MIN_HOURS = 5f / 60f
        /** Most spore dust you can collect while away. */
        const val OFFLINE_CAP = 250
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
        /** Test runs can force rain on (true) or off (false). */
        var forceRain: Boolean? = null
        fun raining() = forceRain ?: (rainy() && hour() !in 15f..18f)
        fun rainbow() = forceRain == null && rainy() && hour() in 15f..18f
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
            s.family[0] = "${a.name}|${a.species}"; s.family[1] = "${b.name}|${b.species}"
            s.family[2] = a.family[0]; s.family[3] = a.family[1]
            s.family[4] = b.family[0]; s.family[5] = b.family[1]
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
        // a long gap means the game was closed: production goes to the welcome-back pool instead (capped)
        val offline = hours > OFFLINE_MIN_HOURS
        var offlineMade = 0f
        if (offline) offlineMs += (hours * 3_600_000f).toLong()
        val rainMul = if (rainy()) 1.5f else 1f
        val charm = max(0.4f, 1f - 0.06f * placed.size)
        val gardener = jobHolder(Data.JOB_GARDEN) != null
        val tucked = now < tuckedUntil
        jobHolder(Data.JOB_FORAGE)?.let { f ->
            forageAcc += hours
            while (forageAcc >= 2f) {
                forageAcc -= 2f
                val i = intArrayOf(Data.WATER, Data.SOIL, Data.EMBER, Data.FROST, Data.SULFUR, Data.PETAL, Data.HONEY, Data.SPARK, Data.CRYSTAL, Data.IRON).random()
                ingredients[i]++
                events.add("${f.name} foraged some ${Data.INGREDIENTS[i].name} 🧺")
            }
        }
        for (s in garden) {
            if (!s.away() && s.area == 0 && Random.nextFloat() < hours * 0.05f) {
                val sd = Random.nextInt(Data.SEEDS.size - (if (Random.nextFloat() < 0.8f) 2 else 0))
                seeds[sd]++
                events.add("${s.name} found a ${Data.SEEDS[sd].name}! Plant it in the veggie patch 🌱")
            }
            if (s.away()) {
                // Out exploring: they forage for themselves and grow a little
                s.xp += 8f * hours
                if (now >= s.awayUntil) returnHome(s)
                continue
            }
            val growing = s.hunger > 0f
            val moodMul = when (s.mood()) { 0 -> 1.4f; 1 -> 1f; 2 -> 0.4f; else -> 0.6f }
            val glowing = Data.SPECIES[s.species].glow || s.variant == 2
            val caveMul = if (s.area == 1 && glowing) 1.5f else 1f
            val bestieNear = s.bestie >= 0 && garden.any { it.id == s.bestie && !it.away() && it.area == s.area }
            val weather = if (s.area == 1) 1f else rainMul
            if (growing) s.xp += 20f * moodMul * weather * caveMul * (if (tucked) 1.3f else 1f) * hours
            s.hunger = max(0f, s.hunger - 9f * hours * (if (s.trait == 3) 1.3f else 1f) * (if (tucked) 0.7f else 1f))
            s.happy = max(0f, s.happy - 5f * charm * hours * (if (s.trait == 5) 0.6f else 1f) *
                    (if (gardener) 0.7f else 1f) * (if (bestieNear) 0.75f else 1f) * (if (s.area == 1 && glowing) 0.7f else 1f))
            val rate = floatArrayOf(2f, 6f, 12f, 20f)[s.stage()] * Data.TIER_MUL[s.tier()] *
                    (if (s.hunger <= 0f) 0.5f else 1f) * (if (s.shiny) 2f else 1f)
            if (offline) offlineMade += rate * hours
            else s.dust = min(s.dustCap(), s.dust + rate * hours)

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
            if (offline && offlineMade > 0f) addOffline(offlineMade)
    }

    fun addOffline(made: Float) {
        pendingOffline = min(OFFLINE_CAP, pendingOffline + made.toInt())
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
        val dustFound = ((8 + z.hours * 10f) * (1 + s.stage() * 0.3f)).toInt()
        dust += dustFound
        s.xp += z.hours * 15f
        s.hunger = max(s.hunger, 60f)
        s.happy = min(100f, s.happy + 20f)
        val parts = haul.indices.filter { haul[it] > 0 }.map { "${haul[it]} ${Data.INGREDIENTS[it].name}" }.toMutableList()
        parts.add("$dustFound ✦")
        if (Random.nextFloat() < 0.35f) { val sd = Random.nextInt(Data.SEEDS.size); seeds[sd]++; parts.add("a ${Data.SEEDS[sd].name}") }
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
        s.gx = if (Random.nextBoolean()) 0.06f else LabState.WORLD - 0.06f
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
            0 -> Request(0, 1, known.random(), 0, 70)
            1 -> Request(1, 1 + Random.nextInt(2), 0, stat(1), 95)
            2 -> Request(2, 3 + Random.nextInt(4), 0, stat(2), 60)
            3 -> Request(3, 5 + Random.nextInt(6), 0, stat(3), 50)
            4 -> Request(4, placed.size + 2 + Random.nextInt(3), 0, 0, 70)
            5 -> Request(5, 1 + Random.nextInt(2), 0, stat(5), 110)
            6 -> Request(6, 1, 0, stat(6), 85)
            else -> Request(7, 1 + Random.nextInt(2), 0, stat(7), 85)
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

    fun giftText(i: Int) = arrayOf("40 ✦", "3 Compost", "65 ✦", "Ingredient pack", "100 ✦", "Moon Jelly + Gold", "160 ✦ + a hat")[i]

    fun claimGift(): String {
        val i = giftIndex()
        when (i) {
            0 -> dust += 40
            1 -> foods[1] += 3
            2 -> dust += 65
            3 -> repeat(6) { ingredients[Random.nextInt(8)]++ }
            4 -> dust += 100
            5 -> { foods[4]++; ingredients[Data.GOLD]++ }
            else -> { dust += 160; accOwned[Random.nextInt(9)]++ }
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
        dust += intArrayOf(160, 80, 40, 15)[place - 1]
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
                put("fam", JSONArray(s.family.toList())); put("bf", s.bestie); put("pt", s.pet); put("job", s.job); put("ar", s.area)
            })
        }
        o.put("garden", g)
        o.put("diary", JSONArray(diary))
        o.put("pets", ints(petOwned))
        o.put("tried", JSONObject(tried as Map<*, *>))
        o.put("fizz", ints(fizzles))
        o.put("friends", JSONObject(friendship.mapValues { it.value.toDouble() } as Map<*, *>))
        o.put("tucked", tuckedUntil)
        o.put("qy", ints(questYear)); o.put("qs", ints(questStep)); o.put("qc", questCount)
        o.put("myst", mysteryClaimed); o.put("forage", forageAcc.toDouble())
        o.put("seeds", ints(seeds)); o.put("plotSeed", ints(plotSeed)); o.put("plotAt", JSONArray(plotAt.toList())); o.put("plotWater", bools(plotWater))
        o.put("stickers", bools(stickers)); o.put("stickerPos", JSONArray(stickerPos.map { if (it.isNaN()) -1.0 else it.toDouble() }))
        o.put("bedtimeUsed", usedBedtime); o.put("jammed", jammed); o.put("harvested", harvested); o.put("cooked", cookedCount)
        o.put("merchantBuys", merchantBuys); o.put("offline", pendingOffline); o.put("offlineMs", offlineMs)
        o.put("redeemed", JSONArray(redeemed.toList())); o.put("packs", JSONArray(packsBought.toList())); o.put("merchantBought", JSONArray(merchantBought.toList()))
        if (noSave) return
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
            readInts(o, "pets", petOwned)
            o.optJSONObject("tried")?.let { t -> for (k in t.keys()) tried[k] = t.optInt(k).coerceIn(0, Data.SPECIES.size - 1) }
            readInts(o, "fizz", fizzles)
            o.optJSONObject("friends")?.let { t -> for (k in t.keys()) friendship[k] = t.optDouble(k).toFloat() }
            tuckedUntil = o.optLong("tucked")
            readInts(o, "qy", questYear); readInts(o, "qs", questStep); questCount = o.optInt("qc")
            mysteryClaimed = o.optInt("myst"); forageAcc = o.optDouble("forage", 0.0).toFloat()
            readInts(o, "seeds", seeds); readInts(o, "plotSeed", plotSeed); readBools(o, "plotWater", plotWater)
            o.optJSONArray("plotAt")?.let { a -> for (i in plotAt.indices) plotAt[i] = a.optLong(i, 0L) }
            for (i in plotSeed.indices) if (plotSeed[i] >= Data.SEEDS.size) plotSeed[i] = -1
            readBools(o, "stickers", stickers)
            o.optJSONArray("stickerPos")?.let { a -> for (i in stickerPos.indices) { val v = a.optDouble(i, -1.0); stickerPos[i] = if (v < 0) Float.NaN else v.toFloat() } }
            usedBedtime = o.optBoolean("bedtimeUsed"); jammed = o.optBoolean("jammed"); harvested = o.optInt("harvested"); cookedCount = o.optInt("cooked")
            merchantBuys = o.optInt("merchantBuys"); pendingOffline = o.optInt("offline"); offlineMs = o.optLong("offlineMs")
            o.optJSONArray("redeemed")?.let { a -> for (i in 0 until a.length()) redeemed.add(a.optString(i)) }
            o.optJSONArray("packs")?.let { a -> for (i in 0 until a.length()) packsBought.add(a.optString(i)) }
            o.optJSONArray("merchantBought")?.let { a -> for (i in 0 until a.length()) merchantBought.add(a.optString(i)) }
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
                    s.optJSONArray("fam")?.let { f -> for (k in 0..5) sh.family[k] = f.optString(k, "") }
                    sh.bestie = s.optLong("bf", -1L)
                    sh.pet = s.optInt("pt", -1).coerceIn(-1, Data.PETS.size - 1)
                    sh.job = s.optInt("job", 0).coerceIn(0, Data.JOBS.size - 1)
                    sh.area = s.optInt("ar", 0).coerceIn(0, 1)
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
