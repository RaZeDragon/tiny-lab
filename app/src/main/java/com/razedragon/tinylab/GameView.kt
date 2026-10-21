package com.razedragon.tinylab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import com.razedragon.tinylab.Sound.Fx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shroomology: brew ingredients into cute mushroom monsters, raise them in your garden,
 * and crossbreed grown-ups to discover rare species. Everything is drawn with Canvas.
 */
class GameView(context: Context) : View(context) {

    private enum class Tab { GARDEN, LAB, DEX, JOURNAL, SHOP }

    private class Button(
        val id: String, val rect: RectF, val label: String, val style: Int = STYLE_NORMAL,
        val enabled: Boolean = true, val custom: ((Canvas, RectF) -> Unit)? = null, val action: () -> Unit,
    )
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
                           val maxLife: Float, val color: Int, val type: Int, var tx: Float = 0f, var ty: Float = 0f)
    private class Toast(val text: String, val color: Int)
    private class PetRef(val owner: Shroom)
    private class PlotRef(val i: Int)
    private object PatchSign
    private class Popup(val species: Int, val isNew: Boolean, val shroomId: Long, val crossed: Boolean,
                        val shiny: Boolean, val quality: Float, val missed: Int = -1)

    companion object {
        private const val STYLE_NORMAL = 0
        private const val STYLE_PRIMARY = 1
        private const val STYLE_DANGER = 2
        private const val STYLE_TAB_ON = 3
        private const val STYLE_CARD = 4
        private const val STYLE_PICKED = 5

        private const val P_SPARK = 0
        private const val P_HEART = 1
        private const val P_DUST = 2
        private const val P_BUBBLE = 3
        private const val P_Z = 4
        private const val P_NOTE = 5

        // What a mushroom is doing
        private const val ACT_SIT = 1
        private const val ACT_SPLASH = 2
        private const val ACT_NAP = 3
        private const val ACT_SNIFF = 4
        private const val ACT_INSIDE = 5
        private const val ACT_DANCE = 6
        private const val ACT_CHAT = 7
        private const val ACT_SLEEP = 8
        private const val ACT_GATHER = 9
        private const val ACT_KICK = 10
        private const val ACT_SWING = 11
        private const val ACT_SLIDE = 12
        private const val ACT_BOUNCE = 13
        private const val ACT_CAMPFIRE = 14
        private const val ACT_JAM = 15
        private const val ACT_PUDDLE = 16
        private const val ACT_SNOWMAN = 17

        private val BG_TOP = Color.rgb(36, 24, 66)
        private val BG = Color.rgb(26, 18, 48)
        private val PANEL = Color.rgb(48, 34, 86)
        private val PANEL_LIGHT = Color.rgb(66, 48, 112)
        private val ACCENT = Color.rgb(255, 120, 170)
        private val DUST = Color.rgb(255, 214, 110)
        private val MINT = Color.rgb(130, 255, 200)
        private val TEXT_DIM = Color.argb(170, 255, 255, 255)

        private const val CROSS_COST = 25
        private const val BREW_TIME = 5f
        private const val STYLE_GHOST = 6

        // Tutorial steps
        private const val T_WELCOME = 0
        private const val T_GO_LAB = 1
        private const val T_DRAG = 2
        private const val T_BREW = 3
        private const val T_STIR = 4
        private const val T_REVEAL = 5
        private const val T_FEED = 6
        private const val T_DONE = 7

        // Face expressions
        private const val E_HEART = 1
        private const val E_SPARKLE = 2
        private const val E_BLISS = 3
        private const val E_DIZZY = 4
        private const val E_CHOMP = 5
        private const val E_GIGGLE = 6
        private const val STIR_GOAL = (2 * Math.PI * 6).toFloat()   // six full circles = perfect brew
        private const val MAX_PLACED = 20
        private const val REST_MS = 20 * 60 * 1000L
    }

    private val dp = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("tinylab_settings", Context.MODE_PRIVATE)
    private val lab = LabState(context)
    private val sound = Sound(context)

    private var tab = Tab.GARDEN
    private var lastFrame = 0L
    private var realDt = 0f   // unclamped frame time, so the splash lasts the same on slow devices
    private var t = 0f                 // seconds since launch, for animation
    private var tickAcc = 0f
    private var saveAcc = 0f
    private var dirty = false

    // Garden
    private var selected: Long = -1L
    private var feeding = false
    private var styling = false
    private var moreOpen = false        // the MORE menu on the info panel
    private var jobbing = false         // picking a job
    private var familyFor = -1L         // family tree overlay
    private var area = 0                // 0 meadow, 1 glow cave
    private fun local() = lab.home().filter { it.area == area }
    private val caveCrystals = listOf(Placed(5, 0.07f, 0.08f), Placed(5, 0.93f, 0.12f), Placed(5, 0.5f, 0.02f),
        Placed(5, 0.05f, 0.9f), Placed(5, 1.05f, 0.85f), Placed(5, 1.45f, 0.06f), Placed(5, 1.95f, 0.2f), Placed(5, 1.6f, 0.92f))
    private var exploring = false
    private var confirmRelease = false

    // Lab
    private var labMode = 0             // 0 brew, 1 crossbreed
    private val slots = intArrayOf(-1, -1, -1)
    private val picks = ArrayList<Long>()
    private var brewing = 0f
    private var popup: Popup? = null

    // Dex / shop
    private var dexPick = -1

    // Stirring mini-game while brewing
    private var stirring = false
    private var stirTotal = 0f
    private var stirAngle = 0f
    private var lastStirA = 0f
    private var stirSound = 0f
    private var flashT = 0f
    private var popupT = 0f

    // Decorating the garden
    private var decorating = false
    private var dragPlaced = -1

    // Drag and drop: 1 = ingredient, 2 = food, 3 = a mushroom being carried,
    //                4 = decoration from the tray, 5 = a placed decoration
    private var dragKind = 0
    private var dragIndex = -1
    private var dragShroom = -1L
    private var dragging = false
    private var panning = false
    private var panMoved = false
    private var panStartCam = 0f
    private var panLastX = 0f
    private var panLastT = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f

    // Touch gestures on mushrooms
    private var pointerDown = false
    private var downT = 0f
    private var petting = false
    private var hugDone = false
    private var revs = 0
    private var lastDxSign = 0
    private var lastMoveX = 0f
    private var petAcc = 0f
    private var lastPurr = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var prevT = 0L
    private var velX = 0f
    private var velY = 0f
    private var shopTab = 0
    private var journalTab = 0
    private var contestPick = -1L
    private var photoMode = false
    private var capturing = false

    // Settings: 0 = all sound, 1 = no music, 2 = muted
    private var soundMode = prefs.getInt("soundMode", 0)   // older builds' single sound switch
    private var settingsOpen = false
    private var vibrateOn = prefs.getBoolean("vibrate", true)
    private var tutStep = -1
    private var tutFeedBase = 0

    // UI
    private var buttons: List<Button> = emptyList()
    private var pressedId: String? = null
    private val toasts = ArrayDeque<Toast>()
    private var toastTime = 0f
    private val particles = ArrayList<Particle>()
    private val blinkAt = HashMap<Long, Float>()

    // Paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = loadFont()
    }
    private val path = Path()

    init {
        keepScreenOn = false
        applySoundMode()
        lab.tick()
        lab.visit()
        tutStep = prefs.getInt("tutStep", if (lab.brewed > 0) -1 else T_WELCOME)
        waveHello()
        if (tutStep < 0) eventWelcome()
    }

    private fun applySoundMode() {
        sound.sfxOn = prefs.getBoolean("sfx", soundMode < 2)
        sound.musicOn = prefs.getBoolean("music", soundMode == 0)
    }

    private fun shroom(id: Long) = lab.garden.firstOrNull { it.id == id }

    // ---------- Layout helpers ----------

    private val topBar get() = 64f * dp
    private val navH get() = 78f * dp
    private val navTop get() = height - navH

    // ---------- Lifecycle ----------

    fun onAppPause() {
        stopShakeSensor()
        lab.tick(); lab.save()
        updateWidget()
        sound.onAppPause()
        try { scheduleReminders() } catch (_: Exception) {}
    }

    fun onAppResume() {
        lab.tick()
        lab.visit()
        startShakeSensor()
        waveHello()
        lastAmbient = ""
        
        sound.onAppResume()
        try { Notifier.cancelAll(context) } catch (_: Exception) {}
    }

    fun onAppDestroy() {
        lab.save()
        sound.release()
    }

    fun onBack(): Boolean {
        when {
            settingsOpen -> settingsOpen = false
            popup != null -> popup = null
            photoMode -> photoMode = false
            feeding -> feeding = false
            previewKind >= 0 -> { previewKind = -1; previewFromOffer = false }
            familyFor >= 0 -> familyFor = -1
            offerPack >= 0 -> closeOffer()
            growPick >= 0 -> growPick = -1
            merchantOpen -> merchantOpen = false
            styling -> styling = false
            jobbing -> jobbing = false
            moreOpen -> moreOpen = false
            exploring -> exploring = false
            decorating -> decorating = false
            selected >= 0 -> { selected = -1; confirmRelease = false }
            tab != Tab.GARDEN -> tab = Tab.GARDEN
            else -> return false
        }
        return true
    }

    private fun markDirty() { dirty = true }

    private fun toast(msg: String, color: Int = Color.WHITE) {
        toasts.addLast(Toast(msg, color))
        if (toasts.size == 1) toastTime = 2.4f
    }

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        if (!vibrateOn) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, 60))
        else v.vibrate(ms)
    }

    // ---------- Game actions ----------

    private fun gardenFull() = lab.garden.size >= lab.capacity()

    private fun reserved(i: Int) = slots.take(slotCount()).count { it == i }
    private fun slotCount() = if (lab.upgrades[Data.UP_FLASK] > 0) 3 else 2
    private fun firstEmptySlot() = (0 until slotCount()).firstOrNull { slots[it] < 0 } ?: -1
    private fun slotsFull() = (0 until slotCount()).all { slots[it] >= 0 }

    private fun startBrew() {
        if (brewing > 0f) return
        if (!slotsFull()) return
        if (gardenFull()) { sound.play(Fx.DENIED); toast("Your garden is full! Release a mushroom first", ACCENT); return }
        beginBrew()
    }

    private fun beginBrew() {
        brewing = BREW_TIME
        stirTotal = 0f
        sound.play(Fx.BUBBLE)
        toast("Stir! Swipe in circles around the flask", MINT)
    }

    private fun quality() = (stirTotal / STIR_GOAL).coerceIn(0f, 1f)

    private fun stars(q: Float): String {
        val n = when { q >= 1f -> 3; q >= 0.6f -> 2; q >= 0.25f -> 1; else -> 0 }
        return "★".repeat(n) + "☆".repeat(3 - n)
    }

    /** The special species the current flask or crossbreed is aiming for, or -1. */
    private fun specialTarget(): Int {
        if (labMode >= 2) return -1
        if (labMode == 0) {
            if (slotCount() < 3 || !slotsFull()) return -1
            return Data.rareRecipe3(slots[0], slots[1], slots[2])
        }
        if (picks.size < 2) return -1
        val a = shroom(picks[0]) ?: return -1
        val b = shroom(picks[1]) ?: return -1
        return Data.cross(a.species, b.species)
    }

    /** Odds that the special result works: base + stirring + happy parents + knowing the recipe. */
    private fun successChance(target: Int, q: Float = quality()): Float {
        var ch = Data.baseChance(target) + 0.30f * q
        if (lab.discovered[target]) ch += 0.10f
        if (lab.jobHolder(Data.JOB_STIR) != null) ch += 0.10f
        if (labMode == 1 && picks.size == 2) {
            val hap = picks.mapNotNull { shroom(it)?.happy }.average().toFloat()
            ch += 0.10f * (hap / 100f).coerceIn(0f, 1f)
        }
        return ch.coerceIn(0.05f, 0.95f)
    }

    private fun finishBrew() {
        val n = slotCount()
        for (k in 0 until n) lab.ingredients[slots[k]]--
        val target = specialTarget()
        var missed = -1
        var sp = if (n == 3) Data.brew3(slots[0], slots[1], slots[2]) else Data.brew(slots[0], slots[1])
        if (target >= 0 && (testForceFail || Random.nextFloat() > successChance(target))) {
            missed = target
            sp = Data.brew3Common(slots[0], slots[1], slots[2])
            lab.fizzles[target]++
        }
        if (n == 2) lab.noteBrew(slots[0], slots[1], sp)
        for (k in 0..2) slots[k] = -1
        hatch(sp, crossed = false, missed = missed)
        lab.brewed++
        if (lab.tutorial < 2) lab.tutorial = 2
    }

    private fun startCross() {
        if (brewing > 0f) return
        if (picks.size < 2) return
        if (gardenFull()) { sound.play(Fx.DENIED); toast("Your garden is full! Release a mushroom first", ACCENT); return }
        if (lab.dust < CROSS_COST) { sound.play(Fx.DENIED); toast("Crossbreeding costs $CROSS_COST ✦ spore dust", ACCENT); return }
        beginBrew()
    }

    private fun finishCross() {
        val target0 = specialTarget()
        val chance = if (target0 >= 0) successChance(target0) else 0f
        val a = shroom(picks[0]); val b = shroom(picks[1])
        picks.clear()
        if (a == null || b == null) return
        lab.dust -= CROSS_COST
        val now = System.currentTimeMillis()
        val rest = (REST_MS * floatArrayOf(1f, 0.6f, 0.3f)[lab.upgrades[Data.UP_REST].coerceIn(0, 2)]).toLong()
        a.restUntil = now + rest; b.restUntil = now + rest
        var sp = Data.cross(a.species, b.species)
        var missed = -1
        if (sp >= 0 && Random.nextFloat() > chance) { missed = sp; lab.fizzles[sp]++; sp = -1 }
        if (sp < 0) {
            val parents = listOf(a.species, b.species).filter { !Data.offSeason(it) }
            sp = if (parents.isNotEmpty()) parents.random()
                 else Data.BASE_SPECIES.filter { Data.SPECIES[it].tier == 1 }.random()
        }
        lab.crosses++
        hatch(sp, crossed = true, a, b, missed)
    }

    private fun hatch(sp: Int, crossed: Boolean, pa: Shroom? = null, pb: Shroom? = null, missed: Int = -1) {
        val q = quality()
        val isNew = !lab.discovered[sp]
        lab.discovered[sp] = true
        // A good stir gives a head start, and a chance at a rare shiny mushroom
        val shiny = Random.nextFloat() < 0.02f + 0.25f * q * q
        if (shiny) lab.shinyFound[sp] = true
        val s = lab.newShroom(sp, q * 30f, shiny, pa, pb)
        lab.addDiary(when {
            shiny -> "A shiny ${Data.SPECIES[sp].name} named ${s.name} sprouted! ✨"
            isNew && Data.SPECIES[sp].event > 0 -> "Discovered a ${Data.EVENTS[Data.SPECIES[sp].event]} species: ${Data.SPECIES[sp].name}! Named it ${s.name}."
            isNew -> "Discovered a new species: ${Data.SPECIES[sp].name}! Named it ${s.name}."
            pa != null && pb != null -> "${pa.name} and ${pb.name} had a baby ${Data.SPECIES[sp].name}: ${s.name}!"
            else -> "${s.name} the ${Data.SPECIES[sp].name} sprouted in the lab."
        })
        if (q >= 1f) lab.perfect++
        s.gx = (camX + (Random.nextFloat() - 0.5f) * 0.5f).coerceIn(0.08f, LabState.WORLD - 0.08f); s.tx = s.gx; s.tz = s.gz
        lab.garden.add(s)
        popup = Popup(sp, isNew, s.id, crossed, shiny, q, missed)
        lab.claimMystery(sp).let { if (it > 0) toast("🔮 You solved this week's mystery recipe! +$it ✦", DUST) }
        if (missed >= 0) lab.addDiary("Tried for a ${if (lab.discovered[missed]) Data.SPECIES[missed].name else Data.TIER_NAMES[Data.SPECIES[missed].tier].lowercase() + " mushroom"}, but it fizzled into ${s.name} the ${Data.SPECIES[sp].name}.")
        popupT = 0f
        flashT = 0.35f
        sound.play(Fx.BREW_DONE)
        buzz(50)
        markDirty()
    }

    /** Called when the popup's mystery silhouette turns into the real mushroom. */
    private fun reveal(p: Popup) {
        sound.play(if (p.isNew || p.shiny) Fx.DISCOVER else Fx.GROW)
        buzz(if (p.isNew || p.shiny) 70 else 30)
        burst(width / 2f, height * 0.45f, if (p.shiny) Color.WHITE else if (p.isNew) DUST else MINT, if (p.isNew || p.shiny) 60 else 30, P_SPARK)
    }

    private fun feed(s: Shroom, f: Int) {
        val food = Data.FOODS[f]
        if (lab.foods[f] <= 0) { sound.play(Fx.DENIED); toast("Out of ${food.name}. Buy more in the Shop", ACCENT); return }
        if (s.hunger >= 95f) { sound.play(Fx.DENIED); toast("${Data.SPECIES[s.species].name} is too full!"); return }
        val fav = Data.SPECIES[s.species].favFood == f
        val before = s.stage()
        lab.foods[f]--
        lab.feeds++
        lab.questFed(f)
        if (f == Data.PETAL_TEA) addBond(s, 5f)
        s.hunger = min(100f, s.hunger + food.hunger)
        s.xp += food.xp * (if (fav) 2f else 1f) * (if (s.trait == 3) 1.2f else 1f)
        s.happy = min(100f, s.happy + food.happy + if (fav) 15f else 0f)
        sound.play(Fx.CHOMP, 0.9f, voicePitch(s))
        val (x, y) = shroomScreen(s)
        burst(x, y - shroomSize(s) * 0.3f, food.color, 12, P_SPARK)
        s.jiggle = 0.8f
        addBond(s, 1f)
        express(s, if (fav) E_HEART else E_BLISS, if (fav) 2.5f else 1.2f)
        if (fav) {
            toast("Yum! That's its favorite food!", ACCENT)
            burst(x, y - shroomSize(s), ACCENT, 6, P_HEART)
        }
        if (s.want == 1 && s.wantFood == f) fulfillWant(s)
        checkGrowth(s, before)
        if (lab.tutorial == 3) { lab.tutorial = 4; toast("You're a mushroom scientist now! 🍄", MINT) }
        markDirty()
    }

    private fun checkGrowth(s: Shroom, before: Int) {
        val after = s.stage()
        if (after > before) {
            sound.play(Fx.GROW)
            buzz(40)
            toast("${Data.SPECIES[s.species].name} grew into ${Data.STAGE_NAMES[after]}!", MINT)
            val idx = lab.garden.indexOf(s)
            if (idx >= 0) { val (x, y) = shroomPos(idx); burst(x, y - 30f * dp, MINT, 30, P_SPARK) }
            if (after == 2) toast("Grown mushrooms can crossbreed in the Lab!", DUST)
            if (after >= 2) lab.addDiary("${s.name} grew into ${if (after == 2) "a Grown" else "an Elder"} ${Data.SPECIES[s.species].name}.")
        }
    }

    private fun pet(s: Shroom) {
        val now = System.currentTimeMillis()
        if (now - s.petAt < 45_000L) { toast("It's still happy from the last pat"); return }
        s.petAt = now
        s.happy = min(100f, s.happy + 15f)
        sound.play(Fx.PET)
        val (x, y) = shroomPos(lab.garden.indexOf(s))
        burst(x, y - 50f * dp, ACCENT, 7, P_HEART)
        markDirty()
    }

    private fun release(s: Shroom) {
        val reward = (8 + (s.xp / 6f).toInt() * Data.TIER_MUL[s.tier()]) * (if (s.shiny) 2 else 1)
        lab.forget(s)
        lab.garden.remove(s)
        lab.dust += reward
        selected = -1; confirmRelease = false; feeding = false
        sound.play(Fx.COIN)
        toast("Released into the wild. +$reward ✦", DUST)
        markDirty()
    }

    private fun collect(s: Shroom, idx: Int) {
        val amount = s.dust.toInt()
        if (amount <= 0) return
        s.dust -= amount
        lab.dust += amount
        val (x, y) = shroomPos(idx)
        flyDust(x, y - 70f * dp, min(12, 2 + amount / 4))
        sound.play(Fx.COIN)
        markDirty()
    }

    private fun buy(kind: Int, i: Int, n: Int) {
        val cost = (if (kind == 0) Data.INGREDIENTS[i].cost else Data.FOODS[i].cost) * n
        if (lab.dust < cost) { sound.play(Fx.DENIED); notEnoughDust(); return }
        lab.dust -= cost
        if (kind == 0) lab.ingredients[i] += n else lab.foods[i] += n
        sound.play(Fx.COIN)
        markDirty()
    }

    // ---------- Particles ----------

    private fun burst(x: Float, y: Float, color: Int, n: Int, type: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 6.283f
            val sp = (if (type == P_HEART) 40f else 80f + Random.nextFloat() * 180f) * dp
            val life = if (type == P_HEART) 1.3f else 0.5f + Random.nextFloat() * 0.5f
            particles.add(Particle(x, y, cos(a) * sp, sin(a) * sp - if (type == P_HEART) 80f * dp else 0f,
                life, life, color, type))
        }
    }

    private fun flyDust(x: Float, y: Float, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 6.283f
            val life = 0.7f + Random.nextFloat() * 0.3f
            particles.add(Particle(x, y, cos(a) * 120f * dp, sin(a) * 120f * dp, life, life, DUST, P_DUST,
                width - 70f * dp, 36f * dp))
        }
    }

    // ---------- Buttons ----------

    private fun buildButtons(): List<Button> {
        val w = width.toFloat()
        val h = height.toFloat()
        if (splashT > 0f) return ArrayList()
        if (mini > 0) return ArrayList<Button>().also { miniButtons(it, w, h) }
        if (settingsOpen) return ArrayList<Button>().also { settingsButtons(it, w, h) }
        if (previewKind >= 0) return ArrayList<Button>().also { l -> previewButtons(l, w, h) }
        if (offerPack >= 0) return ArrayList<Button>().also { l ->
            val pk = Data.PACKS[offerPack]
            val card = offerCard(w, h)
            l.add(Button("offer_buy", RectF(card.left + 24f * dp, card.bottom - 116f * dp, card.right - 24f * dp, card.bottom - 66f * dp), "GET IT  ${pk.price}", STYLE_PRIMARY) {
                val act = context as? android.app.Activity ?: return@Button
                Store.buy(act, pk) { ok ->
                    if (ok) { val got = lab.grantPack(pk); offerPack = -1; sound.play(Fx.DISCOVER); burst(w / 2, h * 0.4f, DUST, 50, P_SPARK); toast("🎁 ${pk.name}: $got", DUST); markDirty() }
                    else toast("🎁 Packs open when Shroomology launches on Google Play!", MINT)
                }
            })
            l.add(Button("offer_peek", RectF(card.left + 24f * dp, card.bottom - 166f * dp, card.right - 24f * dp, card.bottom - 124f * dp), "🔍 SEE WHAT'S INSIDE") {
                previewKind = 7; previewIdx = offerPack; previewAnim = 0f; previewFromOffer = true
            })
            l.add(Button("offer_close", RectF(card.left + 24f * dp, card.bottom - 58f * dp, card.right - 24f * dp, card.bottom - 16f * dp), "Maybe later", STYLE_NORMAL) { closeOffer() })
        }
        if (merchantOpen) return ArrayList<Button>().also { l ->
            val day = LabState.dayKey()
            val offers = Data.merchantOffers(day)
            val card = merchantCard(w, h)
            val rh = 64f * dp
            for ((k, o) in offers.withIndex()) {
                val y = card.top + 120f * dp + k * (rh + 6f * dp)
                val bought = lab.merchantBought.contains("$day-$k")
                l.add(Button("offer$k", RectF(card.right - 112f * dp, y + 12f * dp, card.right - 14f * dp, y + rh - 12f * dp),
                    if (bought) "SOLD" else "${o.price} ✦", if (bought) STYLE_NORMAL else STYLE_PRIMARY, !bought && lab.dust >= o.price) {
                    val got = lab.buyOffer(day, k)
                    if (got == null) { sound.play(Fx.DENIED); toast(if (bought) "Already bought" else "Not enough spore dust ✦", ACCENT) }
                    else { sound.play(Fx.COIN); burst(w / 2, y + rh / 2, DUST, 16, P_SPARK); toast("Bought $got!", MINT); markDirty() }
                })
            }
            l.add(Button("m_close", RectF(card.left + 20f * dp, card.bottom - 62f * dp, card.right - 20f * dp, card.bottom - 16f * dp), "BYE BYE!") { merchantOpen = false })
        }
        if (familyFor >= 0) return ArrayList<Button>().also { l ->
            l.add(Button("fam_close", RectF(w / 2 - 90f * dp, h * 0.86f, w / 2 + 90f * dp, h * 0.86f + 50f * dp), "CLOSE") { familyFor = -1 })
        }
        if (cardFor >= 0) return ArrayList<Button>().also { l ->
            val r = cardRect(w, h)
            val bw = (r.width() - 12f * dp) / 2
            l.add(Button("c_share", RectF(r.left, r.bottom + 14f * dp, r.left + bw, r.bottom + 62f * dp), "SHARE", STYLE_PRIMARY) {
                shroom(cardFor)?.let { shareCard(it) }
            })
            l.add(Button("c_close", RectF(r.right - bw, r.bottom + 14f * dp, r.right, r.bottom + 62f * dp), "CLOSE") { cardFor = -1 })
        }
        if (tutStep == T_WELCOME || tutStep == T_DONE) return ArrayList<Button>().also { tutorialButtons(it, w, h) }
        val list = buildButtonsInner(w, h)
        if (tutStep >= 0 && !photoMode) tutorialButtons(list, w, h)
        return list
    }

    private fun buildButtonsInner(w: Float, h: Float): ArrayList<Button> {
        val list = ArrayList<Button>()

        // Popup takes over the screen
        popup?.let { p ->
            if (popupT < 1.2f) return list
            val bw = min(w * 0.36f, 160f * dp)
            val y = h * 0.66f
            list.add(Button("p_more", RectF(w / 2 - bw - 6f * dp, y, w / 2 - 6f * dp, y + 52f * dp),
                if (p.crossed) "BREED MORE" else "BREW MORE") { popup = null })
            list.add(Button("p_see", RectF(w / 2 + 6f * dp, y, w / 2 + bw + 6f * dp, y + 52f * dp), "SEE IT", STYLE_PRIMARY) {
                popup = null; tab = Tab.GARDEN; selected = p.shroomId; feeding = false
                shroom(p.shroomId)?.let { if (it.area == area) camX = it.gx.coerceIn(camMin(), camMax()) }
                if (lab.tutorial == 2) lab.tutorial = 3
            })
            return list
        }

        if (photoMode) {
            val bw = (w - 48f * dp) / 2
            list.add(Button("snap", RectF(16f * dp, navTop + 14f * dp, 16f * dp + bw, navTop + 62f * dp), "📷 SAVE & SHARE", STYLE_PRIMARY) { takePhoto() })
            list.add(Button("unsnap", RectF(32f * dp + bw, navTop + 14f * dp, w - 16f * dp, navTop + 62f * dp), "CLOSE") { photoMode = false })
            return list
        }

        // Bottom navigation
        val tabs = Tab.values()
        val tw = w / tabs.size
        for ((i, tb) in tabs.withIndex()) {
            list.add(Button("nav$i", RectF(i * tw, navTop, (i + 1) * tw, h), "", STYLE_CARD,
                custom = { c, r -> drawNavItem(c, r, tb) }) {
                if (tab != tb) { tab = tb; selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false; confirmRelease = false; dexPick = -1; decorating = false }
            })
        }

        // Settings gear in the top bar
        list.add(Button("gear", RectF(w - 54f * dp, 12f * dp, w - 10f * dp, 56f * dp), "", STYLE_CARD,
            custom = { c, r -> drawGear(c, r) }) { settingsOpen = true })

        when (tab) {
            Tab.GARDEN -> gardenButtons(list, w, h)
            Tab.LAB -> labButtons(list, w, h)
            Tab.DEX -> dexButtons(list, w, h)
            Tab.SHOP -> shopButtons(list, w, h)
            Tab.JOURNAL -> journalButtons(list, w, h)
        }
        return list
    }

    private var panelAnim = 1f
    private var lastPanelKey = -1L
    private val chatted = HashSet<String>()
    private var shownDust = -1f
    private var cardAnim = 0f

    /** How far the bottom panel is still sliding up (0 once it's in place). */
    private fun panelSlide() = (1f - easeOutBack(panelAnim)) * (navTop - height * 0.58f)
    private fun panelTop() = height * 0.58f + panelSlide()
    private fun panelBottom() = navTop - 6f * dp + panelSlide()

    private fun gardenButtons(list: ArrayList<Button>, w: Float, h: Float) {
        if (lab.caveOpen() && !decorating) {
            list.add(Button("area", RectF(112f * dp, 16f * dp, 196f * dp, 50f * dp), if (area == 0) "⛰ Cave" else "🌿 Meadow") {
                area = 1 - area; selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false
                sound.play(Fx.POP); waveHello()
            })
        }
        if (area == 1) {
            if (selected < 0) list.add(Button("photo", RectF(14f * dp, topBar + 8f * dp, 62f * dp, topBar + 48f * dp), "📷") {
                photoMode = true; selected = -1; toast("Photo mode: tap SAVE & SHARE")
            })
        } else list.add(Button("decorate", RectF(14f * dp, topBar + 8f * dp, 134f * dp, topBar + 48f * dp),
            if (decorating) "DONE" else "DECORATE", if (decorating) STYLE_PRIMARY else STYLE_NORMAL) {
            decorating = !decorating
            selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false; confirmRelease = false
        })
        if (decorating) { decorButtons(list, w); return }
        if (selected < 0 && area == 0) {
            list.add(Button("photo", RectF(142f * dp, topBar + 8f * dp, 190f * dp, topBar + 48f * dp), "📷") {
                photoMode = true; selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false
                toast("Photo mode: tap SAVE & SHARE")
            })
        }
        val away = lab.garden.filter { it.away() }
        if (away.isNotEmpty() && selected < 0) {
            list.add(Button("away", RectF(14f * dp, topBar + 54f * dp, 134f * dp, topBar + 84f * dp), "🧭 ${away.size} exploring") {
                val now = System.currentTimeMillis()
                for (a in away) toast("${a.name}: ${Data.ZONES[a.awayZone].name}, back in ${timeLeft(a.awayUntil - now)}", MINT)
            })
        }
        val totalDust = lab.home().sumOf { it.dust.toInt() }
        if (totalDust >= 1 && selected < 0) {
            list.add(Button("collect", RectF(w - 170f * dp, topBar + 8f * dp, w - 14f * dp, topBar + 48f * dp),
                "Collect $totalDust ✦", STYLE_PRIMARY) {
                for ((i, s) in lab.garden.withIndex()) collect(s, i)
            })
        }

        if (selected < 0 && area == 0 && !decorating && growPick < 0) {
            for (i in 0 until 6) {
                val (px, py) = toScreen(plotGx(i), plotGz(i))
                if (px < -40f * dp || px > w + 40f * dp) continue
                val d = depth(plotGz(i))
                list.add(Button("plot$i", RectF(px - 34f * dp * d, py - 52f * dp * d, px + 34f * dp * d, py + 10f * dp * d), "", STYLE_GHOST, custom = { _, _ -> }) { tapPlot(i) })
            }
        }
        if (growPick >= 0 && area == 0) { seedButtons(list, w, h); return }
        if (selected < 0 && !decorating && !photoMode) offerSideButtons(list, w, h)
        if (selected < 0 && area == 0 && merchantHere()) {
            val (mx, my) = toScreen(0.9f, 0.08f)
            list.add(Button("merchant", RectF(mx - 50f * dp, my - 90f * dp, mx + 40f * dp, my + 6f * dp), "", STYLE_GHOST,
                custom = { c, rr -> drawMerchant(c, rr.centerX(), rr.bottom - 6f * dp, 80f * dp * depth(0.08f)) }) {
                merchantOpen = true; sound.play(Fx.CHIRP, 0.6f, 0.8f)
            })
        }
        if (selected < 0 && !decorating) {
            if (camX > camMin() + 0.02f) list.add(Button("pan_l", RectF(2f * dp, h * 0.4f, 30f * dp, h * 0.4f + 50f * dp), "‹", STYLE_GHOST,
                custom = { c, rr -> drawEdgeArrow(c, rr, -1f) }) { camVel = -2.2f })
            if (camX < camMax() - 0.02f) list.add(Button("pan_r", RectF(w - 30f * dp, h * 0.4f, w - 2f * dp, h * 0.4f + 50f * dp), "›", STYLE_GHOST,
                custom = { c, rr -> drawEdgeArrow(c, rr, 1f) }) { camVel = 2.2f })
        }
        if (selected < 0) {
            val now = System.currentTimeMillis()
            val here = local()
            val night = LabState.darkness() > 0.6f
            val by0 = groundBottom() + 58f * dp
            val btns = ArrayList<Pair<String, () -> Unit>>()
            if (night && now >= lab.tuckedUntil && here.isNotEmpty()) btns.add("🌙 BEDTIME" to { bedtime() })
            if (jamT <= 0f && now >= lab.tuckedUntil && here.count { !it.sleeping } >= 2) btns.add("🎵 JAM" to { startJam() })
            val bw = 150f * dp
            for ((k, b) in btns.withIndex()) {
                val x0 = w / 2 - (btns.size * bw + (btns.size - 1) * 10f * dp) / 2 + k * (bw + 10f * dp)
                list.add(Button(if (b.first.contains("BED")) "bedtime" else "jam", RectF(x0, by0, x0 + bw, by0 + 40f * dp), b.first) { b.second() })
            }
        }
        val s = shroom(selected) ?: return
        val top = panelTop()
        val pad = 16f * dp
        val by = navTop - 62f * dp + panelSlide()
        if (styling) { styleButtons(list, s, w, top, by); return }
        if (jobbing) { jobButtons(list, s, w, top, by); return }
        if (exploring) { exploreButtons(list, s, w, top, by); return }
        if (feeding) {
            val foodsShown = Data.FOODS.indices.filter { (Data.FOODS[it].event == 0 && !Data.FOODS[it].cooked) || lab.foods[it] > 0 }
            val fcols = min(6, foodsShown.size)
            val frows = (foodsShown.size + fcols - 1) / fcols
            val cw = (w - pad * 2) / fcols
            val fh = (by - 10f * dp - (top + 96f * dp)) / frows
            for ((pos, f) in foodsShown.withIndex()) {
                val fy0 = top + 96f * dp + (pos / fcols) * fh
                val r = RectF(pad + (pos % fcols) * cw + 3f * dp, fy0 + 2f * dp, pad + (pos % fcols + 1) * cw - 3f * dp, fy0 + fh - 2f * dp)
                list.add(Button("food$f", r, "", STYLE_CARD, lab.foods[f] > 0, custom = { c, rr -> drawFoodCard(c, rr, f, s) }) {
                    feed(s, f)
                })
            }
            list.add(Button("fback", RectF(pad, by, w - pad, by + 50f * dp), "DONE") { feeding = false })
        } else {
            val bw = (w - pad * 2 - 16f * dp) / 3
            fun cell(col: Int, row: Int) = RectF(pad + col * (bw + 8f * dp), by - (1 - row) * 54f * dp,
                pad + col * (bw + 8f * dp) + bw, by - (1 - row) * 54f * dp + 46f * dp)
            list.add(Button("feed", cell(0, 0), "FEED", STYLE_PRIMARY) { feeding = true; confirmRelease = false })
            if (moreOpen) {
                list.add(Button("card", cell(0, 0), "CARD") { cardFor = s.id; moreOpen = false })
                list.add(Button("family", cell(1, 0), "FAMILY") { familyFor = s.id; moreOpen = false })
                list.add(Button("job", cell(2, 0), if (s.job > 0) Data.JOBS[s.job].icon + " JOB" else "JOB") { jobbing = true; moreOpen = false })
                list.add(Button("move", cell(0, 1), if (!lab.caveOpen()) "🔒 CAVE" else if (s.area == 0) "TO CAVE" else "TO MEADOW", STYLE_NORMAL, lab.caveOpen()) {
                    if (!lab.caveOpen()) { toast("Unlock the Glow Cave in the Shop → LAB"); return@Button }
                    s.area = 1 - s.area; s.act = 0; s.pendingAct = 0; s.sleeping = false
                    s.gx = 0.2f + Random.nextFloat() * 0.6f; s.gz = 0.3f + Random.nextFloat() * 0.5f; s.tx = s.gx; s.tz = s.gz; s.petGx = Float.NaN
                    toast(if (s.area == 1) "${s.name} moved to the Glow Cave ⛰" else "${s.name} moved to the meadow 🌿", MINT)
                    sound.play(Fx.WHEE); selected = -1; moreOpen = false; markDirty()
                })
                val fav = prefs.getLong("widgetShroom", -1L) == s.id
                list.add(Button("widget", cell(1, 1), if (fav) "★ ON WIDGET" else "☆ WIDGET", if (fav) STYLE_PICKED else STYLE_NORMAL) {
                    prefs.edit().putLong("widgetShroom", s.id).apply()
                    updateWidget()
                    toast("${s.name} will live on your home-screen widget! Add it: long-press your home screen → Widgets → Shroomology", MINT)
                })
                list.add(Button("mback", cell(2, 1), "BACK") { moreOpen = false })
                return
            }
            list.add(Button("more", cell(1, 0), "MORE") { moreOpen = true; confirmRelease = false })
            list.add(Button("style", cell(2, 0), "STYLE") { styling = true; confirmRelease = false })
            list.add(Button("explore", cell(0, 1), "EXPLORE") { exploring = true; confirmRelease = false })
            list.add(Button("rename", cell(1, 1), "NAME") { renameDialog(s); confirmRelease = false })
            list.add(Button("release", cell(2, 1), if (confirmRelease) "SURE?" else "RELEASE", STYLE_DANGER) {
                if (confirmRelease) release(s) else confirmRelease = true
            })
        }
    }

    private fun styleButtons(list: ArrayList<Button>, s: Shroom, w: Float, top: Float, by: Float) {
        val pad = 16f * dp
        val owned = Data.ACCESSORIES.indices.filter { lab.accOwned[it] > 0 }.take(10)
        val pets = Data.PETS.indices.filter { lab.petOwned[it] > 0 }
        val cols = 5
        val cw = (w - pad * 2) / cols
        val gy = top + 96f * dp
        val rows = max(1, (owned.size + pets.size + cols - 1) / cols)
        val ch = min(70f * dp, (by - 8f * dp - gy) / max(2, rows))
        for ((k, p) in pets.withIndex()) {
            val pos = owned.size + k
            val mine = s.pet == p
            val r = RectF(pad + (pos % cols) * cw + 3f * dp, gy + (pos / cols) * ch + 3f * dp,
                pad + (pos % cols + 1) * cw - 3f * dp, gy + (pos / cols + 1) * ch - 3f * dp)
            list.add(Button("pet$p", r, "", if (mine) STYLE_PICKED else STYLE_CARD, mine || lab.petAvailable(p) > 0, custom = { c, rr ->
                drawPet(c, p, rr.centerX(), rr.centerY() + rr.height() * 0.12f, rr.height() * 0.45f, t * 6f, 1f, false)
                text.textSize = 9.5f * dp
                c.drawText(if (mine) "with you" else Data.PETS[p].name, rr.centerX(), rr.bottom - 5f * dp, text)
            }) {
                when {
                    mine -> s.pet = -1
                    lab.petAvailable(p) <= 0 -> toast("That pet is following someone else")
                    else -> { s.pet = p; s.petGx = Float.NaN; sound.play(Fx.PET); toast("${Data.PETS[p].name} will follow ${s.name} around!", MINT) }
                }
                markDirty()
            })
        }
        for ((pos, a) in owned.withIndex()) {
            val slot = Data.ACCESSORIES[a].slot
            val worn = s.wear[slot] == a
            val r = RectF(pad + (pos % cols) * cw + 3f * dp, gy + (pos / cols) * ch + 3f * dp,
                pad + (pos % cols + 1) * cw - 3f * dp, gy + (pos / cols + 1) * ch - 3f * dp)
            list.add(Button("acc$a", r, "", if (worn) STYLE_PICKED else STYLE_CARD, worn || lab.accAvailable(a) > 0, custom = { c, rr ->
                drawAccessoryIcon(c, a, rr.centerX(), rr.centerY() - 6f * dp, rr.height() * 0.32f)
                text.textSize = 9.5f * dp
                c.drawText(if (worn) "wearing" else Data.ACCESSORIES[a].name, rr.centerX(), rr.bottom - 5f * dp, text)
            }) {
                when {
                    worn -> s.wear[slot] = -1
                    lab.accAvailable(a) <= 0 -> toast("Another mushroom is wearing that one")
                    else -> { s.wear[slot] = a; sound.play(Fx.PET); burst(shroomPos(lab.garden.indexOf(s)).first, shroomPos(lab.garden.indexOf(s)).second - 40f * dp, ACCENT, 8, P_SPARK) }
                }
                markDirty()
            })
        }
        list.add(Button("sdone", RectF(pad, by, w - pad, by + 46f * dp), "DONE") { styling = false })
    }

    private fun jobButtons(list: ArrayList<Button>, s: Shroom, w: Float, top: Float, by: Float) {
        val pad = 16f * dp
        val gy = top + 96f * dp
        val rh = min(52f * dp, (by - 8f * dp - gy) / Data.JOBS.size)
        for ((j, job) in Data.JOBS.withIndex()) {
            val r = RectF(pad, gy + j * rh + 2f * dp, w - pad, gy + (j + 1) * rh - 2f * dp)
            val holder = if (j == 0) null else lab.jobHolder(j)
            val mine = s.job == j
            val ok = j == 0 || (s.stage() >= 2 && (holder == null || holder === s))
            list.add(Button("job$j", r, "", if (mine) STYLE_PICKED else STYLE_CARD, ok || mine, custom = { c, rr ->
                text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
                c.drawText((if (job.icon.isNotEmpty()) job.icon + "  " else "") + job.name, rr.left + 14f * dp, rr.centerY() - 1f * dp, text)
                text.textSize = 11f * dp; text.color = TEXT_DIM
                c.drawText(when {
                    j > 0 && s.stage() < 2 -> "Needs a Grown mushroom"
                    holder != null && holder !== s -> "${holder.name} has this job"
                    else -> job.desc
                }, rr.left + 14f * dp, rr.centerY() + 14f * dp, text)
                text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            }) {
                if (!ok) { toast(if (s.stage() < 2) "Only Grown mushrooms can work" else "Someone else has that job"); return@Button }
                s.job = j
                if (j > 0) { toast("${s.name} is now the ${job.name}! ${job.icon}", MINT); sound.play(Fx.GROW); express(s, E_SPARKLE, 2f) }
                jobbing = false
                markDirty()
            })
        }
        list.add(Button("jcancel", RectF(pad, by, w - pad, by + 46f * dp), "CANCEL") { jobbing = false })
    }

    private fun exploreButtons(list: ArrayList<Button>, s: Shroom, w: Float, top: Float, by: Float) {
        val pad = 16f * dp
        val gy = top + 96f * dp
        val rh = min(52f * dp, (by - 8f * dp - gy) / Data.ZONES.size)
        for ((z, zone) in Data.ZONES.withIndex()) {
            val r = RectF(pad, gy + z * rh + 2f * dp, w - pad, gy + (z + 1) * rh - 2f * dp)
            val ok = s.stage() >= zone.minStage
            list.add(Button("zone$z", r, "", STYLE_CARD, ok, custom = { c, rr ->
                fill.color = zone.color
                c.drawCircle(rr.left + 20f * dp, rr.centerY(), 9f * dp, fill)
                text.textAlign = Paint.Align.LEFT
                text.textSize = 14f * dp
                c.drawText(zone.name, rr.left + 38f * dp, rr.centerY() - 1f * dp, text)
                text.textSize = 11f * dp; text.color = TEXT_DIM
                val hrs = lab.expeditionHours(z)
                val loot = zone.loot.distinct().joinToString(", ") { Data.INGREDIENTS[it].name }
                c.drawText(if (ok) "${if (hrs < 1f) "${(hrs * 60).toInt()} min" else "${"%.1f".format(hrs).removeSuffix(".0")} h"} · $loot"
                    else "Needs an ${Data.STAGE_NAMES[zone.minStage]} mushroom", rr.left + 38f * dp, rr.centerY() + 14f * dp, text)
                text.color = Color.WHITE
                text.textAlign = Paint.Align.CENTER
            }) {
                if (!ok) { toast("Only ${Data.STAGE_NAMES[zone.minStage]} mushrooms can go there"); return@Button }
                lab.sendAway(s, z)
                toast("${s.name} set off for ${zone.name}! Back in ${timeLeft(s.awayUntil - System.currentTimeMillis())}", MINT)
                sound.play(Fx.GROW)
                selected = -1; exploring = false
                markDirty()
            })
        }
        list.add(Button("ecancel", RectF(pad, by, w - pad, by + 46f * dp), "CANCEL") { exploring = false })
    }

    private fun renameDialog(s: Shroom) {
        val act = context as? android.app.Activity ?: return
        val input = android.widget.EditText(act).apply {
            setText(s.name)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            selectAll()
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("Name your mushroom")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { s.name = n; markDirty() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun decorButtons(list: ArrayList<Button>, w: Float) {
        val top = panelTop()
        val pad = 16f * dp
        // theme switcher
        val owned = Data.THEMES.indices.filter { (lab.themesOwned shr it) and 1 == 1 }
        list.add(Button("theme", RectF(pad, top + 10f * dp, w - pad, top + 50f * dp),
            "Theme: ${Data.THEMES[lab.theme].name}${if (owned.size > 1) "  ▸" else ""}") {
            if (owned.size <= 1) toast("Buy more themes in the Shop → THEMES")
            else { lab.theme = owned[(owned.indexOf(lab.theme) + 1) % owned.size]; markDirty() }
        })
        val cols = 6
        val cw = (w - pad * 2) / cols
        val gy = top + 58f * dp
        val shown = Data.DECOR.indices.filter { Data.DECOR[it].event == 0 || lab.decorOwned[it] > 0 || lab.placed.any { p -> p.kind == it } }
        val rowsN = (shown.size + cols - 1) / cols
        val ch = min(64f * dp, (navTop - 30f * dp - gy) / rowsN)
        for ((pos, k) in shown.withIndex()) {
            val r = RectF(pad + (pos % cols) * cw + 2f * dp, gy + (pos / cols) * ch + 2f * dp,
                pad + (pos % cols + 1) * cw - 2f * dp, gy + (pos / cols + 1) * ch - 2f * dp)
            list.add(Button("dec$k", r, "", STYLE_CARD, lab.decorOwned[k] > 0, custom = { c, rr ->
                c.save(); c.clipRect(rr)
                drawDecor(c, k, rr.centerX(), rr.bottom - 16f * dp, 0.55f)
                c.restore()
                text.textSize = 11f * dp; text.color = if (lab.decorOwned[k] > 0) Color.WHITE else TEXT_DIM
                c.drawText("×${lab.decorOwned[k]}", rr.centerX(), rr.bottom - 4f * dp, text)
                text.color = Color.WHITE
            }) {
                if (lab.decorOwned[k] <= 0) toast("Buy a ${Data.DECOR[k].name} in the Shop → DECOR")
                else toast("Drag it onto the ground to place it")
            })
        }
    }

    private fun flaskY() = height * 0.29f

    private fun slotRect(k: Int): RectF {
        val r = 34f * dp
        if (k == 2) {   // the Big Flask's third slot sits right above the flask's mouth
            val cy = flaskY() - 62f * dp * 1.95f
            return RectF(width / 2f - r * 0.85f, cy - r * 0.85f, width / 2f + r * 0.85f, cy + r * 0.85f)
        }
        val sx = width / 2f + (if (k == 0) -1 else 1) * min(width * 0.33f, 130f * dp)
        return RectF(sx - r, flaskY() - r, sx + r, flaskY() + r)
    }

    private fun labButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 16f * dp
        val tabW = (w - pad * 2) / 3
        val ty = topBar + 6f * dp
        for ((k, tm) in arrayOf("BREW" to 0, "CROSSBREED" to 1, "COOK" to 3).withIndex()) {
            val (nm, mode) = tm
            list.add(Button("m$mode", RectF(pad + k * tabW + 2f * dp, ty, pad + (k + 1) * tabW - 2f * dp, ty + 42f * dp), nm,
                if (labMode == mode) STYLE_TAB_ON else STYLE_NORMAL) { if (brewing <= 0f) labMode = mode })
        }
        if (labMode == 3) { cookButtons(list, w, h); return }

        for (k in 0 until slotCount()) {
            val r = slotRect(k)
            list.add(Button("slot$k", r, "", STYLE_CARD, custom = { c, rr -> drawSlot(c, rr, k) }) {
                if (brewing > 0f) return@Button
                if (labMode == 0) slots[k] = -1 else if (picks.size > k) picks.removeAt(k)
            })
        }

        val by = h * 0.45f
        val bw = min(w * 0.6f, 240f * dp)
        if (labMode == 0) {
            val ready = slotsFull() && brewing <= 0f
            list.add(Button("brew", RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + 54f * dp),
                if (brewing > 0f) "STIR!  ${stars(quality())}" else "BREW", STYLE_PRIMARY, ready || brewing > 0f) { startBrew() })

            val gy = by + 66f * dp
            val shown = Data.ingredientsNow()
            val cols = 4
            val rows = (shown.size + cols - 1) / cols
            val cw = (w - pad * 2) / cols
            val ch = min(96f * dp, (navTop - gy - 6f * dp) / rows)
            for ((pos, i) in shown.withIndex()) {
                val r = RectF(pad + (pos % cols) * cw + 3f * dp, gy + (pos / cols) * ch + 3f * dp,
                    pad + (pos % cols + 1) * cw - 3f * dp, gy + (pos / cols + 1) * ch - 3f * dp)
                val left = lab.ingredients[i] - reserved(i)
                list.add(Button("ing$i", r, "", STYLE_CARD, left > 0, custom = { c, rr -> drawIngredientCard(c, rr, i, left) }) {
                    if (brewing > 0f) return@Button
                    if (left <= 0) { sound.play(Fx.DENIED); toast("Out of ${Data.INGREDIENTS[i].name}. Buy more in the Shop", ACCENT); return@Button }
                    val empty = firstEmptySlot()
                    if (empty < 0) { toast("Both slots are full. Tap a slot to empty it"); return@Button }
                    slots[empty] = i
                    sound.play(Fx.POP, 0.8f, 0.9f + empty * 0.2f)
                })
            }
        } else {
            val ready = picks.size == 2 && brewing <= 0f
            list.add(Button("cross", RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + 54f * dp),
                if (brewing > 0f) "STIR!  ${stars(quality())}" else "CROSSBREED  $CROSS_COST ✦", STYLE_PRIMARY, ready || brewing > 0f) { startCross() })

            val gy = by + 92f * dp
            val cols = 4
            val cw = (w - pad * 2) / cols
            val ch = min(120f * dp, (navTop - gy - 8f * dp) / 2)
            val now = System.currentTimeMillis()
            for ((i, s) in lab.home().withIndex()) {
                val r = RectF(pad + (i % cols) * cw + 4f * dp, gy + (i / cols) * ch + 4f * dp,
                    pad + (i % cols + 1) * cw - 4f * dp, gy + (i / cols + 1) * ch - 4f * dp)
                val ok = s.stage() >= 2 && s.restUntil <= now
                val picked = picks.contains(s.id)
                list.add(Button("pick${s.id}", r, "", if (picked) STYLE_PICKED else STYLE_CARD, ok || picked,
                    custom = { c, rr -> drawPickCard(c, rr, s, now) }) {
                    if (brewing > 0f) return@Button
                    when {
                        picked -> picks.remove(s.id)
                        s.stage() < 2 -> toast("Too young! Only Grown mushrooms can crossbreed")
                        s.restUntil > now -> toast("Resting for ${(s.restUntil - now) / 60000 + 1} more min")
                        picks.size >= 2 -> toast("Two mushrooms are already picked")
                        else -> { picks.add(s.id); sound.play(Fx.POP) }
                    }
                })
            }
        }
    }

    private var dexTab = 0
    private val DEX_TABS = arrayOf("COMMON", "RARE & BRED", "EVENT")

    private fun dexTop() = topBar + 86f * dp
    private fun dexBottom() = height * 0.74f

    /** Species shown on a Shroomdex tab. */
    private fun dexList(tb: Int): List<Int> = when (tb) {
        0 -> Data.DEX_ORDER.filter { Data.SPECIES[it].event == 0 && Data.SPECIES[it].tier == 1 }
        1 -> Data.DEX_ORDER.filter { Data.SPECIES[it].event == 0 && Data.SPECIES[it].tier != 1 }
        else -> Data.SPECIES.indices.filter { Data.SPECIES[it].event > 0 }.sortedBy { Data.SPECIES[it].event * 1000 + it }
    }

    private fun dexButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 12f * dp
        val tw = (w - pad * 2) / DEX_TABS.size
        for ((k, nm) in DEX_TABS.withIndex()) {
            list.add(Button("dt$k", RectF(pad + k * tw + 2f * dp, topBar + 40f * dp, pad + (k + 1) * tw - 2f * dp, topBar + 78f * dp), nm,
                if (dexTab == k) STYLE_TAB_ON else STYLE_NORMAL) { dexTab = k; dexPick = -1 })
        }
        val items = dexList(dexTab)
        if (dexTab == 2) {
            // one labelled row per event
            val cols = 6
            val cw = (w - pad * 2) / cols
            val block = (dexBottom() - dexTop()) / 3
            val ch = min(cw * 1.2f, block - 20f * dp)
            for (ev in 1..3) {
                val row = items.filter { Data.SPECIES[it].event == ev }
                val y0 = dexTop() + (ev - 1) * block + 20f * dp
                for ((pos, i) in row.withIndex()) {
                    val r = RectF(pad + pos * cw + 2f * dp, y0, pad + (pos + 1) * cw - 2f * dp, y0 + ch)
                    list.add(Button("dex$i", r, "", if (dexPick == i) STYLE_PICKED else STYLE_CARD,
                        custom = { c, rr -> drawDexCell(c, rr, i) }) { dexPick = i })
                }
            }
            return
        }
        val cols = 5
        val rows = (items.size + cols - 1) / cols
        val cw = (w - pad * 2) / cols
        val ch = min(cw * 1.15f, (dexBottom() - dexTop()) / rows)
        for ((pos, i) in items.withIndex()) {
            val r = RectF(pad + (pos % cols) * cw + 3f * dp, dexTop() + (pos / cols) * ch + 3f * dp,
                pad + (pos % cols + 1) * cw - 3f * dp, dexTop() + (pos / cols + 1) * ch - 3f * dp)
            list.add(Button("dex$i", r, "", if (dexPick == i) STYLE_PICKED else STYLE_CARD,
                custom = { c, rr -> drawDexCell(c, rr, i) }) { dexPick = i })
        }
    }

    // Shop rows: kind 0 ingredient, 1 food, 2 decoration, 3 theme, 4 accessory, 5 lab upgrade
    private fun shopTabs(): List<String> {
        val ev = eventNow()
        val base = listOf("INGREDIENTS", "FOOD", "DECOR", "THEMES", "STYLE", "LAB", "PETS", "🎁 PACKS")
        return if (ev > 0) base + "${Data.EVENT_ICONS[ev]} EVENT" else base
    }

    private fun shopEntries(): List<Pair<Int, Int>> {
        val ev = eventNow()
        return when (shopTab) {
            0 -> Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event == 0 }.map { 0 to it }
            1 -> Data.FOODS.indices.filter { Data.FOODS[it].event == 0 && !Data.FOODS[it].cooked }.map { 1 to it }
            2 -> Data.DECOR.indices.filter { Data.DECOR[it].event == 0 && Data.DECOR[it].special == 0 }.map { 2 to it }
            3 -> Data.THEMES.indices.filter { Data.THEMES[it].event == 0 }.map { 3 to it }
            4 -> Data.ACCESSORIES.indices.filter { Data.ACCESSORIES[it].event == 0 && Data.ACCESSORIES[it].special == 0 }.map { 4 to it }
            5 -> Data.UPGRADES.indices.map { 5 to it }
            6 -> Data.PETS.indices.filter { (Data.PETS[it].event == 0 && Data.PETS[it].special == 0) || lab.petOwned[it] > 0 }.map { 6 to it }
            7 -> Data.PACKS.indices.filter { !Data.PACKS[it].eventOnly || ev > 0 }.map { 7 to it }
            else -> Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event == ev }.map { 0 to it } +
                Data.ACCESSORIES.indices.filter { Data.ACCESSORIES[it].event == ev }.map { 4 to it } +
                Data.DECOR.indices.filter { Data.DECOR[it].event == ev }.map { 2 to it } +
                Data.FOODS.indices.filter { Data.FOODS[it].event == ev }.map { 1 to it } +
                Data.THEMES.indices.filter { Data.THEMES[it].event == ev }.map { 3 to it }
        }
    }

    private fun shopTop() = topBar + 14f * dp + ((shopTabs().size + 2) / 3) * 44f * dp

    private fun shopButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 12f * dp
        val names = shopTabs()
        if (shopTab >= names.size) shopTab = 0
        val tabW = (w - pad * 2) / 3
        val ty = topBar + 6f * dp
        for ((k, nm) in names.withIndex()) {
            val col = k % 3; val row = k / 3
            list.add(Button("s$k", RectF(pad + col * tabW + 2f * dp, ty + row * 44f * dp, pad + (col + 1) * tabW - 2f * dp, ty + row * 44f * dp + 38f * dp), nm,
                if (shopTab == k) STYLE_TAB_ON else STYLE_NORMAL) { shopTab = k })
        }
        val entries = shopEntries()
        val top = shopTop()
        val rowH = min(if (shopTab == 7) 104f * dp else 66f * dp, (navTop - top - (if (shopTab == 8) 26f else 8f) * dp) / max(1, entries.size))
        val p2 = 16f * dp
        for ((row, e) in entries.withIndex()) {
            val (kind, i) = e
            val y = top + row * rowH
            val b5 = RectF(w - p2 - 64f * dp, y + 5f * dp, w - p2, y + rowH - 5f * dp)
            val b1 = RectF(b5.left - 8f * dp - 84f * dp, y + 5f * dp, b5.left - 8f * dp, y + rowH - 5f * dp)
            if (kind == 2 || kind == 3 || kind == 4 || kind == 6 || kind == 7)
                list.add(Button("prev_${kind}_$i", RectF(p2 - 6f * dp, y + 2f * dp, b1.left - 4f * dp, y + rowH - 2f * dp), "", STYLE_GHOST, custom = { _, _ -> }) {
                    previewKind = kind; previewIdx = i; previewAnim = 0f; sound.play(Fx.POP, 0.5f, 1.2f)
                })
            val wide = RectF(b1.left, b1.top, b5.right, b5.bottom)
            when (kind) {
                0, 1 -> {
                    val cost = if (kind == 0) Data.INGREDIENTS[i].cost else Data.FOODS[i].cost
                    list.add(Button("buy1_${kind}_$i", b1, "$cost ✦", STYLE_PRIMARY, lab.dust >= cost) { buy(kind, i, 1) })
                    list.add(Button("buy5_${kind}_$i", b5, "×5", STYLE_NORMAL, lab.dust >= cost * 5) { buy(kind, i, 5) })
                }
                2 -> {
                    val cost = Data.DECOR[i].cost
                    list.add(Button("dbuy$i", wide, "$cost ✦", STYLE_PRIMARY, lab.dust >= cost) {
                        if (lab.dust < cost) { sound.play(Fx.DENIED); notEnoughDust() }
                        else { lab.dust -= cost; lab.decorOwned[i]++; sound.play(Fx.COIN); toast("Place it from Garden → DECORATE", MINT); markDirty() }
                    })
                }
                4 -> {
                    val a = Data.ACCESSORIES[i]
                    list.add(Button("abuy$i", wide, "${a.cost} ✦", STYLE_PRIMARY, lab.dust >= a.cost) {
                        if (lab.dust < a.cost) { sound.play(Fx.DENIED); notEnoughDust() }
                        else { lab.dust -= a.cost; lab.accOwned[i]++; sound.play(Fx.COIN); toast("Dress up a mushroom: tap it → STYLE", MINT); markDirty() }
                    })
                }
                7 -> {
                    val pk = Data.PACKS[i]
                    val owned = lab.packOwned(pk)
                    list.add(Button("pack$i", RectF(w - pad - 104f * dp, y + rowH / 2 - 22f * dp, w - pad, y + rowH / 2 + 22f * dp),
                        if (owned) "OWNED" else pk.price, if (owned) STYLE_NORMAL else STYLE_PRIMARY, !owned) {
                        if (owned) return@Button
                        val act = context as? android.app.Activity ?: return@Button
                        Store.buy(act, pk) { ok ->
                            if (ok) { val got = lab.grantPack(pk); sound.play(Fx.DISCOVER); burst(w / 2, height * 0.4f, DUST, 50, P_SPARK); toast("🎁 ${pk.name}: $got", DUST); markDirty() }
                            else toast("🎁 Packs open when Shroomology launches on Google Play!", MINT)
                        }
                    })
                }
                6 -> {
                    val pt = Data.PETS[i]
                    if (pt.event > 0) list.add(Button("pbuy$i", wide, "Quest pet", STYLE_NORMAL, false) {})
                    else list.add(Button("pbuy$i", wide, "${pt.cost} ✦", STYLE_PRIMARY, lab.dust >= pt.cost) {
                        if (lab.dust < pt.cost) { sound.play(Fx.DENIED); notEnoughDust() }
                        else { lab.dust -= pt.cost; lab.petOwned[i]++; sound.play(Fx.COIN); toast("New pet! Tap a mushroom → STYLE to bring it along", MINT); markDirty() }
                    })
                }
                5 -> {
                    val u = Data.UPGRADES[i]
                    val lvl = lab.upgrades[i]
                    val maxed = lvl >= u.costs.size
                    list.add(Button("ubuy$i", wide, if (maxed) "MAX" else "${u.costs[lvl]} ✦", if (maxed) STYLE_NORMAL else STYLE_PRIMARY,
                        !maxed && lab.dust >= u.costs[lvl]) {
                        when {
                            maxed -> {}
                            lab.dust < u.costs[lvl] -> { sound.play(Fx.DENIED); notEnoughDust() }
                            else -> {
                                lab.dust -= u.costs[lvl]; lab.upgrades[i]++; sound.play(Fx.DISCOVER)
                                toast(if (i == Data.UP_CAVE) "The Glow Cave is open! Tap ⛰ Cave in the Garden" else "Upgraded: ${u.name}!", MINT); markDirty()
                            }
                        }
                    })
                }
                else -> {
                    val th = Data.THEMES[i]
                    val owned = (lab.themesOwned shr i) and 1 == 1
                    val label = when { lab.theme == i -> "IN USE"; owned -> "USE"; else -> "${th.cost} ✦" }
                    list.add(Button("tbuy$i", wide, label, if (owned) STYLE_NORMAL else STYLE_PRIMARY, lab.theme != i && (owned || lab.dust >= th.cost)) {
                        when {
                            lab.theme == i -> {}
                            owned -> { lab.theme = i; sound.play(Fx.POP); markDirty() }
                            lab.dust < th.cost -> { sound.play(Fx.DENIED); notEnoughDust() }
                            else -> {
                                lab.dust -= th.cost; lab.themesOwned = lab.themesOwned or (1 shl i); lab.theme = i
                                sound.play(Fx.DISCOVER); toast("New garden theme: ${th.name}!", MINT); markDirty()
                            }
                        }
                    })
                }
            }
        }
    }

    private fun drawShop(c: Canvas, w: Float, h: Float) {
        val pad = 16f * dp
        val entries = shopEntries()
        val top = shopTop()
        val rowH = min(if (shopTab == 7) 104f * dp else 66f * dp, (navTop - top - (if (shopTab == 8) 26f else 8f) * dp) / max(1, entries.size))
        for ((row, e) in entries.withIndex()) {
            val (kind, i) = e
            val y = top + row * rowH
            fill.color = if (row % 2 == 0) PANEL else Color.rgb(40, 28, 74)
            c.drawRoundRect(pad - 6f * dp, y + 2f * dp, w - pad + 6f * dp, y + rowH - 2f * dp, 14f * dp, 14f * dp, fill)
            val cy = y + rowH / 2
            val name: String
            val sub: String
            when (kind) {
                0 -> { drawIngredient(c, i, pad + 20f * dp, cy, 14f * dp); name = Data.INGREDIENTS[i].name; sub = "You have ${lab.ingredients[i]}" }
                1 -> { drawFood(c, i, pad + 20f * dp, cy, 14f * dp); name = Data.FOODS[i].name
                    sub = Data.FOODS[i].let { "Have ${lab.foods[i]} · +${it.hunger} food, +${it.xp} growth" } }
                2 -> {
                    c.save(); c.clipRect(pad, y + 3f * dp, pad + 44f * dp, y + rowH - 3f * dp)
                    drawDecor(c, i, pad + 22f * dp, y + rowH - 8f * dp, 0.5f)
                    c.restore()
                    name = Data.DECOR[i].name
                    val placedN = lab.placed.count { it.kind == i }
                    sub = "Have ${lab.decorOwned[i] + placedN}" + when (i) {
                        in 11..14 -> " · a toy to play with"; 15 -> " · they gather & sing at night"
                        else -> if (Data.DECOR[i].glow) " · glows at night" else "" }
                }
                4 -> {
                    drawAccessoryIcon(c, i, pad + 20f * dp, cy, 12f * dp)
                    val a = Data.ACCESSORIES[i]
                    name = a.name
                    sub = "Have ${lab.accOwned[i]} · " + arrayOf("worn on the cap", "worn on the face", "worn on the stem")[a.slot]
                }
                7 -> {
                    val pk = Data.PACKS[i]
                    drawGiftBox(c, pad + 20f * dp, cy + 12f * dp, 30f * dp, pk.color)
                    text.textAlign = Paint.Align.LEFT; text.textSize = 15f * dp
                    c.drawText(pk.name, pad + 44f * dp, y + 24f * dp, text)
                    val pnw = text.measureText(pk.name)
                    text.textSize = 10f * dp; text.color = Color.argb(170, 255, 255, 255)
                    c.drawText("🔍 preview", pad + 52f * dp + pnw, y + 23f * dp, text); text.color = Color.WHITE
                    text.textSize = 10.5f * dp; text.color = TEXT_DIM
                    for ((k, l) in wrap(pk.desc, w - pad * 2 - 150f * dp).take(4).withIndex()) c.drawText(l, pad + 44f * dp, y + 40f * dp + k * 13f * dp, text)
                    text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                    continue
                }
                6 -> {
                    c.save(); c.clipRect(pad - 4f * dp, y + 3f * dp, pad + 44f * dp, y + rowH - 3f * dp)
                    drawPet(c, i, pad + 20f * dp, cy + 12f * dp, 28f * dp, t * 6f, 1f, false)
                    c.restore()
                    name = Data.PETS[i].name
                    sub = "Have ${lab.petOwned[i]} · ${Data.PETS[i].desc}"
                }
                5 -> {
                    val u = Data.UPGRADES[i]
                    fill.color = MINT
                    c.drawCircle(pad + 20f * dp, cy, 14f * dp, fill)
                    text.textSize = 12f * dp; text.color = BG
                    c.drawText("${lab.upgrades[i]}/${u.costs.size}", pad + 20f * dp, cy + 4f * dp, text)
                    text.color = Color.WHITE
                    name = u.name
                    sub = u.desc
                }
                else -> {
                    val th = Data.THEMES[i]
                    fill.shader = LinearGradient(0f, cy - 14f * dp, 0f, cy + 14f * dp, intArrayOf(th.skyTop, th.skyBottom, th.groundFar, th.groundNear),
                        floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(pad + 20f * dp, cy, 14f * dp, fill); fill.shader = null
                    name = th.name
                    sub = if ((lab.themesOwned shr i) and 1 == 1) "Owned" else "New sky, ground and weather"
                }
            }
            text.textAlign = Paint.Align.LEFT
            text.textSize = if (rowH < 50f * dp) 13.5f * dp else 15f * dp
            c.drawText(name, pad + 42f * dp, cy - 2f * dp, text)
            if (kind == 2 || kind == 3 || kind == 4 || kind == 6) {
                val nw = text.measureText(name)
                text.textSize = 10f * dp; text.color = Color.argb(170, 255, 255, 255)
                c.drawText("🔍", pad + 48f * dp + nw, cy - 3f * dp, text); text.color = Color.WHITE
            }
            text.textSize = 10.5f * dp; text.color = TEXT_DIM
            c.drawText(sub, pad + 42f * dp, cy + 13f * dp, text)
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
        }
        if (shopTab == 8) {
            val ev = eventNow()
            text.textSize = 12f * dp; text.color = DUST
            c.drawText("${Data.EVENT_ICONS[ev]} ${Data.EVENTS[ev]} until ${Data.EVENT_ENDS[ev]} · event ingredients fade after", w / 2, navTop - 4f * dp, text)
            text.color = Color.WHITE
        }
    }

    // ---------- Input ----------

    private var touchX = 0f
    private var touchY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { touchX = x; touchY = y }
        if (splashT > 0f) {
            if (e.actionMasked == MotionEvent.ACTION_UP) { holdSplash = false; splashT = min(splashT, 0.45f) }
            return true
        }
        if (mini > 0) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedId = buttons.lastOrNull { it.rect.contains(x, y) }?.id
                    if (pressedId == null && mini == 1) catchTarget = x
                }
                MotionEvent.ACTION_MOVE -> if (pressedId == null && mini == 1) catchTarget = x
                MotionEvent.ACTION_UP -> {
                    val b = buttons.lastOrNull { it.id == pressedId && it.rect.contains(x, y) }
                    pressedId = null
                    b?.let { sound.play(Fx.CLICK, 0.6f); it.action() }
                }
            }
            return true
        }
        if (handleStickerTouch(e, x, y)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragX = x; dragY = y
                dragging = false; dragKind = 0
                pointerDown = true; downT = t; petting = false; hugDone = false
                revs = 0; lastDxSign = 0; lastMoveX = x; petAcc = 0f
                prevX = x; prevY = y; prevT = System.nanoTime(); velX = 0f; velY = 0f
                if (settingsOpen || tutStep == T_WELCOME || tutStep == T_DONE) {
                    pressedId = buttons.lastOrNull { it.rect.contains(x, y) }?.id
                    return true
                }
                if (tab == Tab.LAB && brewing > 0f && popup == null &&
                    hypot(x - width / 2f, y - flaskY()) < 190f * dp) {
                    stirring = true
                    lastStirA = kotlin.math.atan2(y - flaskY(), x - width / 2f)
                    pressedId = null
                    return true
                }
                val b = buttons.lastOrNull { it.rect.contains(x, y) }
                pressedId = b?.id
                val id = b?.id ?: ""
                if (popup == null && brewing <= 0f) {
                    if (id.startsWith("ing")) {
                        val i = id.removePrefix("ing").toInt()
                        if (lab.ingredients[i] - reserved(i) > 0) { dragKind = 1; dragIndex = i }
                    } else if (id.startsWith("food")) {
                        val f = id.removePrefix("food").toInt()
                        if (lab.foods[f] > 0) { dragKind = 2; dragIndex = f }
                    } else if (id.startsWith("dec") && id.removePrefix("dec").toIntOrNull() != null) {
                        val k = id.removePrefix("dec").toInt()
                        if (lab.decorOwned[k] > 0) { dragKind = 4; dragIndex = k }
                    } else if (b == null && tab == Tab.GARDEN && decorating && y < panelTop()) {
                        val d = placedAt(x, y)
                        if (d >= 0) { dragKind = 5; dragPlaced = d }
                    } else if (b == null && tab == Tab.GARDEN && !decorating && !photoMode && !(selected >= 0 && y >= panelTop())) {
                        val s = shroomAt(x, y)
                        if (s != null) { dragKind = 3; dragShroom = s.id }
                    }
                }
                if (b == null && dragKind == 0 && tab == Tab.GARDEN && popup == null && y > topBar && y < (if (selected >= 0 || decorating) panelTop() else navTop)) {
                    panning = true; panMoved = false; panStartCam = camX; camVel = 0f; panLastX = x; panLastT = System.nanoTime()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (stirring) {
                    val a = kotlin.math.atan2(y - flaskY(), x - width / 2f)
                    var d = a - lastStirA
                    if (d > Math.PI) d -= (2 * Math.PI).toFloat()
                    if (d < -Math.PI) d += (2 * Math.PI).toFloat()
                    d = d.coerceIn(-0.8f, 0.8f)
                    lastStirA = a
                    if (brewing > 0f) {
                        stirTotal += abs(d)
                        stirAngle += d
                        stirSound += abs(d)
                        if (stirSound > Math.PI.toFloat()) {
                            stirSound = 0f
                            sound.play(Fx.STIR, 0.5f, 0.8f + quality() * 0.7f)
                            if (quality() >= 1f) buzz(8)
                        }
                    }
                    return true
                }
                dragX = x; dragY = y
                if (panning) {
                    if (!panMoved && abs(x - downX) > 12f * dp) panMoved = true
                    if (panMoved) {
                        camX = (panStartCam - (x - downX) / (pxPerWorld() * 0.86f)).coerceIn(camMin(), camMax())
                        val nt = System.nanoTime(); val dtm = (nt - panLastT) / 1e9f
                        if (dtm > 0.008f) { camVel = camVel * 0.3f + (-(x - panLastX) / (pxPerWorld() * 0.86f) / dtm) * 0.7f; panLastX = x; panLastT = nt }
                    }
                    return true
                }
                // rubbing back and forth on a mushroom pets it
                if (dragKind == 3 && !dragging && !hugDone) {
                    val dxm = x - lastMoveX
                    if (abs(dxm) > 3f * dp) {
                        val sg = if (dxm > 0) 1 else -1
                        if (lastDxSign != 0 && sg != lastDxSign) revs++
                        lastDxSign = sg; lastMoveX = x
                        if (petting) {
                            petAcc += abs(dxm)
                            if (petAcc > 90f * dp) { petAcc = 0f; shroom(dragShroom)?.let { petStroke(it) } }
                        }
                    }
                    if (!petting && revs >= 2 && hypot(x - downX, y - downY) < 70f * dp) {
                        petting = true; pressedId = null
                        shroom(dragShroom)?.let { petStroke(it) }
                    }
                    if (petting) return true
                }
                if (dragging && dragKind == 3) {   // remember how fast the finger moves, for tossing
                    val nt = System.nanoTime()
                    val dtm = (nt - prevT) / 1_000_000_000f
                    if (dtm > 0.008f) {
                        velX = velX * 0.3f + (x - prevX) / dtm * 0.7f
                        velY = velY * 0.3f + (y - prevY) / dtm * 0.7f
                        prevX = x; prevY = y; prevT = nt
                    }
                }
                val threshold = if (dragKind == 3) 30f * dp else 12f * dp
                if (dragKind != 0 && !dragging && hypot(x - downX, y - downY) > threshold) {
                    dragging = true
                    pressedId = null
                    if (dragKind == 3) shroom(dragShroom)?.let {
                        it.held = true; it.moving = false; it.act = 0; it.pendingAct = 0; it.sleeping = false
                        sound.play(Fx.POP, 0.7f, 1.3f)
                        buzz(15)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                pointerDown = false
                if (stirring) { stirring = false; return true }
                if (panning) {
                    panning = false
                    if (panMoved) { panMoved = false; if (System.nanoTime() - panLastT > 80_000_000L) camVel = 0f; return true }
                    camVel = 0f
                }
                if (petting || hugDone) {
                    petting = false; hugDone = false; dragKind = 0; pressedId = null
                    return true
                }
                if (dragging) {
                    drop(x, y)
                } else {
                    val id = pressedId
                    val b = buttons.lastOrNull { it.id == id && it.rect.contains(x, y) }
                    if (b != null) {
                        sound.play(Fx.CLICK, 0.6f)
                        b.action()
                    } else if (id == null && popup == null && tab == Tab.GARDEN && !photoMode && !settingsOpen) {
                        tapGarden(x, y)
                    } else if (id == null && popup == null && tab == Tab.LAB && !settingsOpen) {
                        tapLab(x, y)
                    }
                }
                pressedId = null
                dragging = false; dragKind = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false; petting = false; hugDone = false; panning = false; panMoved = false
                stirring = false
                pressedId = null
                shroom(dragShroom)?.held = false
                dragging = false; dragKind = 0
            }
        }
        return true
    }

    /** Something was dragged and let go at (x, y). */
    private fun drop(x: Float, y: Float) {
        when (dragKind) {
            1 -> { // ingredient into the flask or a slot
                val slotHit = (0 until slotCount()).firstOrNull { k -> slotRect(k).let { r -> RectF(r).apply { inset(-12f * dp, -12f * dp) }.contains(x, y) } }
                val overFlask = hypot(x - width / 2f, y - flaskY()) < 95f * dp
                if (slotHit == null && !overFlask) return
                val target = slotHit ?: firstEmptySlot()
                if (target < 0) { toast("Both slots are full. Tap a slot to empty it"); return }
                slots[target] = dragIndex
                sound.play(Fx.BUBBLE, 0.9f, 0.9f + target * 0.2f)
                buzz(15)
                burst(width / 2f, flaskY() - 20f * dp, Data.INGREDIENTS[dragIndex].color, 16, P_SPARK)
                flaskYum = 0.8f
            }
            2 -> { // food onto a mushroom
                val s = shroomAt(x, y) ?: return
                selected = s.id
                feed(s, dragIndex)
            }
            4 -> { // place a decoration from the tray
                if (y >= panelTop() - 10f * dp) return
                if (lab.placed.size >= MAX_PLACED) { toast("That's a lot of decorations! (max $MAX_PLACED)"); return }
                val (gx, gz) = toWorld(x, y)
                lab.placed.add(Placed(dragIndex, gx, gz))
                lab.decorOwned[dragIndex]--
                sound.play(Fx.POP, 0.8f, 0.7f); buzz(15)
                burst(x, y, Color.rgb(200, 255, 220), 10, P_SPARK)
                markDirty()
            }
            5 -> { // move a placed decoration, or put it back in the tray
                val d = lab.placed.getOrNull(dragPlaced) ?: return
                if (y >= panelTop()) {
                    releaseFromDecor(dragPlaced, removed = true)
                    lab.placed.removeAt(dragPlaced)
                    lab.decorOwned[d.kind]++
                    sound.play(Fx.POP, 0.7f, 1.3f)
                } else {
                    releaseFromDecor(dragPlaced, removed = false)
                    val (gx, gz) = toWorld(x, y)
                    d.gx = gx; d.gz = gz
                    sound.play(Fx.POP, 0.8f, 0.7f)
                }
                markDirty()
            }
            3 -> { // put a mushroom down somewhere new
                val s = shroom(dragShroom) ?: return
                s.held = false
                val (gx, gz) = toWorld(x, y + shroomSize(s) * 0.3f)
                s.gx = gx; s.gz = gz; s.tx = gx; s.tz = gz
                s.idle = 2f + Random.nextFloat() * 2f
                val fresh = (System.nanoTime() - prevT) < 90_000_000L
                if (fresh && hypot(velX, velY) > 1000f * dp) { toss(s, velX, velY); markDirty(); return }
                sound.play(Fx.POP, 0.8f, 0.8f)
                val (sx, sy) = toScreen(gx, gz)
                burst(sx, sy, Color.rgb(170, 220, 180), 10, P_SPARK)
                markDirty()
            }
        }
    }

    /** A decoration moved or was put away: anyone using it hops off, and indexes stay correct. */
    private fun releaseFromDecor(idx: Int, removed: Boolean) {
        for (s in lab.garden) {
            if (s.actTarget == idx && (s.act > 0 || s.pendingAct > 0)) {
                s.act = 0; s.pendingAct = 0; s.sleeping = false; s.actTarget = -1
                s.tx = s.gx; s.tz = s.gz; s.idle = 1f
            } else if (removed && s.actTarget > idx) s.actTarget--
        }
    }

    /** Index of the placed decoration under a screen point, or -1. */
    private fun placedAt(x: Float, y: Float): Int {
        var best = -1; var bestZ = -1f
        for ((i, d) in lab.placed.withIndex()) {
            val (sx, sy) = toScreen(d.gx, d.gz)
            val s = 58f * dp * depth(d.gz)
            if (abs(x - sx) < s * 0.7f && y > sy - s * 1.1f && y < sy + 12f * dp && d.gz > bestZ) { best = i; bestZ = d.gz }
        }
        return best
    }

    /** The mushroom under a screen point, nearest (front-most) first. */
    private fun shroomAt(x: Float, y: Float): Shroom? {
        for (s in local().sortedByDescending { it.gz }) {
            if (s.act == ACT_INSIDE) continue
            val (sx, sy) = shroomScreen(s)
            val size = shroomSize(s)
            if (hypot(x - sx, y - (sy - size * 0.45f)) < max(size * 0.7f, 38f * dp)) return s
        }
        return null
    }

    private fun tapGarden(x: Float, y: Float) {
        if (selected >= 0 && y >= panelTop()) return
        critterAt(x, y)?.let { tapCritter(it); return }
        val s = shroomAt(x, y)
        if (s != null) {
            collect(s, lab.garden.indexOf(s))
            if (selected != s.id) { selected = s.id; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false; confirmRelease = false }
            poke(s)
            if (s.act == ACT_SLEEP || s.act == ACT_NAP) {
                s.act = 0; s.sleeping = false
                s.awakeUntil = System.currentTimeMillis() + 120_000L
                toast("${s.name} woke up. *yawn*")
            }
            s.idle = max(s.idle, 3f); s.moving = false
            if (lab.tutorial == 2) lab.tutorial = 3
            return
        }
        selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false; confirmRelease = false
    }

    // ---------- Loop ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1_000_000_000f, 0.05f)
        realDt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1_000_000_000f, 0.5f)
        lastFrame = now
        update(dt)
        buttons = buildButtons()
        render(canvas)
        postInvalidateOnAnimation()
    }

    private var qaAcc = 0f

    // ---------- Test hooks (used by the screenshot tests, never by the game) ----------
    internal var testForceFail = false
    internal fun testLab(): LabState = lab
    internal fun testFrame(seconds: Float = 0.05f) {
        var left = seconds
        while (left > 0f) { val d = min(left, 0.05f); realDt = d; update(d); left -= d }
        buttons = buildButtons()
    }
    internal fun testButtonIds(): List<String> { buttons = buildButtons(); return buttons.map { it.id } }
    internal fun testTap(id: String): Boolean {
        buttons = buildButtons()
        val b = buttons.lastOrNull { it.id == id } ?: return false
        b.action()
        buttons = buildButtons()
        return true
    }
    internal fun testSkipIntro() { splashT = 0f; holdSplash = false; tutStep = -1; prefs.edit().putInt("tutStep", -1).apply(); toasts.clear() }
    internal fun testProfSay(msg: String) = professorSay(msg, 30f)
    internal fun testSelect(id: Long) { selected = id; panelAnim = 1f }
    internal fun testBedtime() = bedtime()
    internal fun testCam(x: Float) { camX = x.coerceIn(camMin(), camMax()); camVel = 0f }
    internal fun testOffer(i: Int) { offerPack = i }
    internal fun testOfferSide(i: Int) { prefs.edit().putLong(offerKey(Data.PACKS[i]) + "_until", System.currentTimeMillis() + OFFER_MS).apply() }
    internal fun testThink(sh: Shroom) { sh.thought = thoughtFor(sh); sh.thoughtT = 3f }
    internal fun testSnowman(gx: Float, gz: Float) { snowmen.add(Snowman(gx, gz, 1f)) }
    internal fun testUpdateWidgetFrame(sh: Shroom) = renderWidgetFrame(sh, 1f)
    internal fun testRender(): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        buttons = buildButtons()
        render(Canvas(bmp))
        return bmp
    }
    internal fun testDrawSpecies(c: Canvas, sp: Int, x: Float, y: Float, size: Float, label: Boolean = true) {
        drawShroom(c, sp, x, y, size, 0, 0f, false, false)
        if (label) {
            text.textSize = 11f * dp; text.color = Color.WHITE
            c.drawText(Data.SPECIES[sp].name, x, y + 16f * dp, text)
        }
    }
    internal fun testDrawIngredient(c: Canvas, i: Int, x: Float, y: Float, r: Float) {
        drawIngredient(c, i, x, y, r)
        text.textSize = 11f * dp; text.color = Color.WHITE
        c.drawText(Data.INGREDIENTS[i].name, x, y + r * 1.3f + 16f * dp, text)
    }

    private fun update(dt: Float) {
        t += dt
        if (qaMode) { qaAcc += max(dt, realDt); if (qaAcc >= 0.5f) { qaAcc = 0f; qaReport() } }
        updateJam(dt)
        updateSeason(dt)
        updateCamera(dt)
        tickAcc += dt
        if (tickAcc >= 1f) {
            tickAcc = 0f; lab.tick(); markDirty()
            lab.updateQuest()?.let { toast(it, DUST); sound.play(Fx.DISCOVER); buzz(40) }
            val newStickers = lab.checkStickers()
            if (newStickers.size > 2) toast("⭐ ${newStickers.size} new stickers! Journal → AWARDS → Sticker book", DUST)
            else for (i in newStickers) toast("⭐ New sticker: ${Data.STICKERS[i].icon} ${Data.STICKERS[i].name}!", DUST)
            checkOffers()
            if (merchantHere() && prefs.getInt("merchantDay", 0) != LabState.dayKey()) {
                prefs.edit().putInt("merchantDay", LabState.dayKey()).apply()
                toast("🪲 A traveling merchant is visiting the garden today!", MINT)
            }
            updateWants(System.currentTimeMillis())
            updateAmbience()
            for (a in lab.checkAchievements()) {
                toast("🏆 ${Data.ACHIEVEMENTS[a].name}!  +${Data.ACHIEVEMENTS[a].reward} ✦", DUST)
                sound.play(Fx.DISCOVER, 0.8f, 1.2f)
            }
        }
        saveAcc += dt
        if (saveAcc >= 5f) { saveAcc = 0f; if (dirty) { lab.save(); dirty = false } }

        if (toasts.isNotEmpty()) {
            toastTime -= dt
            if (toastTime <= 0f) { toasts.removeFirst(); toastTime = 2.4f }
        }

        if (flashT > 0f) flashT -= dt
        popup?.let { p ->
            val before = popupT
            popupT += dt
            if (before < 0.9f && popupT >= 0.9f) reveal(p)
        }
        if (brewing > 0f) {
            brewing -= dt
            val r = 62f * dp
            val intensity = 0.4f + 0.6f * quality()
            if (Random.nextFloat() < 0.3f + 0.5f * intensity) {   // bubbles rising out of the neck
                particles.add(Particle(width / 2f + (Random.nextFloat() - 0.5f) * 20f * dp, flaskY() - r * 1.5f,
                    (Random.nextFloat() - 0.5f) * 50f * dp, -(60f + Random.nextFloat() * 120f) * dp,
                    1.2f, 1.2f, if (Random.nextBoolean()) Color.WHITE else liquidColor(), P_BUBBLE))
            }
            if (quality() > 0.5f && Random.nextFloat() < 0.15f) {   // sparks for a great stir
                val a = Random.nextFloat() * 6.283f
                particles.add(Particle(width / 2f + cos(a) * r, flaskY() + sin(a) * r, cos(a) * 90f * dp, sin(a) * 90f * dp,
                    0.4f, 0.4f, DUST, P_SPARK))
            }
            if (brewing <= 0f) { stirring = false; if (labMode == 0) finishBrew() else finishCross() }
        }

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            if (p.type == P_DUST) {
                val k = 1f - p.life / p.maxLife
                if (k > 0.35f) {   // after a little pop outward, fly to the dust counter
                    p.x += (p.tx - p.x) * min(1f, dt * 9f)
                    p.y += (p.ty - p.y) * min(1f, dt * 9f)
                } else { p.x += p.vx * dt; p.y += p.vy * dt }
            } else {
                p.x += p.vx * dt; p.y += p.vy * dt
                p.vx *= 0.95f; p.vy *= if (p.type == P_HEART) 0.97f else 0.95f
            }
        }

        for (s in lab.home()) {
            if (s.jiggle > 0f) s.jiggle = max(0f, s.jiggle - dt * 2.2f)
            if (s.thoughtT > 0f) s.thoughtT -= dt
            if (s.exprT > 0f) { s.exprT -= dt; if (s.exprT <= 0f) s.expr = 0 }
            if (s.waveT > 0f) s.waveT -= dt
            if (s.spinT > 0f) s.spinT = max(0f, s.spinT - dt)
            updateAir(s, dt)
            if (!s.held && !s.airborne() && s.act == 0) updateIdleAnim(s, dt)
            // open wide when food is dragged close
            if (dragging && dragKind == 2) {
                val (sx, sy) = shroomScreen(s)
                if (hypot(dragX - sx, dragY - (sy - shroomSize(s) * 0.4f)) < 110f * dp) express(s, E_CHOMP, 0.15f)
            }
        }
        // a long press on a mushroom is a hug
        if (pointerDown && dragKind == 3 && !dragging && !petting && !hugDone && t - downT > 0.5f &&
            hypot(dragX - downX, dragY - downY) < 16f * dp) {
            hugDone = true; pressedId = null
            shroom(dragShroom)?.let { hug(it) }
        }
        if (splashT > 0f && !(holdSplash && splashT < 0.7f)) splashT -= max(dt, realDt)
        updateMini(dt)
        if (profSayT > 0f) profSayT -= dt
        if (flaskYum > 0f) flaskYum -= dt
        for (k in 0 until 6) if (jarWobble[k] > 0f) jarWobble[k] = max(0f, jarWobble[k] - dt * 1.5f)
        if (professor.jiggle > 0f) professor.jiggle = max(0f, professor.jiggle - dt * 2.2f)
        if (professor.exprT > 0f) { professor.exprT -= dt; if (professor.exprT <= 0f) professor.expr = 0 }
        shownDust += (lab.dust - shownDust) * min(1f, dt * 6f)
        if (abs(lab.dust - shownDust) < 0.5f) shownDust = lab.dust.toFloat()
        if (cardFor >= 0) cardAnim += dt * 3f else cardAnim = 0f
        if (previewKind >= 0) previewAnim = min(1f, previewAnim + dt * 3.5f)
        val panelKey = if (decorating) -2L else selected
        if (panelKey != lastPanelKey) { if (panelKey != -1L) panelAnim = 0f; lastPanelKey = panelKey }
        panelAnim = min(1f, panelAnim + dt * 3.2f)
        updateWalkers(dt)
        updateCritters(dt)
        updateBlooms(dt)
        updateBalls(dt)
        sound.update(dt, LabState.darkness() > 0.6f)
        updateTutorial()

        // Random blinking
        if (Random.nextFloat() < dt * 1.2f && lab.garden.isNotEmpty()) blinkAt[lab.garden.random().id] = t

        // messages from the simulation (expeditions, auto-feeder) and new achievements
        while (lab.events.isNotEmpty()) toast(lab.events.removeAt(0), MINT)
    }

    // ---------- Rendering ----------

    private fun render(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (mini > 0) {
            drawMini(c, w, h)
            drawParticles(c)
            drawToast(c, w, h)
            if (splashT > 0f) drawSplash(c, w, h)
            return
        }
        c.drawColor(BG)
        // soft lab glow at the top
        fill.color = BG_TOP
        c.drawRect(0f, 0f, w, h * 0.5f, fill)
        fill.color = Color.argb(40, 255, 120, 200)
        c.drawCircle(w * 0.8f, h * 0.12f, w * 0.5f, fill)
        fill.color = Color.argb(30, 120, 200, 255)
        c.drawCircle(w * 0.1f, h * 0.35f, w * 0.45f, fill)

        if (photoMode) {
            drawGarden(c, w, h)
            drawParticles(c)
            drawPhotoFrame(c, w)
            if (!capturing) {
                fill.color = Color.rgb(20, 14, 38); c.drawRect(0f, navTop, w, h, fill)
                for (b in buttons) drawButton(c, b)
                drawToast(c, w, h)
            }
            return
        }
        when (tab) {
            Tab.GARDEN -> drawGarden(c, w, h)
            Tab.LAB -> drawLab(c, w, h)
            Tab.DEX -> drawDex(c, w, h)
            Tab.SHOP -> drawShop(c, w, h)
            Tab.JOURNAL -> drawJournal(c, w, h)
        }

        drawTopBar(c, w)
        // nav background
        fill.color = Color.rgb(20, 14, 38)
        c.drawRect(0f, navTop, w, h, fill)
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawRect(0f, navTop, w, navTop + 1f * dp, fill)

        if (!settingsOpen && cardFor < 0 && familyFor < 0 && !merchantOpen && offerPack < 0 && previewKind < 0) {
            c.save(); c.clipRect(0f, 0f, w, navTop)
            for (b in buttons) if (!b.id.startsWith("tut_") && !b.id.startsWith("nav")) drawButton(c, b)
            c.restore()
            for (b in buttons) if (b.id.startsWith("nav")) drawButton(c, b)
        }
        if (tab == Tab.LAB && !settingsOpen && cardFor < 0) drawProfBubble(c, w)
        drawParticles(c)
        if (dragging) drawDragged(c)
        popup?.let { drawPopup(c, w, h, it) ; for (b in buttons) if (!b.id.startsWith("tut_")) drawButton(c, b) }
        drawTutorial(c, w, h)
        if (settingsOpen) drawSettings(c, w, h)
        if (offerPack >= 0 && previewKind < 0) {
            fill.color = Color.argb(205, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            drawOffer(c, w, h)
            for (b in buttons) drawButton(c, b)
        }
        if (previewKind >= 0) {
            fill.color = Color.argb(215, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            drawPreview(c, w, h)
            for (b in buttons) drawButton(c, b)
        }
        if (merchantOpen) {
            fill.color = Color.argb(200, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            drawMerchantPanel(c, w, h)
            for (b in buttons) drawButton(c, b)
        }
        if (familyFor >= 0) shroom(familyFor)?.let { s ->
            fill.color = Color.argb(215, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            drawFamily(c, w, h, s)
            for (b in buttons) drawButton(c, b)
        } ?: run { familyFor = -1 }
        if (cardFor >= 0) shroom(cardFor)?.let { s ->
            fill.color = Color.argb(200, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            val r = cardRect(w, h)
            val k = easeOutBack(min(1f, cardAnim))
            c.save(); c.scale(0.7f + 0.3f * k, 0.7f + 0.3f * k, r.centerX(), r.centerY())
            drawProfileCard(c, r, s)
            c.restore()
            for (b in buttons) drawButton(c, b)
        } ?: run { cardFor = -1 }
        if (flashT > 0f) {
            fill.color = Color.WHITE; fill.alpha = (255 * (flashT / 0.35f)).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, fill); fill.alpha = 255
        }
        drawToast(c, w, h)
        if (splashT > 0f) drawSplash(c, w, h)
    }

    private fun drawDragged(c: Canvas) {
        when (dragKind) {
            1 -> {
                groundShadow(c, dragX, dragY + 10f * dp, 26f * dp, 90)
                drawIngredient(c, dragIndex, dragX, dragY - 36f * dp, 24f * dp)
            }
            2 -> {
                drawFood(c, dragIndex, dragX, dragY - 36f * dp, 22f * dp)
                shroomAt(dragX, dragY)?.let { s ->
                    val (sx, sy) = toScreen(s.gx, s.gz)
                    stroke.color = MINT; stroke.strokeWidth = 3f * dp
                    c.drawCircle(sx, sy - shroomSize(s) * 0.45f, shroomSize(s) * 0.75f, stroke)
                }
            }
            4, 5 -> {
                val kind = if (dragKind == 4) dragIndex else lab.placed.getOrNull(dragPlaced)?.kind ?: return
                val overTray = dragY >= panelTop()
                if (!overTray) {
                    val (gx, gz) = toWorld(dragX, dragY)
                    val (sx, sy) = toScreen(gx, gz)
                    drawDecor(c, kind, sx, sy, depth(gz))
                } else {
                    drawDecor(c, kind, dragX, dragY, 0.7f)
                    if (dragKind == 5) { text.textSize = 12f * dp; c.drawText("put away", dragX, dragY + 18f * dp, text) }
                }
            }
            3 -> shroom(dragShroom)?.let { s ->
                val size = shroomSize(s) * 1.1f
                val (gx, gz) = toWorld(dragX, dragY + size * 0.3f)
                val (sx, sy) = toScreen(gx, gz)
                groundShadow(c, sx, sy, size * 0.5f, 90)
                drawShroom(c, s.species, dragX, dragY + size * 0.3f, size, 2, 0f, false, false, held = true, shiny = s.shiny, who = s)
            }
        }
    }

    private fun drawTopBar(c: Canvas, w: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, topBar, Color.argb(235, 34, 22, 66), Color.argb(200, 26, 17, 50), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, topBar, fill)
        fill.shader = null
        fill.color = Color.argb(70, 0, 0, 0); c.drawRect(0f, topBar, w, topBar + 3f * dp, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 25f * dp
        text.color = Color.WHITE
        val title = when (tab) { Tab.GARDEN -> if (area == 1) "Cave" else "Garden"; Tab.LAB -> "Lab"; Tab.DEX -> "Shroomdex"; Tab.SHOP -> "Shop"; Tab.JOURNAL -> "Journal" }
        c.drawText(title, 18f * dp, 43f * dp, text)
        // spore dust pill
        text.textSize = 18f * dp
        if (shownDust < 0f) shownDust = lab.dust.toFloat()
        val label = "${shownDust.toInt()}"
        val pw = text.measureText(label) + 46f * dp
        val pr = RectF(w - 62f * dp - pw, 16f * dp, w - 62f * dp, 50f * dp)
        fill.color = Color.argb(110, 0, 0, 0); c.drawRoundRect(pr, 17f * dp, 17f * dp, fill)
        stroke.color = Color.argb(90, 255, 214, 110); stroke.strokeWidth = 1.5f * dp; c.drawRoundRect(pr, 17f * dp, 17f * dp, stroke)
        fill.color = DUST
        drawSparkle(c, pr.left + 18f * dp, pr.centerY(), 4f * dp)
        text.color = Color.WHITE
        c.drawText(label, pr.left + 32f * dp, pr.centerY() + 6.5f * dp, text)
        text.textAlign = Paint.Align.CENTER
    }

    // ----- Garden (a little 3D-ish stage: gx = left/right, gz = far/near) -----

    private fun groundTop() = height * 0.25f
    private fun groundBottom() = height * 0.62f
    private fun horizon() = height * 0.215f
    private fun depth(gz: Float) = 0.62f + 0.38f * gz

    /** Which part of the wide meadow is on screen: the world x at the screen's center. */
    private var camX = 0.5f
    private var camVel = 0f
    private fun camMin() = 0.5f
    private fun camMax() = LabState.WORLD - 0.5f
    private fun pxPerWorld() = width * 0.86f

    private fun toScreen(gx: Float, gz: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val x = w / 2 + (gx - camX) * w * 0.86f * (0.72f + 0.28f * gz)
        val y = groundTop() + (groundBottom() - groundTop()) * gz
        return Pair(x, y)
    }

    private fun toWorld(x: Float, y: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val gz = ((y - groundTop()) / (groundBottom() - groundTop())).coerceIn(0f, 1f)
        val gx = ((x - w / 2) / (w * 0.86f * (0.72f + 0.28f * gz)) + camX).coerceIn(0.05f, LabState.WORLD - 0.05f)
        return Pair(gx, gz)
    }

    private fun shroomPos(i: Int): Pair<Float, Float> {
        val s = lab.garden.getOrNull(i) ?: return Pair(width / 2f, height * 0.4f)
        return toScreen(s.gx, s.gz)
    }

    private fun shroomSize(s: Shroom): Float {
        val base = min(width * 0.21f, 92f * dp)
        return base * floatArrayOf(0.45f, 0.65f, 0.85f, 1f)[s.stage()] * depth(s.gz) * s.size
    }

    /**
     * Mushroom life: wander, visit decorations (sit on benches, splash in ponds, nap on stumps,
     * sniff flowers, pop into the toadstool house, dance under fairy lights), chat with friends,
     * gather by lanterns at night and sleep when it's dark.
     */
    private fun updateWalkers(dt: Float) {
        val now = System.currentTimeMillis()
        val dark = LabState.darkness()
        val home = local()
        for (s in home) {
            if (s.held) continue
            if (s.airborne()) { s.moving = false; continue }
            if (popup != null) { s.moving = false; continue }

            // Busy doing something
            if (s.act > 0) {
                s.moving = false
                s.actTimer -= dt
                s.sleeping = s.act == ACT_SLEEP || s.act == ACT_NAP
                actEffects(s, dt)
                if (s.actTimer <= 0f) endAct(s)
                continue
            }
            s.sleeping = false
            if (s.id == selected) { s.moving = false; continue }

            if (s.idle > 0f) {
                s.idle -= dt; s.moving = false
                // fall asleep at night (or now and then if Sleepy)
                val drowsy = (dark > 0.6f && now > s.awakeUntil && Random.nextFloat() < dt * (if (s.trait == 0) 0.5f else 0.25f)) ||
                    (s.trait == 0 && Random.nextFloat() < dt * 0.02f)
                if (drowsy) startAct(s, ACT_SLEEP, -1)
                continue
            }
            val dx = s.tx - s.gx; val dz = s.tz - s.gz
            val d = hypot(dx, dz)
            if (d < 0.012f) {
                s.moving = false
                if (s.pendingAct > 0) { startAct(s, s.pendingAct, s.actTarget); s.pendingAct = 0; continue }
                if (Random.nextFloat() < 0.45f) s.idle = 1f + Random.nextFloat() * 4f
                chooseNext(s, home, dark)
                continue
            }
            val speed = (0.05f + 0.015f * s.stage()) * (if (s.mood() == 2) 0.5f else 1f) *
                    (when (s.trait) { 0 -> 0.7f; 1 -> 1.35f; else -> 1f })
            val step = min(d, speed * dt)
            s.gx += dx / d * step; s.gz += dz / d * step
            s.moving = true
            s.phase += dt * (if (s.stage() == 0 || s.trait == 1) 6f else 9f)
            if (abs(dx) > 0.004f) s.facing = if (dx > 0) 1f else -1f
        }
        // gently push apart mushrooms standing on top of each other (shy ones keep more space)
        for (i in home.indices) for (j in i + 1 until home.size) {
            val a = home[i]; val b = home[j]
            if (a.held || b.held || a.act == ACT_INSIDE || b.act == ACT_INSIDE) continue
            if (a.act == ACT_CHAT && b.act == ACT_CHAT && hypot(a.gx - b.gx, a.gz - b.gz) > 0.1f) continue
            val gap = if (a.trait == 2 || b.trait == 2) 0.22f else 0.14f
            val dx = b.gx - a.gx; val dz = (b.gz - a.gz) * 1.6f
            val d = hypot(dx, dz)
            if (d < gap && d > 0.0001f) {
                val push = (gap - d) * 0.5f * min(1f, dt * 6f)
                if (a.act == 0) { a.gx -= dx / d * push; a.gz -= dz / d * push * 0.6f }
                if (b.act == 0) { b.gx += dx / d * push; b.gz += dz / d * push * 0.6f }
            }
        }
        for (s in home) { s.gx = s.gx.coerceIn(0.05f, LabState.WORLD - 0.05f); s.gz = s.gz.coerceIn(0f, 1f) }
        // pets trot after their friend
        for (s in home) {
            if (s.pet < 0) continue
            if (s.petGx.isNaN()) { s.petGx = s.gx - 0.06f; s.petGz = s.gz + 0.02f }
            val tx = (s.gx - s.facing * 0.075f).coerceIn(0.03f, LabState.WORLD - 0.03f); val tz = (s.gz + 0.015f).coerceIn(0f, 1f)
            val dx = tx - s.petGx; val dz = tz - s.petGz
            val d = hypot(dx, dz)
            val speed = if (s.pet == 0) 0.05f else 0.14f
            if (d > 0.01f) { val st = min(d, speed * dt); s.petGx += dx / d * st; s.petGz += dz / d * st; s.petPhase += dt * 8f }
        }
    }

    /** Decide where to wander next: a decoration, a friend, or just somewhere. */
    private fun chooseNext(s: Shroom, home: List<Shroom>, dark: Float) {
        if (s.stage() <= 1 && s.parentId >= 0) {
            val p = home.firstOrNull { it.id == s.parentId && it.act != ACT_INSIDE }
            if (p != null && Random.nextFloat() < 0.45f) {   // waddle after mom or dad like a duckling
                s.tx = (p.gx - p.facing * 0.12f).coerceIn(0.05f, LabState.WORLD - 0.05f); s.tz = (p.gz + 0.03f).coerceIn(0f, 1f)
                s.idle = 0f
                return
            }
        }
        // chase a butterfly now and then
        val bfly = critters.firstOrNull { it.type == 0 && it.state == 0 && it.gx in 0.05f..(LabState.WORLD - 0.05f) && abs(it.gx - s.gx) < 0.5f }
        if (bfly != null && Random.nextFloat() < 0.15f) {
            s.tx = bfly.gx; s.tz = bfly.gz.coerceIn(0f, 1f)
            express(s, E_SPARKLE, 2f)
            return
        }
        // jump in puddles on rainy days
        if (area == 0 && LabState.raining() && Random.nextFloat() < 0.18f) {
            val (px, pz) = PUDDLES.minByOrNull { abs(it.first - s.gx) + Random.nextFloat() * 0.3f }!!
            s.tx = (px + (Random.nextFloat() - 0.5f) * 0.04f).coerceIn(0.05f, LabState.WORLD - 0.05f); s.tz = pz
            s.pendingAct = ACT_PUDDLE; s.actTarget = -1
            return
        }
        // build a little snowman when it's snowy
        if (snowy() && snowmen.size < 4 && Random.nextFloat() < 0.07f) {
            snowmen.add(Snowman((s.gx + s.facing * 0.07f).coerceIn(0.06f, LabState.WORLD - 0.06f), (s.gz + 0.01f).coerceIn(0f, 1f)))
            startAct(s, ACT_SNOWMAN, snowmen.size - 1)
            return
        }
        // cozy up to the campfire at night
        val fireIdx = if (area != 0) null else lab.placed.indices.firstOrNull { lab.placed[it].kind == 15 }
        if (fireIdx != null && dark > 0.5f && Random.nextFloat() < 0.5f) {
            val d = lab.placed[fireIdx]
            val a = Random.nextFloat() * 6.283f
            s.tx = (d.gx + cos(a) * 0.16f).coerceIn(0.05f, LabState.WORLD - 0.05f); s.tz = (d.gz + sin(a) * 0.06f).coerceIn(0f, 1f)
            s.pendingAct = ACT_CAMPFIRE; s.actTarget = fireIdx
            return
        }
        fun taken(i: Int, act: Int) = home.any { it !== s && it.actTarget == i && (it.act == act || it.pendingAct == act) }
        val usable = if (area != 0) emptyList() else lab.placed.indices.filter {
            when (lab.placed[it].kind) {
                7, 4, 1, 6, 9, 11, 13, 15, 25 -> true
                10, 24 -> !taken(it, ACT_NAP)
                12 -> !taken(it, ACT_SWING)
                14 -> !taken(it, ACT_BOUNCE)
                0 -> dark > 0.5f
                else -> false
            }
        }
        val decoChance = if (s.trait == 4) 0.6f else if (s.trait == 1) 0.5f else 0.35f
        val r = Random.nextFloat()
        if (usable.isNotEmpty() && r < decoChance) {
            val di = usable.random()
            val d = lab.placed[di]
            val (ox, oz, act) = when (d.kind) {
                7 -> Triple((Random.nextFloat() - 0.5f) * 0.08f, 0.01f, ACT_SIT)
                4, 25 -> Triple(if (Random.nextBoolean()) -0.11f else 0.11f, 0.02f, ACT_SPLASH)
                10, 24 -> Triple(0f, 0.008f, ACT_NAP)
                1 -> Triple(0.07f, 0.02f, ACT_SNIFF)
                6 -> Triple(0f, 0.025f, ACT_INSIDE)
                9 -> Triple((Random.nextFloat() - 0.5f) * 0.14f, 0.03f, ACT_DANCE)
                11 -> Triple(if (Random.nextBoolean()) -0.04f else 0.04f, 0f, ACT_KICK)
                12 -> Triple(0f, 0.015f, ACT_SWING)
                13 -> Triple(-0.06f, 0.015f, ACT_SLIDE)
                14 -> Triple(0f, 0.012f, ACT_BOUNCE)
                15 -> { val a = Random.nextFloat() * 6.283f; Triple(cos(a) * 0.1f, sin(a) * 0.06f, ACT_CAMPFIRE) }
                else -> Triple(if (Random.nextBoolean()) -0.07f else 0.07f, 0.03f, ACT_GATHER)
            }
            s.tx = (d.gx + ox).coerceIn(0.05f, LabState.WORLD - 0.05f); s.tz = (d.gz + oz).coerceIn(0f, 1f)
            s.pendingAct = act; s.actTarget = di
            return
        }
        val bf = home.firstOrNull { it.id == s.bestie && it.act == 0 && it.pendingAct == 0 && !it.held && it.id != selected }
        if ((s.trait != 2 || bf != null) && r < decoChance + (if (bf != null) 0.22f else 0.1f)) {
            val friend = bf?.takeIf { Random.nextFloat() < 0.5f }
                ?: home.filter { it !== s && it.act == 0 && it.pendingAct == 0 && it.trait != 2 && !it.held && it.id != selected }.randomOrNull()
            if (friend != null) {
                val mx = (s.gx + friend.gx) / 2; val mz = (s.gz + friend.gz) / 2
                s.tx = (mx - 0.08f).coerceIn(0.05f, LabState.WORLD - 0.05f); s.tz = mz
                friend.tx = (mx + 0.08f).coerceIn(0.05f, LabState.WORLD - 0.05f); friend.tz = mz
                s.pendingAct = ACT_CHAT; friend.pendingAct = ACT_CHAT
                s.actTarget = -1; friend.actTarget = -1
                friend.idle = 0f
                return
            }
        }
        s.tx = (s.gx + (Random.nextFloat() - 0.5f) * 0.9f).coerceIn(0.08f, LabState.WORLD - 0.08f)
        s.tz = (s.gz + (Random.nextFloat() - 0.5f) * 0.6f).coerceIn(0.05f, 0.98f)
    }

    private fun startAct(s: Shroom, act: Int, target: Int) {
        s.act = act; s.actTarget = target
        s.actTimer = when (act) {
            ACT_SIT -> 6f + Random.nextFloat() * 8f
            ACT_SPLASH -> 4f + Random.nextFloat() * 3f
            ACT_NAP -> 8f + Random.nextFloat() * 10f
            ACT_SNIFF -> 3f + Random.nextFloat() * 3f
            ACT_INSIDE -> 8f + Random.nextFloat() * 8f
            ACT_DANCE -> 5f + Random.nextFloat() * 4f
            ACT_CHAT -> 3f + Random.nextFloat() * 3f
            ACT_GATHER -> 10f + Random.nextFloat() * 12f
            ACT_KICK -> 0.6f
            ACT_SWING -> 8f + Random.nextFloat() * 8f
            ACT_SLIDE -> 12f
            ACT_BOUNCE -> 6f + Random.nextFloat() * 5f
            ACT_CAMPFIRE -> 12f + Random.nextFloat() * 14f
            ACT_JAM -> max(1f, jamT)
            ACT_PUDDLE -> 4f + Random.nextFloat() * 3f
            ACT_SNOWMAN -> 8f
            else -> if (s.trait == 0) 40f + Random.nextFloat() * 60f else 20f + Random.nextFloat() * 40f   // sleep
        }
        if (act == ACT_CHAT) {
            val other = local().filter { it !== s && it.act == ACT_CHAT || (it !== s && it.pendingAct == ACT_CHAT) }
                .minByOrNull { hypot(it.gx - s.gx, it.gz - s.gz) }
            if (other != null) {
                s.facing = if (other.gx > s.gx) 1f else -1f
                val key = listOf(s.id, other.id).sorted().joinToString("-") + "-" + LabState.dayKey()
                if (chatted.add(key)) lab.addDiary("${s.name} and ${other.name} had a little chat.")
                if (lab.befriend(s, other)) {
                    toast("💕 ${s.name} and ${other.name} are now best friends!", ACCENT)
                    sound.play(Fx.DISCOVER, 0.6f)
                    val (bx, by) = toScreen((s.gx + other.gx) / 2, (s.gz + other.gz) / 2)
                    burst(bx, by - 60f * dp, ACCENT, 14, P_HEART)
                    express(s, E_HEART, 2.5f); express(other, E_HEART, 2.5f)
                }
            }
        }
        if (act == ACT_SLEEP && s.bestie >= 0) local().firstOrNull { it.id == s.bestie && it.act == 0 && it.pendingAct == 0 && !it.held && it.id != selected }?.let { b ->
            b.tx = (s.gx + 0.12f).coerceIn(0.05f, LabState.WORLD - 0.05f); b.tz = s.gz; b.pendingAct = ACT_SLEEP; b.actTarget = -1; b.idle = 0f
        }
        if (act == ACT_SNIFF) lab.placed.getOrNull(target)?.let { s.facing = if (it.gx > s.gx) 1f else -1f }
        if (act == ACT_INSIDE) sound.play(Fx.POP, 0.4f, 0.8f)
        if (act == ACT_SWING || act == ACT_SLIDE || act == ACT_BOUNCE) { s.phase = 0f; express(s, E_GIGGLE, 1.5f) }
        if (act == ACT_KICK) lab.placed.getOrNull(target)?.let { ball ->
            val v = ballVel.getOrPut(ball) { floatArrayOf(0f, 0f) }
            val dx = ball.gx - s.gx; val dz = ball.gz - s.gz
            val d = max(0.01f, hypot(dx, dz))
            v[0] = dx / d * 0.45f + (Random.nextFloat() - 0.5f) * 0.1f; v[1] = dz / d * 0.25f
            s.jiggle = 0.6f
            express(s, E_GIGGLE, 1.2f)
            sound.play(Fx.BOING, 0.4f, 1.7f)
        }
        if (act == ACT_CAMPFIRE) lab.placed.getOrNull(target)?.let { s.facing = if (it.gx > s.gx) 1f else -1f }
    }

    private fun endAct(s: Shroom) {
        if (s.act != ACT_SLEEP) s.happy = min(100f, s.happy + 3f)
        if (s.act == ACT_INSIDE) sound.play(Fx.POP, 0.4f, 1.2f)
        s.act = 0; s.sleeping = false
        s.idle = 0.5f + Random.nextFloat()
        s.tx = s.gx; s.tz = s.gz
    }

    /** Little particles for whatever a mushroom is doing. */
    private fun actEffects(s: Shroom, dt: Float) {
        val (x, y) = toScreen(s.gx, s.gz)
        val size = shroomSize(s)
        when (s.act) {
            ACT_SLEEP, ACT_NAP -> if (Random.nextFloat() < dt * 0.8f)
                particles.add(Particle(x + size * 0.3f, y - size * 0.9f - actLift(s), 10f * dp, -22f * dp, 2f, 2f, Color.WHITE, P_Z))
            ACT_SPLASH -> {
                s.phase += dt * 10f
                if (Random.nextFloat() < dt * 6f) {
                    val a = -1.57f + (Random.nextFloat() - 0.5f) * 2f
                    particles.add(Particle(x, y - 4f * dp, cos(a) * 90f * dp, sin(a) * 140f * dp, 0.6f, 0.6f, Color.rgb(150, 210, 255), P_SPARK))
                }
            }
            ACT_SNIFF -> if (Random.nextFloat() < dt * 0.7f)
                particles.add(Particle(x, y - size * 0.8f, 0f, -30f * dp, 1.2f, 1.2f, ACCENT, P_HEART))
            ACT_DANCE -> {
                s.phase += dt * 9f
                if (Random.nextFloat() < dt * 1.2f)
                    particles.add(Particle(x + (Random.nextFloat() - 0.5f) * size, y - size * 1.1f, (Random.nextFloat() - 0.5f) * 30f * dp, -35f * dp,
                        1.5f, 1.5f, Color.rgb(255, 230, 140), P_NOTE))
            }
            ACT_CHAT -> s.phase += dt
            ACT_PUDDLE -> {
                val before = sin(s.phase)
                s.phase += dt * 7f
                if (before > 0f && sin(s.phase) <= 0f) {   // landed: splash!
                    for (k in 0 until 6) { val a = -1.57f + (Random.nextFloat() - 0.5f) * 2.4f
                        particles.add(Particle(x, y - 2f * dp, cos(a) * 80f * dp, sin(a) * 120f * dp, 0.5f, 0.5f, Color.rgb(150, 200, 255), P_SPARK)) }
                    sound.play(Fx.BUBBLE, 0.25f, 1.3f)
                    if (Random.nextFloat() < 0.3f) express(s, E_GIGGLE, 0.8f)
                }
            }
            ACT_SNOWMAN -> {
                s.phase += dt * 6f
                snowmen.getOrNull(s.actTarget)?.let { it.prog = min(1f, it.prog + dt / 7.5f) }
                if (Random.nextFloat() < dt * 5f) particles.add(Particle(x + s.facing * size * 0.4f, y - size * 0.2f, (Random.nextFloat() - 0.5f) * 40f * dp, -50f * dp, 0.6f, 0.6f, Color.WHITE, P_SPARK))
            }
            ACT_SWING -> s.phase += dt
            ACT_SLIDE -> {
                val before = s.phase % 4f
                s.phase += dt
                if (before < 1.6f && s.phase % 4f >= 1.6f) { sound.play(Fx.WHEE, 0.5f, voicePitch(s)); express(s, E_GIGGLE, 1f) }
            }
            ACT_BOUNCE -> {
                val before = (s.phase * 3f) % 3.1416f
                s.phase += dt
                if ((s.phase * 3f) % 3.1416f < before) { sound.play(Fx.BOING, 0.35f, voicePitch(s)); s.jiggle = 0.5f }
            }
            ACT_CAMPFIRE -> if (LabState.darkness() > 0.5f && Random.nextFloat() < dt * 0.6f)
                particles.add(Particle(x, y - size * 1.1f, (Random.nextFloat() - 0.5f) * 20f * dp, -30f * dp, 1.6f, 1.6f, Color.rgb(255, 220, 150), P_NOTE))
        }
    }

    /** How far up a mushroom is lifted when sitting on a bench or napping on a stump. */
    private fun actLift(s: Shroom): Float {
        if (s.act == ACT_PUDDLE) return abs(sin(s.phase)) * shroomSize(s) * 0.22f
        val d = lab.placed.getOrNull(s.actTarget) ?: return 0f
        return when (s.act) {
            ACT_SIT -> 58f * dp * depth(d.gz) * 0.3f
            ACT_NAP -> 58f * dp * depth(d.gz) * (if (d.kind == Data.COZY_BED) 0.2f else 0.44f)
            ACT_SPLASH, ACT_DANCE -> abs(sin(s.phase)) * shroomSize(s) * 0.12f
            ACT_BOUNCE -> 58f * dp * depth(d.gz) * 0.2f + abs(sin(s.phase * 3f)) * 58f * dp * depth(d.gz) * 0.9f
            else -> 0f
        }
    }

    /** Grass tufts, pebbles and flowers on a jittered grid, so they're spread evenly across the meadow. */
    private val decor by lazy {
        val r = Random(42)
        val out = ArrayList<Triple<Float, Float, Int>>()
        val cols = (LabState.WORLD * 9).toInt(); val rows = 5
        for (row in 0 until rows) for (col in 0 until cols) {
            if (r.nextFloat() < 0.25f) continue
            val gx = (col + 0.2f + r.nextFloat() * 0.6f) / cols * LabState.WORLD
            val gz = (row + 0.2f + r.nextFloat() * 0.6f) / rows
            val roll = r.nextFloat()
            out.add(Triple(gx, gz, if (roll < 0.62f) 0 else if (roll < 0.78f) 1 else 2))
        }
        out
    }

    private fun drawGardenScene(c: Canvas, w: Float, h: Float) {
        val hz = horizon()
        val th = Data.THEMES[lab.theme]
        // sky
        fill.shader = LinearGradient(0f, 0f, 0f, hz, th.skyTop, th.skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hz + 2f * dp, fill)
        fill.shader = null

        val night = LabState.darkness()
        if (night > 0.2f && lab.theme != 3) {
            val r = Random(11)
            for (k in 0 until 30) {
                val tw = 0.5f + 0.5f * sin(t * 2f + k)
                fill.color = Color.argb((night * 200 * tw).toInt(), 255, 255, 240)
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * hz * 0.85f, (0.8f + r.nextFloat() * 1.4f) * dp, fill)
            }
        }
        when (lab.theme) {
            5 -> { // big orange harvest moon
                fill.shader = RadialGradient(w * 0.75f, hz * 0.42f, 70f * dp, Color.argb(120, 255, 170, 80), Color.argb(0, 255, 170, 80), Shader.TileMode.CLAMP)
                c.drawCircle(w * 0.75f, hz * 0.42f, 70f * dp, fill); fill.shader = null
                fill.color = Color.rgb(255, 190, 110); c.drawCircle(w * 0.75f, hz * 0.42f, 32f * dp, fill)
                fill.color = Color.rgb(240, 160, 90); c.drawCircle(w * 0.75f - 9f * dp, hz * 0.42f - 6f * dp, 6f * dp, fill)
                c.drawCircle(w * 0.75f + 10f * dp, hz * 0.42f + 8f * dp, 4f * dp, fill)
            }
            1, 2, 6 -> { // sun
                val sc = when (lab.theme) { 1 -> Color.rgb(255, 245, 170); 6 -> Color.rgb(255, 240, 200); else -> Color.rgb(255, 190, 110) }
                fill.shader = RadialGradient(w * 0.78f, hz * 0.45f, 60f * dp, sc, Color.argb(0, Color.red(sc), Color.green(sc), Color.blue(sc)), Shader.TileMode.CLAMP)
                c.drawCircle(w * 0.78f, hz * 0.45f, 60f * dp, fill); fill.shader = null
                fill.color = sc; c.drawCircle(w * 0.78f, hz * 0.45f, 22f * dp, fill)
                if (lab.theme == 1) { // drifting clouds
                    fill.color = Color.argb(200, 255, 255, 255)
                    for (k in 0..2) {
                        val cx = ((k * 0.4f + t * 0.01f) % 1.3f) * w - 0.15f * w
                        val cy = hz * (0.25f + k * 0.18f)
                        c.drawOval(cx - 30f * dp, cy - 9f * dp, cx + 30f * dp, cy + 9f * dp, fill)
                        c.drawCircle(cx - 8f * dp, cy - 8f * dp, 13f * dp, fill)
                        c.drawCircle(cx + 10f * dp, cy - 6f * dp, 10f * dp, fill)
                    }
                }
            }
            3 -> { // cave ceiling with stalactites
                fill.color = Color.rgb(25, 18, 50)
                var x = 0f
                val r = Random(3)
                while (x < w) {
                    val sw = (14f + r.nextFloat() * 20f) * dp
                    path.reset(); path.moveTo(x, 0f); path.lineTo(x + sw, 0f); path.lineTo(x + sw / 2, (25f + r.nextFloat() * 50f) * dp); path.close()
                    c.drawPath(path, fill)
                    x += sw * 0.8f
                }
            }
            else -> { // moon
                fill.color = Color.argb(70, 255, 240, 220); c.drawCircle(w * 0.78f, hz * 0.45f, 30f * dp, fill)
                fill.color = Color.rgb(255, 240, 220); c.drawCircle(w * 0.78f, hz * 0.45f, 18f * dp, fill)
            }
        }

        if (LabState.rainbow() && lab.theme != 3) drawRainbow(c, w, hz)

        // distant skyline: giant mushrooms, or crystals in the cave
        val forest = intArrayOf(th.forest1, th.forest2)
        for (layer in 0..1) {
            val r = Random(7 + layer)
            fill.color = forest[layer]
            val shift = (camX - 0.5f) * pxPerWorld() * (if (layer == 0) 0.35f else 0.55f)
            var x = -20f * dp - shift
            val endX = w + 40f * dp
            while (x < endX) {
                val sz = (28f + r.nextFloat() * 34f) * dp * (if (layer == 0) 0.8f else 1f)
                val baseY = hz + (if (layer == 0) 0f else 4f * dp)
                if (lab.theme == 3) {
                    path.reset(); path.moveTo(x - sz * 0.25f, baseY); path.lineTo(x, baseY - sz * 1.3f); path.lineTo(x + sz * 0.25f, baseY); path.close()
                    c.drawPath(path, fill)
                } else {
                    c.drawRect(x - sz * 0.09f, baseY - sz * 0.9f, x + sz * 0.09f, baseY, fill)
                    c.drawArc(x - sz * 0.6f, baseY - sz * 1.45f, x + sz * 0.6f, baseY - sz * 0.6f, 180f, 180f, true, fill)
                    if (lab.theme == 4) { // snow on the caps
                        fill.color = Color.argb(200, 240, 245, 255)
                        c.drawArc(x - sz * 0.5f, baseY - sz * 1.42f, x + sz * 0.5f, baseY - sz * 0.95f, 180f, 180f, true, fill)
                        fill.color = forest[layer]
                    }
                }
                x += sz * (0.9f + r.nextFloat() * 0.8f)
            }
        }

        // ground plane
        val gb = navTop
        fill.shader = LinearGradient(0f, hz, 0f, gb, th.groundFar, th.groundNear, Shader.TileMode.CLAMP)
        c.drawRect(0f, hz, w, gb, fill)
        fill.shader = null
        stroke.color = Color.argb(28, 0, 0, 0); stroke.strokeWidth = 1.5f * dp
        for (k in 0..(8 * LabState.WORLD).toInt()) {
            val (x1, _) = toScreen(k / 8f, 0f)
            val (x2, y2) = toScreen(k / 8f, 1.4f)
            if (max(x1, x2) < -20f * dp || min(x1, x2) > w + 20f * dp) continue
            c.drawLine(x1, groundTop() - 20f * dp, x2, y2, stroke)
        }
        val grass = when (lab.theme) { 2 -> Color.rgb(200, 140, 60); 3 -> Color.rgb(130, 110, 200); 4 -> Color.rgb(200, 215, 235); else -> Color.rgb(110, 190, 120) }
        for ((gx, gz, kind) in decor) {
            if (abs(gx - camX) > 0.62f) continue
            val (x, y) = toScreen(gx, gz * 1.15f - 0.05f)
            val d = depth(gz)
            when (kind) {
                0 -> { stroke.color = grass; stroke.strokeWidth = 2f * dp * d
                    c.drawLine(x, y, x - 3f * dp * d, y - 9f * dp * d, stroke)
                    c.drawLine(x + 3f * dp * d, y, x + 5f * dp * d, y - 8f * dp * d, stroke) }
                1 -> { fill.color = Color.rgb(95, 110, 115); c.drawOval(x - 6f * dp * d, y - 3f * dp * d, x + 6f * dp * d, y + 2f * dp * d, fill)
                    fill.color = Color.rgb(130, 145, 150); c.drawOval(x - 4f * dp * d, y - 3f * dp * d, x + 2f * dp * d, y - 1f * dp * d, fill) }
                else -> { stroke.color = grass; stroke.strokeWidth = 1.5f * dp * d
                    c.drawLine(x, y, x, y - 10f * dp * d, stroke)
                    fill.color = if ((gx * 10).toInt() % 2 == 0) ACCENT else Color.rgb(255, 230, 120)
                    c.drawCircle(x, y - 11f * dp * d, 3f * dp * d, fill) }
            }
        }
    }

    /** Real-time night: darken the garden, then let lanterns, crystals and glowing mushrooms shine through. */
    private fun drawNight(c: Canvas, w: Float) {
        val dark = LabState.darkness() * (if (lab.theme == 3) 0.4f else 1f)
        if (dark <= 0.02f) return
        fill.color = Color.argb((dark * 115).toInt(), 10, 8, 40)
        c.drawRect(0f, 0f, w, navTop, fill)
        for (d in lab.placed) {
            if (!Data.DECOR[d.kind].glow) continue
            val (x, y) = toScreen(d.gx, d.gz)
            val sz = 58f * dp * depth(d.gz)
            val gc = if (d.kind == 5) Color.rgb(190, 120, 255) else Color.rgb(255, 210, 120)
            val gy = y - sz * (if (d.kind == 9) 0.75f else 0.55f)
            fill.shader = RadialGradient(x, gy, sz * 1.5f, Color.argb((dark * 110).toInt(), Color.red(gc), Color.green(gc), Color.blue(gc)),
                Color.argb(0, Color.red(gc), Color.green(gc), Color.blue(gc)), Shader.TileMode.CLAMP)
            c.drawCircle(x, gy, sz * 1.5f, fill)
            fill.shader = null
        }
        for (sh in local()) {
            if (!(Data.SPECIES[sh.species].glow || sh.variant == 2) || sh.act == ACT_INSIDE) continue
            val (x, y) = toScreen(sh.gx, sh.gz)
            val sz = shroomSize(sh)
            val cc = Data.SPECIES[sh.species].cap
            fill.shader = RadialGradient(x, y - sz * 0.7f, sz * 1.1f, Color.argb((dark * 120).toInt(), Color.red(cc), Color.green(cc), Color.blue(cc)),
                Color.argb(0, Color.red(cc), Color.green(cc), Color.blue(cc)), Shader.TileMode.CLAMP)
            c.drawCircle(x, y - sz * 0.7f, sz * 1.1f, fill)
            fill.shader = null
        }
    }

    private fun drawWeatherLabel(c: Canvas, w: Float) {
        val dark = LabState.darkness()
        val label = when {
            LabState.rainbow() -> "🌈 A rainbow! (+50% growth today)"
            LabState.raining() -> "🌧 Rainy day: +50% growth"
            dark > 0.6f -> "🌙 Night: sleepy mushrooms"
            dark > 0.05f -> "🌆 Evening"
            else -> return
        }
        text.textSize = 12f * dp; text.color = Color.argb(220, 255, 255, 255)
        c.drawText(label, w / 2, topBar + 70f * dp, text)
        text.color = Color.WHITE
    }

    private fun drawEventBanner(c: Canvas, w: Float) {
        val ev = eventNow()
        if (ev == 0 || photoMode) return
        text.textSize = 12f * dp
        val label = "${Data.EVENT_ICONS[ev]} ${Data.EVENTS[ev]} · until ${Data.EVENT_ENDS[ev]}"
        val tw = text.measureText(label) + 20f * dp
        val y = topBar + 92f * dp
        fill.color = Color.argb(150, 40, 20, 50)
        c.drawRoundRect(w / 2 - tw / 2, y - 14f * dp, w / 2 + tw / 2, y + 6f * dp, 10f * dp, 10f * dp, fill)
        text.color = Color.rgb(255, 190, 110)
        c.drawText(label, w / 2, y, text)
        text.color = Color.WHITE
    }

    /** Things floating in the air, drawn in front of everything: fireflies, pollen, leaves, motes, snow. */
    private fun drawAmbient(c: Canvas, w: Float) {
        val hz = horizon()
        val bottom = navTop
        if (LabState.raining()) {
            fill.color = Color.argb(35, 60, 70, 100)
            c.drawRect(0f, 0f, w, bottom, fill)
            stroke.color = Color.argb(120, 190, 210, 255); stroke.strokeWidth = 1.3f * dp
            for (k in 0 until 45) {
                val x = (k * 0.0237f * w * 3.7f + t * 40f * dp) % (w + 40f * dp) - 20f * dp
                val y = ((t * (500f + (k % 5) * 60f) * dp) + k * 97f * dp) % bottom
                c.drawLine(x, y, x - 4f * dp, y + 14f * dp, stroke)
            }
            stroke.color = Color.argb(90, 200, 220, 255); stroke.strokeWidth = 1f * dp
            for (k in 0 until 6) {   // ripples in puddles
                val rp = (t * 0.8f + k * 0.37f) % 1f
                val (px, py) = toScreen(((k * 0.29f) % 0.85f) + 0.08f, ((k * 0.47f) % 0.9f) + 0.05f)
                c.drawOval(px - 14f * dp * rp, py - 4f * dp * rp, px + 14f * dp * rp, py + 4f * dp * rp, stroke)
            }
        }
        when (lab.theme) {
            5 -> drawBatsAndFog(c, w, hz)
            2, 6 -> for (k in 0 until 10) { // falling leaves (or cherry petals)
                val p = (t * 0.08f * (1 + k % 3) + k * 0.13f) % 1f
                val x = (k * 0.1f * w + sin(t + k) * 30f * dp + p * 60f * dp) % w
                val y = hz * 0.3f + p * (bottom - hz * 0.3f)
                c.save(); c.rotate(t * 90f + k * 40f, x, y)
                fill.color = if (lab.theme == 6) (if (k % 2 == 0) Color.rgb(255, 190, 215) else Color.WHITE)
                             else if (k % 2 == 0) Color.rgb(230, 120, 40) else Color.rgb(250, 190, 60)
                c.drawOval(x - 5f * dp, y - 2.5f * dp, x + 5f * dp, y + 2.5f * dp, fill)
                c.restore()
            }
            4 -> for (k in 0 until 30) { // snow
                val p = (t * 0.05f * (1 + k % 4) + k * 0.071f) % 1f
                val x = (k * 0.0333f * w + sin(t * 0.7f + k) * 12f * dp) % w
                val y = p * bottom
                fill.color = Color.argb(220, 255, 255, 255)
                c.drawCircle(x, y, (1.5f + k % 3) * dp, fill)
            }
            else -> for (k in 0 until 8) { // fireflies / pollen / crystal motes
                val fx = (w * ((k * 0.137f + t * 0.02f * (1 + k % 3)) % 1f))
                val fy = hz + (groundBottom() - hz) * (0.2f + 0.6f * ((k * 0.31f) % 1f)) + sin(t * 1.3f + k) * 14f * dp
                val a = (0.5f + 0.5f * sin(t * 3f + k * 1.7f))
                val col = when (lab.theme) { 1 -> Color.rgb(255, 255, 220); 3 -> Color.rgb(200, 150, 255); else -> Color.rgb(220, 255, 160) }
                fill.color = Color.argb((60 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(fx, fy, 7f * dp, fill)
                fill.color = Color.argb((220 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(fx, fy, 2f * dp, fill)
            }
        }
    }

    private fun gardenItemCount() = lab.decorOwned.sum() + lab.placed.size

    private fun drawGarden(c: Canvas, w: Float, h: Float) {
        if (area == 1) drawCaveScene(c, w, h) else drawGardenScene(c, w, h)

        if (local().isEmpty() && !decorating) {
            text.textSize = 17f * dp; text.color = Color.WHITE
            c.drawText("Your garden is empty", w / 2, h * 0.42f, text)
            text.textSize = 14f * dp; text.color = TEXT_DIM
            c.drawText("Brew a mushroom in the Lab to get started", w / 2, h * 0.42f + 24f * dp, text)
            text.color = Color.WHITE
        }

        if (selected < 0 && !decorating && !photoMode) {
            text.textSize = 12f * dp; text.color = Color.argb(200, 255, 255, 255)
            val awayN = lab.garden.count { it.away() }
            c.drawText("${lab.garden.size} / ${lab.capacity()} mushrooms" + (if (awayN > 0) " · $awayN exploring" else " · drag them to move"), w / 2, groundBottom() + 44f * dp, text)
            text.color = Color.WHITE
        }

        if (area == 0) {
            drawSeasonGround(c, w)
            drawBlooms(c)
            for (cr in critters) if (cr.type != 0) drawCritter(c, cr)
        }

        // Draw decorations and mushrooms together, far-to-near, so closer things overlap ones behind
        val items = ArrayList<Pair<Float, Any>>()
        if (area == 0) for (d in lab.placed) items.add(Pair(d.gz, d)) else for (d in caveCrystals) items.add(Pair(d.gz, d))
        for (s in local()) if (s.pet >= 0 && !s.petGx.isNaN() && s.pet != 1 && s.pet != 2 && s.pet != Data.MINI_DRAGON) items.add(Pair(s.petGz, PetRef(s)))
        if (area == 0) for (sm in snowmen) items.add(Pair(sm.gz, sm))
        if (area == 0) { for (i in 0 until 6) items.add(Pair(plotGz(i) - 0.001f, PlotRef(i))); items.add(Pair(0.0f, PatchSign)) }
        for (s in local()) items.add(Pair(s.gz + (if (s.act == ACT_SIT || s.act == ACT_NAP) 0.02f else 0f), s))
        items.sortBy { it.first }
        for ((_, obj) in items) {
            if (obj is Placed) {
                if (dragging && dragKind == 5 && lab.placed.getOrNull(dragPlaced) === obj) continue
                val (x, y) = toScreen(obj.gx, obj.gz)
                if (decorating) {
                    stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = 1.5f * dp
                    c.drawOval(x - 30f * dp * depth(obj.gz), y - 8f * dp, x + 30f * dp * depth(obj.gz), y + 8f * dp, stroke)
                }
                val idx = lab.placed.indexOf(obj)
                val occ = if (obj.kind == 12 || obj.kind == 14) local().firstOrNull { it.actTarget == idx && (it.act == ACT_SWING || it.act == ACT_BOUNCE) } else null
                drawDecor(c, obj.kind, x, y, depth(obj.gz), occ)
                continue
            }
            if (obj is PlotRef) {
                val (px, py) = toScreen(plotGx(obj.i), plotGz(obj.i))
                if (px > -60f * dp && px < w + 60f * dp) drawPlotWorld(c, obj.i, px, py, depth(plotGz(obj.i)))
                continue
            }
            if (obj === PatchSign) {
                val (px, py) = toScreen(1.32f, 0.02f)
                if (px > -60f * dp && px < w + 60f * dp) drawPatchSign(c, px, py, depth(0.02f))
                continue
            }
            if (obj is Snowman) {
                val (sx, sy) = toScreen(obj.gx, obj.gz)
                drawSnowman(c, sx, sy, 44f * dp * depth(obj.gz), obj.prog)
                continue
            }
            if (obj is PetRef) {
                val o = obj.owner
                val (px, py) = toScreen(o.petGx, o.petGz)
                drawPet(c, o.pet, px, py, shroomSize(o) * 0.42f * depth(o.petGz) / depth(o.gz), o.petPhase, if (o.petGx < o.gx) 1f else -1f, o.sleeping)
                continue
            }
            val s = obj as Shroom
            if (s.held && dragging) continue
            if (s.act == ACT_INSIDE) continue
            val pose = actPose(s)
            val x: Float; val y: Float
            if (pose != null) { x = pose[0]; y = pose[1] } else { val (sx, sy) = toScreen(s.gx, s.gz); x = sx; y = sy - actLift(s) }
            val size = shroomSize(s)
            if (pose != null && pose[2] != 0f) { c.save(); c.rotate(pose[2], x, y) }
            if (s.id == selected) {
                stroke.color = Color.argb(180, 255, 255, 255); stroke.strokeWidth = 2f * dp
                c.drawOval(x - size * 0.6f, y - size * 0.1f, x + size * 0.6f, y + size * 0.12f, stroke)
            }
            val i = lab.garden.indexOf(s)
            drawShroom(c, s.species, x, y, size, s.mood(), sin(t * 2.2f + i * 1.3f), isBlinking(s.id), false,
                facing = s.facing, phase = s.phase, moving = s.moving || (s.act == ACT_SLIDE && (s.phase % 4f) > 2.4f), shiny = s.shiny, who = s, sleeping = s.sleeping)
            if (pose != null && pose[2] != 0f) c.restore()
            val inNest = s.act == ACT_NAP && lab.placed.getOrNull(s.actTarget)?.kind == Data.COZY_BED
            if (inNest && !s.held) drawLeafBlanket(c, x, y, size)
            else if (s.sleeping && !s.held && System.currentTimeMillis() < lab.tuckedUntil) drawBlanket(c, x, y, size, s.id)
            if (s.sleeping) drawDream(c, s, x, y - s.airH, size)
            if (s.thoughtT > 0f && !s.sleeping) drawThought(c, s, x, y - s.airH - size * 1.25f)
            if (s.pet == 1 || s.pet == 2 || s.pet == Data.MINI_DRAGON) {   // flying pets circle the cap
                val a = t * 1.6f + s.id
                drawPet(c, s.pet, x + cos(a) * size * 0.6f, y - s.airH - size * 1.05f + sin(a * 1.3f) * size * 0.12f, size * 0.32f, t * 10f, if (cos(a) > 0) -1f else 1f, s.sleeping)
            }
            if (s.act == ACT_JAM) drawInstrument(c, jamInst[s.id] ?: 0, x + s.facing * size * 0.45f, y - size * 0.3f, size * 0.38f, s.jiggle)
            if (area == 0 && LabState.raining() && !s.held) drawLeafUmbrella(c, x, y - s.airH - size * 1.0f, size)
            drawWantBubble(c, s, x, y - s.airH - size * (if (LabState.raining()) 1.35f else 1.05f))
            if (s.act == ACT_CHAT) {
                val bx = x + s.facing * size * 0.3f; val by = y - size * 1.15f
                fill.color = Color.WHITE
                c.drawRoundRect(bx - 11f * dp, by - 10f * dp, bx + 11f * dp, by + 8f * dp, 8f * dp, 8f * dp, fill)
                path.reset(); path.moveTo(bx - 3f * dp, by + 7f * dp); path.lineTo(bx - s.facing * 6f * dp, by + 14f * dp); path.lineTo(bx + 4f * dp, by + 7f * dp); path.close()
                c.drawPath(path, fill)
                text.textSize = 12f * dp; text.color = ACCENT
                c.drawText(arrayOf("♥", "♪", "!", "?", "☺")[((t + s.id) % 5).toInt()], bx, by + 4f * dp, text)
                text.color = Color.WHITE
            }
            if (s.dust >= 1f) {
                val dy = y - size * 1.15f - 16f * dp + sin(t * 3f + i) * 3f * dp
                fill.color = Color.argb(70, 255, 214, 110)
                c.drawCircle(x, dy, 15f * dp, fill)
                fill.shader = RadialGradient(x - 3f * dp, dy - 3f * dp, 11f * dp, Color.rgb(255, 245, 190), DUST, Shader.TileMode.CLAMP)
                c.drawCircle(x, dy, 10f * dp, fill)
                fill.shader = null
                text.textSize = 10f * dp; text.color = BG
                c.drawText("${s.dust.toInt()}", x, dy + 3.5f * dp, text)
                text.color = Color.WHITE
            }
        }
        drawBestieHearts(c)
        if (area == 0) {
            for (cr in critters) if (cr.type == 0) drawCritter(c, cr)
            drawNight(c, w)
            drawAmbient(c, w)
            drawSeasonAir(c, w)
            drawWeatherLabel(c, w)
        } else {
            text.textSize = 12f * dp; text.color = Color.argb(220, 200, 255, 240)
            c.drawText("⛰ Glow Cave · glowing shrooms grow 50% faster", w / 2, topBar + 66f * dp, text)
            text.color = Color.WHITE
        }
        drawEventBanner(c, w)

        if (photoMode) return
        if (growPick >= 0 && area == 0) { drawSeedPanel(c, w); return }
        if (decorating) drawDecorTray(c, w)
        else shroom(selected)?.let { drawInfoPanel(c, w, it) }
    }

    private fun drawDecorTray(c: Canvas, w: Float) {
        val top = panelTop()
        panel(c, RectF(8f * dp, top, w - 8f * dp, panelBottom()), 22f * dp)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        val msg = if (gardenItemCount() == 0) "Buy decorations in the Shop → DECOR"
        else "Drag items onto the ground · drag them back here to put away"
        c.drawText(msg, w / 2, navTop - 18f * dp, text)
        text.color = Color.WHITE
    }

    // ---------- Art: decorations ----------

    private fun drawDecor(c: Canvas, kind: Int, x: Float, y: Float, scale: Float, occupant: Shroom? = null) {
        val s = 58f * dp * scale
        if (Data.DECOR[kind].glow) {
            val gc = when (kind) { 5 -> Color.rgb(190, 120, 255); 9 -> Color.rgb(255, 200, 120); else -> Color.rgb(255, 210, 120) }
            val gy = y - s * (if (kind == 9) 0.75f else 0.55f)
            fill.shader = RadialGradient(x, gy, s * 1.1f, Color.argb((70 + 25 * sin(t * 2f + kind)).toInt(), Color.red(gc), Color.green(gc), Color.blue(gc)),
                Color.argb(0, Color.red(gc), Color.green(gc), Color.blue(gc)), Shader.TileMode.CLAMP)
            c.drawCircle(x, gy, s * 1.1f, fill)
            fill.shader = null
        }
        if (kind != 4) groundShadow(c, x, y, s * (if (kind == 3 || kind == 9 || kind == 7) 0.75f else 0.45f), 90)
        when (kind) {
            0 -> { // lantern on a post
                stroke.color = Color.rgb(60, 50, 70); stroke.strokeWidth = s * 0.06f
                c.drawLine(x, y, x, y - s * 0.95f, stroke)
                c.drawLine(x, y - s * 0.95f, x + s * 0.22f, y - s * 0.95f, stroke)
                val lx = x + s * 0.22f; val ly = y - s * 0.7f
                stroke.strokeWidth = s * 0.025f; c.drawLine(lx, y - s * 0.95f, lx, ly - s * 0.16f, stroke)
                fill.shader = RadialGradient(lx, ly, s * 0.18f, Color.rgb(255, 250, 200), Color.rgb(255, 170, 60), Shader.TileMode.CLAMP)
                c.drawRoundRect(lx - s * 0.11f, ly - s * 0.16f, lx + s * 0.11f, ly + s * 0.12f, s * 0.04f, s * 0.04f, fill)
                fill.shader = null
                fill.color = Color.rgb(60, 50, 70)
                c.drawRect(lx - s * 0.13f, ly - s * 0.2f, lx + s * 0.13f, ly - s * 0.15f, fill)
                c.drawRect(lx - s * 0.13f, ly + s * 0.11f, lx + s * 0.13f, ly + s * 0.15f, fill)
            }
            1 -> { // flower patch
                fill.shader = RadialGradient(x - s * 0.1f, y - s * 0.2f, s * 0.5f, Color.rgb(120, 200, 110), Color.rgb(50, 110, 60), Shader.TileMode.CLAMP)
                c.drawOval(x - s * 0.45f, y - s * 0.28f, x + s * 0.45f, y + s * 0.06f, fill)
                fill.shader = null
                val cols = intArrayOf(ACCENT, Color.rgb(255, 230, 120), Color.WHITE, Color.rgb(170, 140, 255), Color.rgb(255, 150, 90))
                for (k in 0 until 6) {
                    val fx = x + (k - 2.5f) * s * 0.14f; val fy = y - s * (0.2f + (k % 2) * 0.1f)
                    fill.color = cols[k % cols.size]
                    for (pp in 0 until 5) { val a = pp * 1.2566f + t * 0.3f; c.drawCircle(fx + cos(a) * s * 0.045f, fy + sin(a) * s * 0.045f, s * 0.035f, fill) }
                    fill.color = Color.rgb(255, 220, 90); c.drawCircle(fx, fy, s * 0.025f, fill)
                }
            }
            2 -> { // big rock
                fill.shader = RadialGradient(x - s * 0.15f, y - s * 0.4f, s * 0.6f, Color.rgb(170, 175, 185), Color.rgb(80, 85, 100), Shader.TileMode.CLAMP)
                path.reset(); path.moveTo(x - s * 0.45f, y); path.quadTo(x - s * 0.5f, y - s * 0.4f, x - s * 0.1f, y - s * 0.55f)
                path.quadTo(x + s * 0.35f, y - s * 0.6f, x + s * 0.45f, y - s * 0.15f); path.quadTo(x + s * 0.45f, y + s * 0.03f, x - s * 0.45f, y); path.close()
                c.drawPath(path, fill); fill.shader = null
                fill.color = Color.rgb(100, 160, 100); c.drawOval(x - s * 0.3f, y - s * 0.52f, x, y - s * 0.4f, fill)
            }
            3 -> { // picket fence
                fill.color = Color.rgb(240, 235, 225)
                c.drawRect(x - s * 0.7f, y - s * 0.45f, x + s * 0.7f, y - s * 0.38f, fill)
                c.drawRect(x - s * 0.7f, y - s * 0.2f, x + s * 0.7f, y - s * 0.13f, fill)
                for (k in 0 until 6) {
                    val px = x - s * 0.62f + k * s * 0.248f
                    path.reset(); path.moveTo(px - s * 0.07f, y); path.lineTo(px - s * 0.07f, y - s * 0.55f); path.lineTo(px, y - s * 0.65f)
                    path.lineTo(px + s * 0.07f, y - s * 0.55f); path.lineTo(px + s * 0.07f, y); path.close()
                    fill.color = Color.rgb(250, 248, 240); c.drawPath(path, fill)
                    fill.color = Color.rgb(200, 195, 185); c.drawRect(px + s * 0.03f, y - s * 0.55f, px + s * 0.07f, y, fill)
                }
            }
            4 -> { // pond
                fill.color = Color.rgb(60, 110, 90); c.drawOval(x - s * 0.72f, y - s * 0.24f, x + s * 0.72f, y + s * 0.18f, fill)
                fill.shader = RadialGradient(x - s * 0.2f, y - s * 0.1f, s * 0.7f, Color.rgb(120, 200, 240), Color.rgb(40, 100, 170), Shader.TileMode.CLAMP)
                c.drawOval(x - s * 0.62f, y - s * 0.19f, x + s * 0.62f, y + s * 0.13f, fill); fill.shader = null
                stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = 1.5f * dp
                val rp = (t * 0.6f) % 1f
                c.drawOval(x + s * 0.15f - s * 0.25f * rp, y - s * 0.06f * rp, x + s * 0.15f + s * 0.25f * rp, y + s * 0.06f * rp, stroke)
                fill.color = Color.rgb(90, 170, 90); c.drawArc(x - s * 0.4f, y - s * 0.1f, x - s * 0.15f, y + s * 0.02f, 30f, 300f, true, fill)
                fill.color = ACCENT; c.drawCircle(x - s * 0.3f, y - s * 0.07f, s * 0.04f, fill)
            }
            5 -> { // crystal cluster
                for ((ox, hgt, lean) in arrayOf(Triple(-0.18f, 0.55f, -12f), Triple(0.15f, 0.45f, 14f), Triple(0f, 0.8f, 0f))) {
                    c.save(); c.rotate(lean, x + ox * s, y)
                    val cx = x + ox * s
                    path.reset(); path.moveTo(cx - s * 0.1f, y); path.lineTo(cx - s * 0.1f, y - s * hgt * 0.75f); path.lineTo(cx, y - s * hgt)
                    path.lineTo(cx + s * 0.1f, y - s * hgt * 0.75f); path.lineTo(cx + s * 0.1f, y); path.close()
                    fill.shader = LinearGradient(cx - s * 0.1f, 0f, cx + s * 0.1f, 0f, Color.rgb(230, 200, 255), Color.rgb(120, 60, 200), Shader.TileMode.CLAMP)
                    c.drawPath(path, fill); fill.shader = null
                    c.restore()
                }
            }
            6 -> { // toadstool house
                fill.shader = LinearGradient(x - s * 0.3f, 0f, x + s * 0.3f, 0f, Color.rgb(255, 245, 225), Color.rgb(205, 190, 170), Shader.TileMode.CLAMP)
                c.drawRoundRect(x - s * 0.3f, y - s * 0.75f, x + s * 0.3f, y, s * 0.08f, s * 0.08f, fill); fill.shader = null
                fill.color = Color.rgb(140, 90, 60); c.drawRoundRect(x - s * 0.09f, y - s * 0.3f, x + s * 0.09f, y, s * 0.09f, s * 0.09f, fill)
                fill.color = Color.rgb(255, 220, 120); c.drawCircle(x + s * 0.17f, y - s * 0.5f, s * 0.07f, fill)
                fill.color = Color.rgb(255, 230, 120); c.drawCircle(x + s * 0.04f, y - s * 0.15f, s * 0.015f, fill)
                val cw = s * 1.2f; val cb = y - s * 0.68f
                path.reset(); path.moveTo(x - cw / 2, cb); path.cubicTo(x - cw / 2, cb - s * 0.8f, x + cw / 2, cb - s * 0.8f, x + cw / 2, cb)
                path.quadTo(x, cb + s * 0.1f, x - cw / 2, cb); path.close()
                fill.shader = RadialGradient(x - cw * 0.2f, cb - s * 0.5f, cw * 0.8f, Color.rgb(255, 120, 110), Color.rgb(170, 30, 40), Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
                fill.color = Color.WHITE
                c.drawCircle(x - cw * 0.25f, cb - s * 0.3f, s * 0.07f, fill); c.drawCircle(x + cw * 0.12f, cb - s * 0.45f, s * 0.06f, fill)
                c.drawCircle(x + cw * 0.3f, cb - s * 0.18f, s * 0.05f, fill)
                fill.color = Color.rgb(140, 120, 120); c.drawRect(x + cw * 0.18f, cb - s * 0.62f, x + cw * 0.26f, cb - s * 0.4f, fill)
            }
            7 -> { // bench
                fill.color = Color.rgb(90, 60, 40)
                c.drawRect(x - s * 0.5f, y - s * 0.28f, x - s * 0.44f, y, fill); c.drawRect(x + s * 0.44f, y - s * 0.28f, x + s * 0.5f, y, fill)
                c.drawRect(x - s * 0.5f, y - s * 0.62f, x - s * 0.45f, y - s * 0.28f, fill); c.drawRect(x + s * 0.45f, y - s * 0.62f, x + s * 0.5f, y - s * 0.28f, fill)
                fill.color = Color.rgb(170, 115, 70)
                c.drawRoundRect(x - s * 0.58f, y - s * 0.34f, x + s * 0.58f, y - s * 0.25f, s * 0.03f, s * 0.03f, fill)
                c.drawRoundRect(x - s * 0.55f, y - s * 0.62f, x + s * 0.55f, y - s * 0.54f, s * 0.03f, s * 0.03f, fill)
                c.drawRoundRect(x - s * 0.55f, y - s * 0.5f, x + s * 0.55f, y - s * 0.43f, s * 0.03f, s * 0.03f, fill)
            }
            8 -> { // signpost
                fill.color = Color.rgb(110, 75, 50); c.drawRect(x - s * 0.04f, y - s * 0.85f, x + s * 0.04f, y, fill)
                fill.color = Color.rgb(190, 140, 90)
                path.reset(); path.moveTo(x - s * 0.35f, y - s * 0.8f); path.lineTo(x + s * 0.3f, y - s * 0.8f); path.lineTo(x + s * 0.42f, y - s * 0.68f)
                path.lineTo(x + s * 0.3f, y - s * 0.56f); path.lineTo(x - s * 0.35f, y - s * 0.56f); path.close(); c.drawPath(path, fill)
                text.textSize = s * 0.13f; text.color = Color.rgb(80, 50, 30)
                c.drawText("SHROOMS", x, y - s * 0.63f, text); text.color = Color.WHITE
            }
            9 -> { // fairy lights
                stroke.color = Color.rgb(90, 70, 60); stroke.strokeWidth = s * 0.04f
                val lx = x - s * 0.7f; val rx = x + s * 0.7f; val top = y - s * 0.95f
                c.drawLine(lx, y, lx, top, stroke); c.drawLine(rx, y, rx, top, stroke)
                stroke.color = Color.rgb(60, 60, 60); stroke.strokeWidth = 1.2f * dp
                path.reset(); path.moveTo(lx, top); path.quadTo(x, top + s * 0.4f, rx, top)
                c.drawPath(path, stroke)
                val cols = intArrayOf(Color.rgb(255, 120, 150), Color.rgb(255, 220, 110), Color.rgb(130, 220, 255), Color.rgb(150, 255, 170))
                for (k in 1..7) {
                    val u = k / 8f
                    val bx = lx + (rx - lx) * u
                    val by = top + 2 * u * (1 - u) * s * 0.4f + s * 0.04f
                    val on = sin(t * 4f + k * 1.3f) > -0.3f
                    fill.color = cols[k % cols.size]; fill.alpha = if (on) 255 else 90
                    c.drawCircle(bx, by, s * 0.045f, fill)
                }
                fill.alpha = 255
            }
            in 11..15 -> drawToy(c, kind, x, y, s, occupant)
            in 16..23 -> drawEventDecor(c, kind, x, y, s)
            Data.COZY_BED -> { // a walnut-shell nest lined with moss, with a little leaf to snuggle under
                fill.color = Color.rgb(150, 100, 60)
                c.drawArc(x - s * 0.5f, y - s * 0.5f, x + s * 0.5f, y + s * 0.12f, 0f, 180f, true, fill)
                stroke.color = Color.rgb(110, 72, 42); stroke.strokeWidth = s * 0.025f
                for (k in -2..2) c.drawArc(x + k * s * 0.17f - s * 0.12f, y - s * 0.22f, x + k * s * 0.17f + s * 0.12f, y + s * 0.1f, 20f, 140f, false, stroke)
                fill.color = Color.rgb(120, 180, 90); c.drawOval(x - s * 0.46f, y - s * 0.3f, x + s * 0.46f, y - s * 0.12f, fill)
                fill.color = Color.rgb(150, 210, 110)
                for (k in 0 until 7) c.drawCircle(x - s * 0.36f + k * s * 0.12f, y - s * 0.24f - (k % 2) * s * 0.03f, s * 0.055f, fill)
                fill.color = Color.rgb(255, 200, 220); c.drawCircle(x + s * 0.32f, y - s * 0.3f, s * 0.04f, fill)
                fill.color = Color.rgb(255, 240, 150); c.drawCircle(x - s * 0.3f, y - s * 0.28f, s * 0.035f, fill)
            }
            Data.STAR_FOUNTAIN -> {
                fill.shader = RadialGradient(x, y - s * 0.6f, s * 0.8f, Color.argb(110, 180, 220, 255), Color.argb(0, 180, 220, 255), Shader.TileMode.CLAMP)
                c.drawCircle(x, y - s * 0.6f, s * 0.8f, fill); fill.shader = null
                fill.color = Color.rgb(200, 195, 225); c.drawOval(x - s * 0.55f, y - s * 0.22f, x + s * 0.55f, y + s * 0.05f, fill)
                fill.color = Color.rgb(120, 190, 255); c.drawOval(x - s * 0.46f, y - s * 0.18f, x + s * 0.46f, y - s * 0.02f, fill)
                fill.color = Color.rgb(200, 195, 225); c.drawRect(x - s * 0.07f, y - s * 0.62f, x + s * 0.07f, y - s * 0.1f, fill)
                c.drawOval(x - s * 0.25f, y - s * 0.68f, x + s * 0.25f, y - s * 0.56f, fill)
                for (k in 0 until 5) {   // sparkling water arcs
                    val p = (t * 0.8f + k * 0.2f) % 1f
                    val dx = (k - 2) * s * 0.12f * p * 2f
                    fill.color = Color.argb((220 * (1f - p)).toInt(), 170, 220, 255)
                    c.drawCircle(x + dx, y - s * 0.7f - sin(p * 3.14f) * s * 0.35f + p * s * 0.45f, s * 0.035f, fill)
                }
                fill.color = Color.rgb(255, 225, 110); drawSparkle(c, x, y - s * 0.95f - sin(t * 3f) * s * 0.04f, s * 0.12f)
            }
            else -> { // tree stump
                fill.shader = LinearGradient(x - s * 0.32f, 0f, x + s * 0.32f, 0f, Color.rgb(150, 100, 60), Color.rgb(80, 50, 30), Shader.TileMode.CLAMP)
                c.drawRect(x - s * 0.32f, y - s * 0.4f, x + s * 0.32f, y, fill); fill.shader = null
                fill.color = Color.rgb(210, 170, 120); c.drawOval(x - s * 0.32f, y - s * 0.48f, x + s * 0.32f, y - s * 0.32f, fill)
                stroke.color = Color.rgb(170, 125, 80); stroke.strokeWidth = 1.2f * dp
                c.drawOval(x - s * 0.2f, y - s * 0.45f, x + s * 0.2f, y - s * 0.35f, stroke)
                c.drawOval(x - s * 0.09f, y - s * 0.42f, x + s * 0.09f, y - s * 0.38f, stroke)
                fill.color = Color.rgb(255, 120, 150); c.drawCircle(x + s * 0.25f, y - s * 0.1f, s * 0.05f, fill)
            }
        }
    }

    private fun isBlinking(id: Long): Boolean {
        val b = blinkAt[id] ?: return false
        return t - b < 0.15f
    }

    private fun drawInfoPanel(c: Canvas, w: Float, s: Shroom) {
        val top = panelTop()
        val r = RectF(8f * dp, top, w - 8f * dp, panelBottom())
        panel(c, r, 22f * dp)
        val sp = Data.SPECIES[s.species]
        val pad = 24f * dp

        text.textAlign = Paint.Align.LEFT
        text.textSize = 21f * dp
        c.drawText(s.name, pad, top + 30f * dp, text)
        val nameW = text.measureText(s.name)
        for (k in 0 until 5) {   // friendship hearts
            fill.color = if (k < s.hearts()) ACCENT else Color.argb(60, 255, 255, 255)
            drawHeart(c, w - pad - 8f * dp - (4 - k) * 17f * dp, top + 20f * dp, 5.5f * dp)
        }
        text.textSize = 13f * dp; text.color = if (s.shiny) Color.WHITE else TEXT_DIM
        c.drawText((if (s.shiny) "✨ Shiny " else "") + sp.name, pad + nameW + 10f * dp, top + 30f * dp, text)
        text.color = TEXT_DIM
        val extras = ArrayList<String>()
        extras.add(Data.STAGE_NAMES[s.stage()]); extras.add(Data.TIER_NAMES[sp.tier])
        if (s.variant == 1) extras.add("Albino") else if (s.variant == 2) extras.add("Neon")
        if (s.size > 1.1f) extras.add("Big") else if (s.size < 0.9f) extras.add("Tiny")
        extras.add("loves ${Data.FOODS[sp.favFood].name}")
        c.drawText(extras.joinToString(" · "), pad, top + 48f * dp, text)
        text.color = ACCENT
        c.drawText("${Data.TRAITS[s.trait]}: ${Data.TRAIT_DESC[s.trait]}", pad, top + 64f * dp, text)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER

        val modeHint = when {
            feeding -> "Tap a food, or drag it onto any mushroom"
            styling -> if (Data.ACCESSORIES.indices.none { lab.accOwned[it] > 0 } && Data.PETS.indices.none { lab.petOwned[it] > 0 }) "Buy hats in Shop → STYLE and pets in Shop → PETS" else "Tap to put on, take off, or bring a pet along"
            exploring -> "Send ${s.name} exploring. It comes back with loot!"
            jobbing -> "Give ${s.name} a job (one mushroom per job)"
            moreOpen -> "More for ${s.name}" + (if (s.job > 0) " · ${Data.JOBS[s.job].icon} ${Data.JOBS[s.job].name}" else "") +
                (lab.garden.firstOrNull { it.id == s.bestie }?.let { " · 💕 ${it.name}" } ?: "")
            else -> null
        }
        if (modeHint != null) {
            text.textSize = 12f * dp; text.color = TEXT_DIM
            c.drawText(modeHint, w / 2, top + 86f * dp, text)
            text.color = Color.WHITE
            return
        }

        val stage = s.stage()
        val barX = pad + 70f * dp
        val barW = w - barX - pad
        var y = top + 78f * dp
        if (stage < Data.STAGE_XP.size - 1) {
            val lo = Data.STAGE_XP[stage]; val hi = Data.STAGE_XP[stage + 1]
            bar(c, "Growth", barX, y, barW, (s.xp - lo) / (hi - lo), MINT)
        } else bar(c, "Growth", barX, y, barW, 1f, DUST)
        y += 22f * dp
        bar(c, "Food", barX, y, barW, s.hunger / 100f, if (s.hunger < 25f) ACCENT else Color.rgb(255, 170, 90))
        y += 22f * dp
        bar(c, "Happy", barX, y, barW, s.happy / 100f, Color.rgb(255, 140, 200))
        y += 18f * dp
        text.textSize = 12f * dp; text.color = TEXT_DIM
        val moodText = wantText(s) ?: when (s.mood()) {
            0 -> "Feeling great! Growing fast."
            1 -> "Doing fine. Hold it for a hug, or rub it to pet!"
            2 -> "Hungry! It grows slowly until you feed it."
            else -> "A little lonely. Try a pat."
        }
        c.drawText(moodText, w / 2, y + 6f * dp, text)
        text.color = Color.WHITE
    }

    private fun bar(c: Canvas, label: String, x: Float, y: Float, w: Float, frac: Float, color: Int) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 13f * dp
        c.drawText(label, 24f * dp, y + 9f * dp, text)
        text.textAlign = Paint.Align.CENTER
        fill.color = Color.argb(50, 255, 255, 255)
        c.drawRoundRect(x, y, x + w, y + 11f * dp, 6f * dp, 6f * dp, fill)
        fill.color = color
        c.drawRoundRect(x, y, x + w * frac.coerceIn(0.02f, 1f), y + 11f * dp, 6f * dp, 6f * dp, fill)
    }

    private fun drawFoodCard(c: Canvas, r: RectF, f: Int, s: Shroom) {
        val food = Data.FOODS[f]
        val fav = Data.SPECIES[s.species].favFood == f
        drawFood(c, f, r.centerX(), r.top + r.height() * 0.32f, min(r.width(), r.height()) * 0.22f)
        text.textSize = 10.5f * dp
        c.drawText(food.name, r.centerX(), r.top + r.height() * 0.66f, text)
        text.textSize = 13f * dp
        text.color = if (lab.foods[f] > 0) Color.WHITE else ACCENT
        c.drawText("×${lab.foods[f]}", r.centerX(), r.top + r.height() * 0.84f, text)
        text.color = Color.WHITE
        if (fav) {
            text.textSize = 12f * dp
            c.drawText("❤", r.right - 10f * dp, r.top + 16f * dp, text)
        }
    }

    // ----- Lab -----

    private fun liquidColor(): Int {
        val a = if (labMode == 0) slots[0] else picks.getOrNull(0)?.let { shroom(it)?.species }?.let { Data.SPECIES[it].cap } ?: -1
        val b = if (labMode == 0) slots[1] else picks.getOrNull(1)?.let { shroom(it)?.species }?.let { Data.SPECIES[it].cap } ?: -1
        val ca = if (labMode == 0) (if (a >= 0) Data.INGREDIENTS[a].color else -1) else a
        val cb = if (labMode == 0) (if (b >= 0) Data.INGREDIENTS[b].color else -1) else b
        val base = Color.rgb(150, 120, 255)
        val x = if (ca == -1) base else ca
        val y = if (cb == -1) (if (ca == -1) base else ca) else cb
        return Color.rgb((Color.red(x) + Color.red(y)) / 2, (Color.green(x) + Color.green(y)) / 2, (Color.blue(x) + Color.blue(y)) / 2)
    }

    /** The lab room: back wall, a shelf of specimen jars, bubbling tubes and a burner under the flask. */
    private fun drawLabRoom(c: Canvas, w: Float, h: Float) {
        val fx = w / 2; val fy = flaskY(); val r = 62f * dp
        // back wall tiles
        fill.color = Color.rgb(32, 22, 58)
        c.drawRect(0f, topBar + 52f * dp, w, h * 0.43f, fill)
        stroke.color = Color.argb(25, 255, 255, 255); stroke.strokeWidth = 1f * dp
        var yy = topBar + 52f * dp
        var row = 0
        while (yy < h * 0.43f) {
            c.drawLine(0f, yy, w, yy, stroke)
            var xx = if (row % 2 == 0) 0f else 22f * dp
            while (xx < w) { c.drawLine(xx, yy, xx, yy + 22f * dp, stroke); xx += 44f * dp }
            yy += 22f * dp; row++
        }

        // shelf with specimen jars of mushrooms you've discovered
        val shelfY = fy - r * 1.75f
        fill.color = Color.rgb(110, 75, 55)
        c.drawRect(8f * dp, shelfY, w - 8f * dp, shelfY + 7f * dp, fill)
        fill.color = Color.rgb(70, 48, 36)
        c.drawRect(8f * dp, shelfY + 7f * dp, w - 8f * dp, shelfY + 10f * dp, fill)
        val found = Data.DEX_ORDER.filter { lab.discovered[it] }
        val spots = floatArrayOf(0.08f, 0.2f, 0.32f, 0.68f, 0.8f, 0.92f)
        for ((k, sx) in spots.withIndex()) {
            val jx = w * sx
            c.save(); c.rotate(sin(t * 25f) * jarWobble[k] * 14f, jx, shelfY)
            val jw = 18f * dp; val jh = 30f * dp
            val sp = found.getOrNull(k)
            if (sp != null) drawShroom(c, sp, jx, shelfY - 3f * dp, 20f * dp, 0, 0f, (t + k) % 4f < 0.12f, false)
            else {
                // jar of a random ingredient color
                fill.color = Data.INGREDIENTS[(k * 2) % Data.INGREDIENTS.size].color; fill.alpha = 160
                c.drawRect(jx - jw * 0.8f, shelfY - jh * 0.55f, jx + jw * 0.8f, shelfY - 2f * dp, fill)
                fill.alpha = 255
            }
            fill.color = Color.argb(45, 220, 235, 255)
            c.drawRoundRect(jx - jw, shelfY - jh, jx + jw, shelfY, 5f * dp, 5f * dp, fill)
            fill.color = Color.rgb(150, 110, 80)
            c.drawRect(jx - jw * 0.85f, shelfY - jh - 4f * dp, jx + jw * 0.85f, shelfY - jh + 1f * dp, fill)
            fill.color = Color.argb(80, 255, 255, 255)
            c.drawRect(jx - jw * 0.7f, shelfY - jh * 0.85f, jx - jw * 0.5f, shelfY - jh * 0.2f, fill)
            c.restore()
        }

        // bubbling test tubes on the far sides
        for ((k, side) in floatArrayOf(0.06f, 0.94f).withIndex()) {
            val tx = w * side
            val top = fy - r * 0.2f
            val col = if (k == 0) Color.rgb(120, 255, 200) else Color.rgb(255, 130, 200)
            fill.color = Color.argb(50, 220, 235, 255)
            c.drawRoundRect(tx - 8f * dp, top, tx + 8f * dp, top + 70f * dp, 8f * dp, 8f * dp, fill)
            fill.color = col; fill.alpha = 200
            c.drawRoundRect(tx - 6f * dp, top + 26f * dp, tx + 6f * dp, top + 68f * dp, 6f * dp, 6f * dp, fill)
            fill.alpha = 255
            for (b in 0 until 3) {
                val bt = (t * (0.7f + b * 0.2f) + b * 0.33f + k * 0.5f) % 1f
                fill.color = Color.argb((200 * (1 - bt)).toInt(), 255, 255, 255)
                c.drawCircle(tx + sin(t * 3f + b) * 2f * dp, top + 64f * dp - bt * 40f * dp, 2f * dp, fill)
            }
        }

        // burner under the flask
        val by = fy + r - 2f * dp
        val power = if (brewing > 0f) 1f + 0.3f * sin(t * 30f) else 0.45f
        fill.shader = RadialGradient(fx, by + 6f * dp, 20f * dp * power,
            intArrayOf(Color.rgb(200, 240, 255), Color.rgb(80, 150, 255), if (brewing > 0f) Color.argb(0, 255, 140, 60) else Color.argb(0, 80, 150, 255)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(fx - 10f * dp, by + 18f * dp)
        path.quadTo(fx - 9f * dp, by + 4f * dp - 10f * dp * power, fx + sin(t * 20f) * 2f * dp, by - 14f * dp * power)
        path.quadTo(fx + 9f * dp, by + 4f * dp - 10f * dp * power, fx + 10f * dp, by + 18f * dp)
        path.close()
        c.drawPath(path, fill)
        fill.shader = null
        fill.color = Color.rgb(80, 80, 95)
        c.drawRoundRect(fx - 26f * dp, by + 17f * dp, fx + 26f * dp, by + 26f * dp, 4f * dp, 4f * dp, fill)
        stroke.color = Color.rgb(110, 110, 125); stroke.strokeWidth = 3f * dp
        c.drawLine(fx - r * 0.7f, fy + r * 0.5f, fx - r * 0.95f, by + 24f * dp, stroke)
        c.drawLine(fx + r * 0.7f, fy + r * 0.5f, fx + r * 0.95f, by + 24f * dp, stroke)
    }

    private fun drawLab(c: Canvas, w: Float, h: Float) {
        val fx = w / 2
        val fy = flaskY()
        val late = if (brewing > 0f) 1f - brewing / BREW_TIME else 0f
        val shakeX = if (brewing > 0f) sin(t * 50f) * (2f + 6f * late * late) * dp else 0f
        val r = 62f * dp
        drawLabRoom(c, w, h)

        // "+" signs between slots and flask
        text.textSize = 26f * dp; text.color = TEXT_DIM
        val off = min(w * 0.33f, 130f * dp)
        if (labMode <= 1) {
            c.drawText("+", fx - off / 2 - 14f * dp, fy + 9f * dp, text)
            c.drawText("+", fx + off / 2 + 14f * dp, fy + 9f * dp, text)
        }
        text.color = Color.WHITE

        if (dragging && dragKind == 1) {
            val near = hypot(dragX - fx, dragY - fy) < 95f * dp
            fill.shader = RadialGradient(fx, fy, r * 1.9f, Color.argb(if (near) 150 else 70, 130, 255, 200),
                Color.argb(0, 130, 255, 200), Shader.TileMode.CLAMP)
            c.drawCircle(fx, fy, r * 1.9f, fill)
            fill.shader = null
        }

        // flask neck + body
        c.save()
        c.translate(shakeX, 0f)
        fill.color = Color.argb(60, 220, 230, 255)
        c.drawRoundRect(fx - r * 0.28f, fy - r * 1.55f, fx + r * 0.28f, fy - r * 0.6f, 6f * dp, 6f * dp, fill)
        c.drawCircle(fx, fy, r, fill)
        // liquid
        val lc = liquidColor()
        fill.color = lc
        val level = fy - r * 0.15f + sin(t * 2f) * 2f * dp
        c.save()
        path.reset(); path.addCircle(fx, fy, r - 5f * dp, Path.Direction.CW)
        c.clipPath(path)
        c.drawRect(fx - r, level, fx + r, fy + r, fill)
        fill.color = Color.argb(70, 255, 255, 255)
        c.drawRect(fx - r, level, fx + r, level + 4f * dp, fill)
        // swirl from stirring
        if (brewing > 0f) {
            stroke.color = Color.argb(110, 255, 255, 255); stroke.strokeWidth = 2.5f * dp
            for (k in 0 until 3) {
                val rr = r * (0.25f + k * 0.22f)
                c.drawArc(fx - rr, fy + r * 0.2f - rr * 0.5f, fx + rr, fy + r * 0.2f + rr * 0.5f,
                    Math.toDegrees(stirAngle.toDouble()).toFloat() + k * 120f, 110f, false, stroke)
            }
        }
        // bubbles inside
        for (k in 0 until 6) {
            val bt = (t * (0.6f + k * 0.13f) + k * 0.37f) % 1f
            val bx = fx + sin(k * 2.1f + t) * r * 0.5f
            val byy = fy + r * 0.8f - bt * r * 0.9f
            fill.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(bx, byy, (2f + k % 3) * dp, fill)
        }
        c.restore()
        stroke.color = Color.argb(160, 230, 240, 255); stroke.strokeWidth = 3f * dp
        c.drawCircle(fx, fy, r, stroke)
        c.drawLine(fx - r * 0.28f, fy - r * 1.55f, fx - r * 0.28f, fy - r * 0.95f, stroke)
        c.drawLine(fx + r * 0.28f, fy - r * 1.55f, fx + r * 0.28f, fy - r * 0.95f, stroke)
        fill.color = Color.argb(90, 255, 255, 255)
        c.drawCircle(fx - r * 0.45f, fy - r * 0.35f, r * 0.12f, fill)
        c.drawOval(fx - r * 0.7f, fy - r * 0.15f, fx - r * 0.55f, fy + r * 0.35f, fill)
        drawFlaskFace(c, fx, fy, r, 0f)
        c.restore()

        // stir meter: a ring around the flask that fills as you stir
        if (brewing > 0f) {
            val q = quality()
            val rr = r + 16f * dp
            stroke.strokeWidth = 8f * dp
            stroke.color = Color.argb(50, 255, 255, 255)
            c.drawCircle(fx, fy, rr, stroke)
            stroke.color = if (q >= 1f) DUST else MINT
            c.drawArc(fx - rr, fy - rr, fx + rr, fy + rr, -90f, 360f * q, false, stroke)

            // a big arrow sweeping around the flask shows which way to stir
            val ar = rr + 26f * dp
            val ang = (t * 220f) % 360f
            stroke.color = Color.argb(235, 255, 255, 255); stroke.strokeWidth = 6f * dp
            c.drawArc(fx - ar, fy - ar, fx + ar, fy + ar, ang - 80f, 80f, false, stroke)
            val a = Math.toRadians(ang.toDouble())
            val hx = fx + (cos(a) * ar).toFloat(); val hy = fy + (sin(a) * ar).toFloat()
            val tx = (-sin(a)).toFloat(); val ty = cos(a).toFloat()     // direction of travel
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()        // pointing outward
            fill.color = Color.WHITE
            path.reset()
            path.moveTo(hx + tx * 18f * dp, hy + ty * 18f * dp)
            path.lineTo(hx + nx * 12f * dp - tx * 4f * dp, hy + ny * 12f * dp - ty * 4f * dp)
            path.lineTo(hx - nx * 12f * dp - tx * 4f * dp, hy - ny * 12f * dp - ty * 4f * dp)
            path.close()
            c.drawPath(path, fill)
            // a ghost fingertip rides the arrow until the player starts stirring
            if (stirTotal < 1.5f) {
                val p = 0.5f + 0.5f * sin(t * 6f)
                fill.color = Color.argb((90 + 60 * p).toInt(), 255, 255, 255)
                c.drawCircle(hx - tx * 30f * dp, hy - ty * 30f * dp, 16f * dp, fill)
                fill.color = Color.argb(230, 255, 255, 255)
                c.drawCircle(hx - tx * 30f * dp, hy - ty * 30f * dp, 9f * dp, fill)
            }
            text.textSize = 26f * dp
            text.color = if (q >= 1f) DUST else MINT
            c.drawText(if (q >= 1f) "PERFECT!" else "STIR!", fx, fy - r * 1.8f, text)
            text.textSize = 13f * dp; text.color = Color.WHITE
            if (q < 1f) c.drawText("Trace circles around the flask", fx, fy - r * 1.8f + 20f * dp, text)
            text.color = Color.WHITE
        }

        text.textSize = 13f * dp; text.color = TEXT_DIM
        val hint = if (labMode == 0) {
            if (slotsFull()) (0 until slotCount()).joinToString(" + ") { Data.INGREDIENTS[slots[it]].name }
            else if (slotCount() == 3) "Drag three ingredients into the flask" else "Drag two ingredients into the flask"
        } else "Pick two Grown mushrooms"
        val target = specialTarget()
        if (target >= 0) drawChanceMeter(c, fx, fy + r + 30f * dp, target)
        else if (brewing <= 0f && labMode <= 1) c.drawText(hint, fx, fy + r + 44f * dp, text)
        if (labMode == 3) drawCook(c, w, h)
        text.color = Color.WHITE

        drawLabFriends(c, w)

        if (labMode == 1) {
            text.textSize = 12f * dp; text.color = TEXT_DIM
            c.drawText("Parents rest 20 min after breeding. Some pairs make new species!", w / 2, h * 0.45f + 74f * dp, text)
            text.color = Color.WHITE
            if (lab.garden.isEmpty()) {
                text.textSize = 15f * dp; text.color = TEXT_DIM
                c.drawText("No mushrooms yet. Brew some first!", w / 2, h * 0.65f, text)
                text.color = Color.WHITE
            }
        }
    }

    /** "Rare: Storm Cap · 62%" with a bar that fills as you stir. */
    private fun drawChanceMeter(c: Canvas, cx: Float, y: Float, target: Int) {
        val sp = Data.SPECIES[target]
        val ch = successChance(target)
        val best = successChance(target, 1f)
        val label = "${Data.TIER_NAMES[sp.tier]}: ${if (lab.discovered[target] || lab.fizzles[target] >= 2) sp.name else "???"} · ${(ch * 100).toInt()}% chance" +
            (if (lab.jobHolder(Data.JOB_STIR) != null) " 🥄" else "")
        text.textSize = 13f * dp; text.color = DUST
        c.drawText(label, cx, y + 8f * dp, text)
        val bw = 190f * dp; val bh = 9f * dp; val by = y + 16f * dp
        fill.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + bh, bh / 2, bh / 2, fill)
        // ghost of how high a perfect stir could take it
        fill.color = Color.argb(60, 255, 214, 110)
        c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + bw * best, by + bh, bh / 2, bh / 2, fill)
        fill.color = when { ch >= 0.66f -> MINT; ch >= 0.4f -> DUST; else -> Color.rgb(255, 150, 90) }
        c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + max(bh, bw * ch), by + bh, bh / 2, bh / 2, fill)
        if (brewing <= 0f) {
            text.textSize = 11f * dp; text.color = TEXT_DIM
            c.drawText("Stir well for up to ${(best * 100).toInt()}%", cx, by + bh + 13f * dp, text)
        }
        text.color = Color.WHITE
    }

    private fun drawSlot(c: Canvas, r: RectF, k: Int) {
        stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = 2f * dp
        fill.color = PANEL
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, stroke)
        if (labMode == 0) {
            val i = slots[k]
            if (i >= 0) drawIngredient(c, i, r.centerX(), r.centerY(), r.width() * 0.36f)
            else { text.textSize = 22f * dp; text.color = TEXT_DIM; c.drawText("?", r.centerX(), r.centerY() + 8f * dp, text); text.color = Color.WHITE }
        } else {
            val s = picks.getOrNull(k)?.let { shroom(it) }
            if (s != null) drawShroom(c, s.species, r.centerX(), r.centerY() + r.height() * 0.32f, r.width() * 0.5f, 0, 0f, false, false, shiny = s.shiny, who = s)
            else { text.textSize = 22f * dp; text.color = TEXT_DIM; c.drawText("?", r.centerX(), r.centerY() + 8f * dp, text); text.color = Color.WHITE }
        }
    }

    private fun drawIngredientCard(c: Canvas, r: RectF, i: Int, left: Int) {
        val ev = Data.INGREDIENTS[i].event
        if (ev > 0) {
            stroke.color = Data.EVENT_COLORS[ev]; stroke.strokeWidth = 2f * dp
            c.drawRoundRect(r.left + 1f * dp, r.top + 1f * dp, r.right - 1f * dp, r.bottom - 1f * dp, 14f * dp, 14f * dp, stroke)
        }
        drawIngredient(c, i, r.centerX(), r.top + r.height() * 0.36f, min(r.width(), r.height()) * 0.2f)
        text.textSize = 13f * dp
        while (text.textSize > 9f * dp && text.measureText(Data.INGREDIENTS[i].name) > r.width() - 8f * dp) text.textSize -= 0.5f * dp
        c.drawText(Data.INGREDIENTS[i].name, r.centerX(), r.top + r.height() * 0.72f, text)
        if (ev > 0) {
            text.textSize = 11f * dp
            c.drawText(Data.EVENT_ICONS[ev], r.right - 12f * dp, r.top + 16f * dp, text)
        }
        text.textSize = 12f * dp
        text.color = if (left > 0) TEXT_DIM else ACCENT
        c.drawText("×$left", r.centerX(), r.top + r.height() * 0.9f, text)
        text.color = Color.WHITE
    }

    private fun drawPickCard(c: Canvas, r: RectF, s: Shroom, now: Long) {
        drawShroom(c, s.species, r.centerX(), r.top + r.height() * 0.62f, r.width() * 0.42f, s.mood(), 0f, false, false, shiny = s.shiny, who = s)
        text.textSize = 10f * dp
        val name = Data.SPECIES[s.species].name.split(" ").first()
        c.drawText(name, r.centerX(), r.top + r.height() * 0.8f, text)
        text.textSize = 10f * dp; text.color = TEXT_DIM
        val status = when {
            s.stage() < 2 -> Data.STAGE_NAMES[s.stage()]
            s.restUntil > now -> "rest ${(s.restUntil - now) / 60000 + 1}m"
            else -> "ready"
        }
        if (status == "ready") text.color = MINT
        c.drawText(status, r.centerX(), r.top + r.height() * 0.94f, text)
        text.color = Color.WHITE
    }

    // ----- Dex -----

    private fun drawDex(c: Canvas, w: Float, h: Float) {
        text.textSize = 14f * dp; text.color = TEXT_DIM
        val baseFound = Data.BASE_SPECIES.count { lab.discovered[it] }
        val evAll = Data.SPECIES.indices.filter { Data.SPECIES[it].event > 0 }
        c.drawText("$baseFound of ${Data.BASE_SPECIES.size} species · ${evAll.count { lab.discovered[it] }} of ${evAll.size} event", w / 2, topBar + 26f * dp, text)
        text.color = Color.WHITE
        if (dexTab == 2) {
            val block = (dexBottom() - dexTop()) / 3
            val now = eventNow()
            for (ev in 1..3) {
                val y = dexTop() + (ev - 1) * block + 14f * dp
                text.textAlign = Paint.Align.LEFT; text.textSize = 13f * dp; text.color = Data.EVENT_COLORS[ev]
                c.drawText("${Data.EVENT_ICONS[ev]} ${Data.EVENTS[ev]}", 16f * dp, y, text)
                text.textAlign = Paint.Align.RIGHT; text.textSize = 11f * dp
                text.color = if (ev == now) MINT else TEXT_DIM
                c.drawText(if (ev == now) "On now, until ${Data.EVENT_ENDS[ev]}!" else "${Data.EVENT_STARTS[ev]} – ${Data.EVENT_ENDS[ev]}", w - 16f * dp, y, text)
            }
            text.textAlign = Paint.Align.CENTER; text.color = Color.WHITE
        }

        val top = dexBottom() + 8f * dp
        val r = RectF(8f * dp, top, w - 8f * dp, navTop - 6f * dp)
        fill.color = PANEL
        c.drawRoundRect(r, 20f * dp, 20f * dp, fill)
        val pad = 22f * dp
        text.textAlign = Paint.Align.LEFT
        if (dexPick < 0) {
            text.textSize = 14f * dp; text.color = TEXT_DIM
            c.drawText("Tap a mushroom to see how to make it", pad, top + 34f * dp, text)
            text.color = Color.WHITE
        } else {
            val sp = Data.SPECIES[dexPick]
            val known = lab.discovered[dexPick]
            text.textSize = 19f * dp
            c.drawText(if (known) sp.name else "???", pad, top + 32f * dp, text)
            text.textSize = 13f * dp
            text.color = if (sp.event > 0) Data.EVENT_COLORS[sp.event] else when (sp.tier) { 3 -> DUST; 2 -> Color.rgb(140, 200, 255); else -> TEXT_DIM }
            c.drawText(tierLabel(dexPick), pad, top + 52f * dp, text)
            text.color = Color.WHITE
            text.textSize = 14f * dp
            if (known) {
                c.drawText("Recipe: ${Data.recipeText(dexPick)}", pad, top + 78f * dp, text)
                text.color = TEXT_DIM
                c.drawText("Favorite food: ${Data.FOODS[sp.favFood].name}", pad, top + 100f * dp, text)
            } else {
                text.color = TEXT_DIM
                c.drawText("Hint: ${sp.hint}", pad, top + 78f * dp, text)
            }
            if (sp.event > 0) {
                text.color = if (Data.offSeason(dexPick)) ACCENT else MINT
                c.drawText(if (Data.offSeason(dexPick)) "Only during ${Data.EVENTS[sp.event]} (${Data.EVENT_STARTS[sp.event]} – ${Data.EVENT_ENDS[sp.event]})"
                    else "Available now, until ${Data.EVENT_ENDS[sp.event]}!", pad, top + (if (known) 122f else 100f) * dp, text)
            }
            text.color = Color.WHITE
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawDexCell(c: Canvas, r: RectF, i: Int) {
        val known = lab.discovered[i]
        val sp = Data.SPECIES[i]
        if (sp.tier >= 2 || sp.event > 0) {
            stroke.color = if (sp.event > 0) Data.EVENT_COLORS[sp.event] else if (sp.tier == 3) DUST else Color.rgb(140, 200, 255)
            stroke.alpha = 150; stroke.strokeWidth = 1.5f * dp
            c.drawRoundRect(r, 14f * dp, 14f * dp, stroke)
            stroke.alpha = 255
        }
        drawShroom(c, i, r.centerX(), r.top + r.height() * 0.7f, min(r.width() * 0.5f, r.height() * 0.5f), 0,
            0f, false, !known)
        if (lab.shinyFound[i]) {
            fill.color = Color.WHITE
            drawSparkle(c, r.right - 9f * dp, r.top + 9f * dp, 2.5f * dp)
        }
        text.textSize = 9.5f * dp
        text.color = if (known) Color.WHITE else TEXT_DIM
        c.drawText(if (known) sp.name.split(" ").first() else "???", r.centerX(), r.bottom - 6f * dp, text)
        text.color = Color.WHITE
    }

    // ----- Shop -----

    // ----- Popup / tutorial / toast -----

    private fun drawPopup(c: Canvas, w: Float, h: Float, p: Popup) {
        fill.color = Color.argb(190, 10, 6, 24)
        c.drawRect(0f, 0f, w, h, fill)
        val k = 0.6f + 0.4f * easeOutBack(min(1f, popupT / 0.35f))
        c.save(); c.scale(k, k, w / 2, h * 0.47f)
        drawPopupInner(c, w, h, p)
        c.restore()
    }

    private fun drawPopupInner(c: Canvas, w: Float, h: Float, p: Popup) {
        val card = RectF(24f * dp, h * 0.18f, w - 24f * dp, h * 0.76f)
        fill.color = PANEL
        c.drawRoundRect(card, 28f * dp, 28f * dp, fill)
        // rays behind the new mushroom
        if (p.isNew) {
            c.save()
            c.translate(w / 2, h * 0.44f)
            c.rotate(t * 20f)
            fill.color = Color.argb(40, 255, 214, 110)
            for (k in 0 until 12) {
                c.rotate(30f)
                path.reset(); path.moveTo(0f, 0f); path.lineTo(-18f * dp, -160f * dp); path.lineTo(18f * dp, -160f * dp); path.close()
                c.drawPath(path, fill)
            }
            c.restore()
        }
        val sp = Data.SPECIES[p.species]
        val revealed = popupT >= 0.9f
        val size = min(w * 0.38f, 150f * dp)
        if (!revealed) {
            // mystery silhouette wobbling before the reveal
            c.save()
            c.rotate(sin(popupT * 40f) * 6f * popupT, w / 2, h * 0.52f)
            drawShroom(c, p.species, w / 2, h * 0.52f, size * (0.6f + 0.4f * popupT / 0.9f), 0, 0f, false, true)
            c.restore()
            text.textSize = 40f * dp; text.color = Color.WHITE
            c.drawText("?", w / 2, h * 0.4f, text)
            text.textSize = 15f * dp; text.color = TEXT_DIM
            c.drawText("Something is growing...", w / 2, card.top + 40f * dp, text)
            text.color = Color.WHITE
            return
        }
        val since = popupT - 0.9f
        if (since < 0.3f) {
            fill.color = Color.WHITE; fill.alpha = (255 * (1f - since / 0.3f)).toInt()
            c.drawCircle(w / 2, h * 0.45f, size * (1f + since * 4f), fill); fill.alpha = 255
        }
        text.textSize = 15f * dp
        text.color = when { p.shiny -> Color.WHITE; p.isNew -> DUST; else -> MINT }
        c.drawText(when {
            p.shiny -> "✨ SHINY! ✨"
            p.missed >= 0 -> "So close! It fizzled..."
            p.isNew -> "✦ NEW DISCOVERY! ✦"
            p.crossed -> "A new sprout!"
            else -> "It's alive!"
        }, w / 2, card.top + 40f * dp, text)
        text.color = Color.WHITE
        val pop = min(1f, since / 0.25f)
        drawShroom(c, p.species, w / 2, h * 0.52f, size * (0.8f + 0.2f * pop), 0, sin(t * 2.5f), (t % 3f) < 0.15f, false, shiny = p.shiny, who = shroom(p.shroomId))
        text.textSize = 26f * dp
        val baby = shroom(p.shroomId)
        c.drawText((if (p.shiny) "Shiny " else "") + sp.name, w / 2, h * 0.59f, text)
        if (baby != null) {
            text.textSize = 14f * dp; text.color = ACCENT
            c.drawText("Meet ${baby.name}, a ${Data.TRAITS[baby.trait].lowercase()} one" +
                (if (baby.variant == 1) " · ALBINO!" else if (baby.variant == 2) " · NEON!" else ""), w / 2, h * 0.59f - 30f * dp, text)
            text.color = Color.WHITE
        }
        text.textSize = 13f * dp; text.color = TEXT_DIM
        c.drawText("${tierLabel(p.species)} · Loves ${Data.FOODS[sp.favFood].name}", w / 2, h * 0.59f + 24f * dp, text)
        if (p.missed >= 0) {
            val m = Data.SPECIES[p.missed]
            text.color = ACCENT
            c.drawText("The ${Data.TIER_NAMES[m.tier].lowercase()} ${if (lab.discovered[p.missed]) m.name else "recipe"} didn't take. Try again!", w / 2, h * 0.59f + 44f * dp, text)
            text.color = Color.WHITE
            return
        }
        val grade = stars(p.quality) + "  " + when { p.quality >= 1f -> "Perfect brew!"; p.quality >= 0.6f -> "Great stir"; p.quality >= 0.25f -> "Good stir"; else -> "Tip: stir in circles while it brews" }
        text.color = if (p.quality >= 1f) DUST else TEXT_DIM
        c.drawText(grade, w / 2, h * 0.59f + 44f * dp, text)
        text.color = Color.WHITE
    }

    /** "Common", or "Shroomoween · Common" for event species. */
    private fun tierLabel(i: Int): String {
        val sp = Data.SPECIES[i]
        return if (sp.event > 0) "${Data.EVENT_ICONS[sp.event]} ${Data.EVENTS[sp.event]} · ${Data.TIER_NAMES[sp.tier]}" else Data.TIER_NAMES[sp.tier]
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(test) > maxW && line.isNotEmpty()) { out.add(line); line = word } else line = test
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawToast(c: Canvas, w: Float, h: Float) {
        val tt = toasts.firstOrNull() ?: return
        val a = (min(1f, toastTime * 3f) * min(1f, (2.4f - toastTime) * 6f)).coerceIn(0f, 1f)
        text.textSize = 14f * dp
        val tw = min(w - 24f * dp, text.measureText(tt.text) + 36f * dp)
        val y = navTop - 30f * dp
        val r = RectF(w / 2 - tw / 2, y - 20f * dp, w / 2 + tw / 2, y + 14f * dp)
        fill.color = Color.rgb(20, 14, 40); fill.alpha = (235 * a).toInt()
        c.drawRoundRect(r, 17f * dp, 17f * dp, fill)
        stroke.color = tt.color; stroke.alpha = (200 * a).toInt(); stroke.strokeWidth = 1.5f * dp
        c.drawRoundRect(r, 17f * dp, 17f * dp, stroke)
        stroke.alpha = 255; fill.alpha = 255
        text.color = tt.color; text.alpha = (255 * a).toInt()
        c.drawText(tt.text, w / 2, y + 2f * dp, text)
        text.color = Color.WHITE; text.alpha = 255
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            fill.color = p.color
            fill.alpha = (255 * min(1f, a * 2f)).toInt()
            when (p.type) {
                P_HEART -> drawHeart(c, p.x, p.y, 7f * dp)
                P_BUBBLE -> { fill.alpha = (150 * a).toInt(); c.drawCircle(p.x, p.y, 4f * dp, fill) }
                P_DUST -> c.drawCircle(p.x, p.y, 4f * dp, fill)
                P_Z, P_NOTE -> {
                    text.color = p.color; text.alpha = (255 * min(1f, a * 1.5f)).toInt()
                    text.textSize = (if (p.type == P_Z) 11f + (1f - a) * 8f else 15f) * dp
                    c.drawText(if (p.type == P_Z) "z" else "♪", p.x, p.y, text)
                    text.color = Color.WHITE; text.alpha = 255
                }
                else -> drawSparkle(c, p.x, p.y, 4f * dp * (0.5f + a))
            }
        }
        fill.alpha = 255
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x - s * 0.5f, y, s * 0.6f, fill)
        c.drawCircle(x + s * 0.5f, y, s * 0.6f, fill)
        path.reset(); path.moveTo(x - s * 1.08f, y + s * 0.2f); path.lineTo(x, y + s * 1.3f); path.lineTo(x + s * 1.08f, y + s * 0.2f); path.close()
        c.drawPath(path, fill)
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y - s * 2); path.lineTo(x + s * 0.5f, y - s * 0.5f); path.lineTo(x + s * 2, y)
        path.lineTo(x + s * 0.5f, y + s * 0.5f); path.lineTo(x, y + s * 2); path.lineTo(x - s * 0.5f, y + s * 0.5f)
        path.lineTo(x - s * 2, y); path.lineTo(x - s * 0.5f, y - s * 0.5f); path.close()
        c.drawPath(path, fill)
    }

    // ----- Buttons -----

    private fun drawButton(c: Canvas, b: Button) {
        val pressed = pressedId == b.id && b.enabled
        val r = RectF(b.rect)
        val isNav = b.id.startsWith("nav") || b.id == "gear"
        val radius = min(16f * dp, r.height() / 2)
        val custom = b.custom

        if (b.style == STYLE_GHOST && custom != null) { custom(c, r); return }
        if (b.style == STYLE_GHOST) {
            text.textSize = 12f * dp; text.color = Color.argb(if (pressed) 255 else 190, 255, 255, 255)
            c.drawText(b.label, r.centerX(), r.centerY() + 4f * dp, text)
            stroke.color = text.color; stroke.strokeWidth = 1f * dp
            val tw = text.measureText(b.label)
            c.drawLine(r.centerX() - tw / 2, r.centerY() + 7f * dp, r.centerX() + tw / 2, r.centerY() + 7f * dp, stroke)
            text.color = Color.WHITE
            return
        }
        if (isNav) {
            if (pressed) { fill.color = Color.argb(30, 255, 255, 255); c.drawRoundRect(r, radius, radius, fill) }
            custom?.invoke(c, r)
            text.color = Color.WHITE
            return
        }

        if (pressed) { c.save(); c.scale(0.95f, 0.95f, r.centerX(), r.centerY()) }
        // colors: top of the gradient, bottom of the gradient, text
        val (top, bottom, tc) = when (b.style) {
            STYLE_PRIMARY -> if (b.enabled) Triple(shade(ACCENT, 0.18f), shade(ACCENT, -0.18f), Color.WHITE)
                             else Triple(Color.rgb(100, 72, 112), Color.rgb(80, 56, 94), Color.argb(170, 255, 255, 255))
            STYLE_DANGER -> Triple(Color.rgb(150, 52, 78), Color.rgb(108, 32, 56), Color.rgb(255, 205, 212))
            STYLE_TAB_ON -> Triple(Color.WHITE, Color.rgb(222, 212, 250), BG)
            STYLE_PICKED -> Triple(shade(PANEL_LIGHT, 0.12f), PANEL_LIGHT, Color.WHITE)
            STYLE_CARD -> if (b.enabled) Triple(shade(PANEL, 0.08f), shade(PANEL, -0.06f), Color.WHITE)
                          else Triple(Color.rgb(40, 30, 70), Color.rgb(34, 25, 60), Color.WHITE)
            else -> Triple(shade(PANEL_LIGHT, 0.06f), shade(PANEL, -0.02f), Color.WHITE)
        }
        val raised = b.style != STYLE_CARD && b.style != STYLE_PICKED
        val lift = if (raised && !pressed) 4f * dp else 0f
        if (pressed && raised) r.offset(0f, 3f * dp)
        if (raised) {   // the button's "side", for a chunky 3D look
            fill.color = shade(bottom, -0.45f)
            c.drawRoundRect(r.left, r.top + lift, r.right, r.bottom + lift, radius, radius, fill)
        } else if (pressed) r.inset(2f * dp, 2f * dp)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        // glossy highlight along the top
        fill.color = Color.argb(if (raised) 40 else 18, 255, 255, 255)
        c.drawRoundRect(r.left + 3f * dp, r.top + 2f * dp, r.right - 3f * dp, r.top + r.height() * 0.45f, radius * 0.8f, radius * 0.8f, fill)
        if (b.style == STYLE_PICKED) {
            stroke.color = MINT; stroke.strokeWidth = 2.5f * dp
            c.drawRoundRect(r, radius, radius, stroke)
        } else if (b.style == STYLE_CARD) {
            stroke.color = Color.argb(30, 255, 255, 255); stroke.strokeWidth = 1f * dp
            c.drawRoundRect(r, radius, radius, stroke)
        }

        if (custom != null) {
            text.color = Color.WHITE
            custom(c, r)
        } else {
            text.color = tc
            text.textSize = if (b.label.length > 12) 15f * dp else 18f * dp
            while (text.textSize > 9f * dp && text.measureText(b.label) > r.width() - 14f * dp) text.textSize -= 1f * dp
            val ty = r.centerY() - (text.descent() + text.ascent()) / 2
            if (b.style == STYLE_PRIMARY || b.style == STYLE_DANGER) {   // soft text shadow
                val keep = text.color
                text.color = Color.argb(70, 0, 0, 0)
                c.drawText(b.label, r.centerX(), ty + 1.5f * dp, text)
                text.color = keep
            }
            c.drawText(b.label, r.centerX(), ty, text)
        }
        if (pressed) c.restore()
        text.color = Color.WHITE
        text.alpha = 255
    }

    private fun drawNavItem(c: Canvas, r: RectF, tb: Tab) {
        val on = tab == tb
        val cx = r.centerX(); val cy = r.top + 30f * dp
        if (on) {
            fill.color = Color.argb(45, 255, 120, 170)
            c.drawRoundRect(cx - 30f * dp, r.top + 8f * dp, cx + 30f * dp, r.top + 50f * dp, 18f * dp, 18f * dp, fill)
        }
        val col = if (on) ACCENT else Color.argb(150, 255, 255, 255)
        fill.color = col; stroke.color = col; stroke.strokeWidth = 2.5f * dp
        when (tb) {
            Tab.GARDEN -> {
                c.drawRoundRect(cx - 4f * dp, cy - 2f * dp, cx + 4f * dp, cy + 10f * dp, 3f * dp, 3f * dp, fill)
                path.reset(); path.moveTo(cx - 13f * dp, cy); path.cubicTo(cx - 13f * dp, cy - 16f * dp, cx + 13f * dp, cy - 16f * dp, cx + 13f * dp, cy); path.close()
                c.drawPath(path, fill)
            }
            Tab.LAB -> {
                c.drawCircle(cx, cy + 4f * dp, 9f * dp, stroke)
                c.drawLine(cx - 3f * dp, cy - 5f * dp, cx - 3f * dp, cy - 12f * dp, stroke)
                c.drawLine(cx + 3f * dp, cy - 5f * dp, cx + 3f * dp, cy - 12f * dp, stroke)
                c.drawCircle(cx, cy + 6f * dp, 5f * dp, fill)
            }
            Tab.DEX -> {
                c.drawRoundRect(cx - 12f * dp, cy - 11f * dp, cx + 12f * dp, cy + 11f * dp, 3f * dp, 3f * dp, stroke)
                c.drawLine(cx, cy - 11f * dp, cx, cy + 11f * dp, stroke)
            }
            Tab.JOURNAL -> {   // a star badge
                path.reset()
                for (k in 0 until 10) {
                    val a = -Math.PI / 2 + k * Math.PI / 5
                    val rr = if (k % 2 == 0) 12f * dp else 5f * dp
                    val px = cx + (cos(a) * rr).toFloat(); val py = cy + (sin(a) * rr).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, stroke)
            }
            Tab.SHOP -> {
                c.drawRoundRect(cx - 11f * dp, cy - 5f * dp, cx + 11f * dp, cy + 11f * dp, 4f * dp, 4f * dp, stroke)
                c.drawArc(cx - 6f * dp, cy - 13f * dp, cx + 6f * dp, cy - 1f * dp, 180f, 180f, false, stroke)
            }
        }
        text.textSize = 11f * dp
        text.color = col
        c.drawText(when (tb) { Tab.GARDEN -> "GARDEN"; Tab.LAB -> "LAB"; Tab.DEX -> "DEX"; Tab.SHOP -> "SHOP"; Tab.JOURNAL -> "JOURNAL" },
            cx, r.top + 60f * dp, text)
        text.color = Color.WHITE
        if (tb == Tab.JOURNAL && journalAlert()) {
            fill.color = ACCENT
            c.drawCircle(cx + 14f * dp, cy - 12f * dp, 5f * dp + sin(t * 5f) * 1f * dp, fill)
        }
    }

    private fun drawSpeaker(c: Canvas, r: RectF) {
        val cx = r.centerX() - 4f * dp; val cy = r.centerY()
        fill.color = Color.argb(200, 255, 255, 255)
        path.reset()
        path.moveTo(cx - 8f * dp, cy - 4f * dp); path.lineTo(cx - 3f * dp, cy - 4f * dp); path.lineTo(cx + 3f * dp, cy - 9f * dp)
        path.lineTo(cx + 3f * dp, cy + 9f * dp); path.lineTo(cx - 3f * dp, cy + 4f * dp); path.lineTo(cx - 8f * dp, cy + 4f * dp); path.close()
        c.drawPath(path, fill)
        stroke.color = Color.argb(200, 255, 255, 255); stroke.strokeWidth = 2f * dp
        if (soundMode == 2) {
            c.drawLine(cx + 7f * dp, cy - 5f * dp, cx + 15f * dp, cy + 5f * dp, stroke)
            c.drawLine(cx + 15f * dp, cy - 5f * dp, cx + 7f * dp, cy + 5f * dp, stroke)
        } else {
            c.drawArc(cx - 2f * dp, cy - 7f * dp, cx + 12f * dp, cy + 7f * dp, -50f, 100f, false, stroke)
            if (soundMode == 0) c.drawArc(cx - 4f * dp, cy - 12f * dp, cx + 18f * dp, cy + 12f * dp, -50f, 100f, false, stroke)
        }
    }

    // ---------- Art: mushrooms ----------

    /** Lighten (f > 0) or darken (f < 0) a color. */
    private fun shade(col: Int, f: Float): Int {
        fun ch(v: Int) = if (f >= 0) (v + (255 - v) * f).toInt() else (v * (1f + f)).toInt()
        return Color.rgb(ch(Color.red(col)).coerceIn(0, 255), ch(Color.green(col)).coerceIn(0, 255), ch(Color.blue(col)).coerceIn(0, 255))
    }

    /** Soft round shadow on the ground. */
    private fun groundShadow(c: Canvas, cx: Float, y: Float, rx: Float, alpha: Int) {
        c.save()
        c.scale(1f, 0.28f, cx, y)
        fill.shader = RadialGradient(cx, y, rx, Color.argb(alpha, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, y, rx, fill)
        fill.shader = null
        c.restore()
    }

    /**
     * Draws a cute, softly lit mushroom standing at (cx, baseY).
     * mood: 0 happy, 1 okay, 2 hungry, 3 sad. bob: -1..1 idle squash-and-stretch.
     * facing / phase / moving animate walking; held makes it dangle with little feet kicking.
     */
    private fun drawShroom(c: Canvas, species: Int, cx: Float, baseY0: Float, size: Float, mood0: Int,
                           bob: Float, blink: Boolean, silhouette: Boolean,
                           facing: Float = 0f, phase: Float = 0f, moving: Boolean = false, held: Boolean = false,
                           shiny: Boolean = false, who: Shroom? = null, sleeping: Boolean = false) {
        val sp = Data.SPECIES[species]
        // Colors come from the species, then the mushroom's own genes
        val variant = who?.variant ?: 0
        fun gene(col: Int): Int {
            if (col == 0) return 0
            val hsv = FloatArray(3)
            Color.colorToHSV(col, hsv)
            hsv[0] = (hsv[0] + (who?.tint ?: 0f) + 360f) % 360f
            when (variant) {
                1 -> { hsv[1] *= 0.12f; hsv[2] = 0.97f }                       // albino: pale and pinkish-white
                2 -> { hsv[1] = 1f; hsv[2] = 1f; hsv[0] = (hsv[0] + t * 40f) % 360f } // neon: vivid, color-shifting
            }
            return Color.HSVToColor(hsv)
        }
        val capC = gene(sp.cap)
        val stemC = if (variant == 1) Color.rgb(255, 240, 242) else sp.stem
        val spotStyle = who?.spots ?: 0
        val spotC = if (spotStyle == 2) 0 else if (variant == 1) Color.rgb(255, 200, 215) else gene(sp.spots)
        val spotMul = if (spotStyle == 3) 1.5f else 1f
        val glows = sp.glow || variant == 2
        val s = size
        val dark = Color.rgb(44, 34, 78)
        fun col(v: Int) = if (silhouette) dark else v
        val sprout = s < 50f * dp && sp.shape != 1
        val mood = if (held) 2 else mood0
        val ex0 = who?.expr ?: 0
        val jig = who?.jiggle ?: 0f
        val ia = who?.idleAnim ?: 0
        val iaT = who?.idleAnimT ?: 0f

        // walking bounce / hop, or flying through the air after a toss
        val lift = when {
            held -> 0f
            moving && sprout -> abs(sin(phase * 0.5f)) * s * 0.28f
            moving -> abs(sin(phase)) * s * 0.05f
            else -> 0f
        } + (who?.airH ?: 0f)
        val baseY = baseY0 - lift
        if (!held) groundShadow(c, cx, baseY0, s * 0.55f * (1f - min(0.7f, lift / (s * 1.5f))), if (silhouette) 60 else 110)

        c.save()
        if (held) c.rotate(sin(t * 7f) * 9f, cx, baseY - s)
        else if (moving) c.rotate(sin(phase) * 5f, cx, baseY)
        who?.let { if (it.spinT > 0f) c.rotate((1f - it.spinT / 0.6f) * 360f, cx, baseY - s * 0.5f) }
        if (who != null && who.airborne()) c.rotate(who.fvx * 300f, cx, baseY - s * 0.5f)
        c.scale(1f - 0.03f * bob, 1f + 0.04f * bob, cx, baseY)
        if (jig > 0.01f) { val wv = sin(t * 28f) * jig; c.scale(1f + 0.12f * wv, 1f - 0.12f * wv, cx, baseY) }
        when (ia) {
            1 -> c.scale(1f, 1.05f, cx, baseY)
            2 -> c.scale(0.95f, 1f + 0.12f * sin((1.2f - iaT) / 1.2f * 3.1416f), cx, baseY)
            3 -> if (iaT > 0.35f) c.scale(1.05f, 0.93f, cx, baseY)
        }

        if (glows && !silhouette) {
            fill.shader = RadialGradient(cx, baseY - s * 0.7f, s * 0.95f,
                Color.argb((90 + 40 * sin(t * 3f)).toInt(), Color.red(capC), Color.green(capC), Color.blue(capC)),
                Color.argb(0, Color.red(capC), Color.green(capC), Color.blue(capC)), Shader.TileMode.CLAMP)
            c.drawCircle(cx, baseY - s * 0.7f, s * 0.95f, fill)
            fill.shader = null
        }

        val stemH = when (sp.shape) { 1 -> s * 0.16f; 4 -> s * 0.36f; else -> s * 0.5f }
        val stemW = s * 0.42f
        val capBottom = baseY - stemH + s * 0.08f
        var capTop: Float

        // little feet
        if (!silhouette) {
            fill.color = shade(stemC, -0.25f)
            val fw = stemW * 0.32f; val fh = s * 0.07f
            for (side in intArrayOf(-1, 1)) {
                val kick = when {
                    held -> abs(sin(t * 9f + side)) * s * 0.08f
                    moving -> max(0f, sin(phase + if (side < 0) 0f else 3.14f)) * s * 0.06f
                    else -> 0f
                }
                val fx = cx + side * stemW * 0.3f
                c.drawOval(fx - fw, baseY - fh - kick + (if (held) s * 0.05f else 0f), fx + fw, baseY + fh * 0.5f - kick + (if (held) s * 0.05f else 0f), fill)
            }
        }

        // stem with side lighting
        if (silhouette) fill.color = dark
        else fill.shader = LinearGradient(cx - stemW / 2, 0f, cx + stemW / 2, 0f,
            intArrayOf(shade(stemC, -0.08f), shade(stemC, 0.2f), shade(stemC, -0.32f)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(cx - stemW / 2, baseY - stemH - s * 0.04f, cx + stemW / 2, baseY, stemW * 0.4f, stemW * 0.4f, fill)
        fill.shader = null

        // cap shader: light comes from the upper left
        fun capShader(x0: Float, y0: Float, r: Float) {
            if (silhouette) { fill.color = dark; return }
            fill.shader = RadialGradient(x0, y0, r,
                intArrayOf(shade(capC, 0.5f), capC, shade(capC, -0.4f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        fun spot(x: Float, y: Float, r0: Float, squash: Float) {
            val r = r0 * spotMul
            fill.color = spotC
            c.drawOval(x - r * squash, y - r, x + r * squash, y + r, fill)
            fill.color = Color.argb(90, 255, 255, 255)
            c.drawCircle(x - r * 0.3f * squash, y - r * 0.35f, r * 0.3f, fill)
        }

        path.reset()
        when (sp.shape) {
            1 -> { // puffball
                val r = s * 0.44f
                val cy = baseY - r - s * 0.02f
                capTop = cy - r
                capShader(cx - r * 0.35f, cy - r * 0.4f, r * 1.4f)
                c.drawCircle(cx, cy, r, fill)
                fill.shader = null
                if (spotC != 0 && !silhouette) {
                    for ((px, py) in arrayOf(-0.45f to -0.5f, 0.2f to -0.72f, 0.5f to -0.22f)) {
                        spot(cx + px * r, cy + (py + 0.45f) * r - r * 0.4f, r * 0.12f, 1f - abs(px) * 0.6f)
                    }
                }
            }
            2 -> { // morel cone
                val cw = s * 0.8f; val ch = s * 0.95f
                capTop = capBottom - ch
                path.moveTo(cx - cw / 2, capBottom)
                path.quadTo(cx - cw * 0.58f, capBottom - ch * 0.7f, cx, capBottom - ch)
                path.quadTo(cx + cw * 0.58f, capBottom - ch * 0.7f, cx + cw / 2, capBottom)
                path.quadTo(cx, capBottom + ch * 0.1f, cx - cw / 2, capBottom)
                path.close()
                capShader(cx - cw * 0.2f, capBottom - ch * 0.7f, ch * 1.1f)
                c.drawPath(path, fill)
                fill.shader = null
                if (spotC != 0 && !silhouette) {
                    for (row in 0 until 4) for (k in -1..1) {
                        val yy = capBottom - ch * (0.16f + row * 0.19f)
                        val spread = cw * 0.36f * (1f - row * 0.2f)
                        val px = cx + k * spread * 0.8f + (row % 2) * s * 0.04f
                        fill.color = shade(spotC, -0.15f)
                        c.drawOval(px - s * 0.05f * (1f - abs(k) * 0.35f), yy - s * 0.035f, px + s * 0.05f * (1f - abs(k) * 0.35f), yy + s * 0.035f, fill)
                    }
                }
            }
            3 -> { // funnel / chanterelle
                capTop = capBottom - s * 0.4f
                path.moveTo(cx - stemW * 0.5f, capBottom)
                path.lineTo(cx - s * 0.6f, capTop + s * 0.06f)
                path.quadTo(cx - s * 0.3f, capTop - s * 0.1f, cx, capTop)
                path.quadTo(cx + s * 0.3f, capTop - s * 0.1f, cx + s * 0.6f, capTop + s * 0.06f)
                path.lineTo(cx + stemW * 0.5f, capBottom)
                path.close()
                if (silhouette) fill.color = dark
                else fill.shader = LinearGradient(cx - s * 0.6f, capTop, cx + s * 0.6f, capBottom,
                    intArrayOf(shade(capC, 0.35f), capC, shade(capC, -0.4f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
                fill.shader = null
                if (!silhouette) {
                    // the hollow top of the funnel
                    fill.shader = RadialGradient(cx + s * 0.1f, capTop + s * 0.06f, s * 0.6f,
                        shade(if (spotC != 0) spotC else capC, -0.3f), shade(capC, 0.25f), Shader.TileMode.CLAMP)
                    c.drawOval(cx - s * 0.56f, capTop - s * 0.04f, cx + s * 0.56f, capTop + s * 0.13f, fill)
                    fill.shader = null
                }
            }
            4 -> { // tall horn
                capTop = capBottom - s * 0.9f
                capShader(cx - s * 0.1f, capTop + s * 0.25f, s * 0.6f)
                c.drawRoundRect(cx - s * 0.25f, capTop, cx + s * 0.25f, capBottom + s * 0.03f, s * 0.25f, s * 0.25f, fill)
                fill.shader = null
                if (spotC != 0 && !silhouette) {
                    stroke.color = shade(spotC, -0.1f); stroke.strokeWidth = s * 0.04f
                    for (k in 1..3) c.drawArc(cx - s * 0.22f, capTop + k * s * 0.2f - s * 0.04f, cx + s * 0.22f,
                        capTop + k * s * 0.2f + s * 0.06f, 20f, 140f, false, stroke)
                }
            }
            else -> { // dome
                val cw = s * 1.1f; val ch = s * 0.6f
                capTop = capBottom - ch
                // darker underside peeking out below the rim (gives the cap thickness)
                if (!silhouette) {
                    fill.color = shade(stemC, -0.4f)
                    c.drawOval(cx - cw * 0.44f, capBottom - ch * 0.1f, cx + cw * 0.44f, capBottom + ch * 0.26f, fill)
                }
                path.moveTo(cx - cw / 2, capBottom)
                path.cubicTo(cx - cw / 2, capBottom - ch * 1.3f, cx + cw / 2, capBottom - ch * 1.3f, cx + cw / 2, capBottom)
                path.quadTo(cx, capBottom + ch * 0.14f, cx - cw / 2, capBottom)
                path.close()
                capShader(cx - cw * 0.2f, capBottom - ch * 0.85f, cw * 0.85f)
                c.drawPath(path, fill)
                fill.shader = null
                if (spotC != 0 && !silhouette) {
                    val dots = arrayListOf(Triple(-0.28f, -0.5f, 0.085f), Triple(0.1f, -0.8f, 0.07f),
                            Triple(0.34f, -0.34f, 0.06f), Triple(-0.04f, -0.32f, 0.05f))
                    if (spotStyle == 1) dots.addAll(listOf(Triple(-0.38f, -0.24f, 0.045f), Triple(0.22f, -0.58f, 0.05f), Triple(-0.16f, -0.78f, 0.045f)))
                    for ((px, py, pr) in dots) spot(cx + px * cw, capBottom + py * ch, s * pr, 1f - abs(px) * 1.1f)
                }
                if (!silhouette) {
                    fill.color = Color.argb(90, 255, 255, 255)
                    c.drawOval(cx - cw * 0.34f, capBottom - ch * 0.88f, cx - cw * 0.1f, capBottom - ch * 0.62f, fill)
                    stroke.color = Color.argb(60, 255, 255, 255); stroke.strokeWidth = s * 0.025f
                    c.drawArc(cx - cw / 2 + s * 0.02f, capBottom - ch * 0.98f, cx + cw / 2 - s * 0.02f, capBottom + ch * 0.05f, -60f, 50f, false, stroke)
                }
            }
        }

        if (sp.deco != 0 && !silhouette) drawCapDeco(c, sp.deco, sp.shape, cx, capTop, capBottom, baseY, s, capC)

        if (shiny && !silhouette) {
            // rainbow sheen on the cap and orbiting sparkles
            stroke.strokeWidth = s * 0.035f
            stroke.color = Color.HSVToColor(200, floatArrayOf((t * 120f) % 360f, 0.6f, 1f))
            c.drawArc(cx - s * 0.5f, capTop - s * 0.02f, cx + s * 0.5f, capTop + s * 0.75f, 200f, 140f, false, stroke)
            for (k in 0 until 3) {
                val a = t * 2f + k * 2.094f
                fill.color = Color.WHITE
                fill.alpha = (180 + 75 * sin(t * 5f + k)).toInt().coerceIn(0, 255)
                drawSparkle(c, cx + cos(a) * s * 0.62f, capTop + s * 0.35f + sin(a) * s * 0.25f, s * 0.03f)
            }
            fill.alpha = 255
        }

        if (sp.crown && !silhouette) {
            val cy = capTop + s * 0.02f
            val cw = s * 0.32f
            path.reset()
            path.moveTo(cx - cw / 2, cy)
            path.lineTo(cx - cw / 2, cy - s * 0.16f); path.lineTo(cx - cw / 4, cy - s * 0.07f)
            path.lineTo(cx, cy - s * 0.2f); path.lineTo(cx + cw / 4, cy - s * 0.07f)
            path.lineTo(cx + cw / 2, cy - s * 0.16f); path.lineTo(cx + cw / 2, cy); path.close()
            fill.shader = LinearGradient(cx - cw / 2, 0f, cx + cw / 2, 0f, Color.rgb(255, 240, 150), Color.rgb(220, 160, 30), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            fill.color = ACCENT
            c.drawCircle(cx, cy - s * 0.06f, s * 0.03f, fill)
        }
        if (sp.tier == 3 && !silhouette) {
            fill.color = Color.WHITE
            val tw = (t * 2f) % 1f
            fill.alpha = (255 * (1f - tw)).toInt()
            drawSparkle(c, cx + s * 0.45f, capTop + s * 0.1f, s * 0.04f * (1f + tw))
            fill.alpha = 255
        }

        // face, looking a little toward where it's walking
        if (!silhouette) {
            val lookDir = if (ia == 4) (if ((iaT % 0.8f) < 0.4f) -1f else 1f) else facing
            val look = lookDir * s * 0.035f
            val faceY = if (sp.shape == 1) baseY - s * 0.36f else baseY - stemH * 0.5f
            val fx = cx + look
            val ex = s * 0.11f
            val er = s * 0.048f
            fill.color = Color.rgb(50, 30, 60)
            stroke.color = Color.rgb(50, 30, 60); stroke.strokeWidth = max(1.5f * dp, s * 0.025f)
            val happyEyes = ex0 == E_BLISS || ex0 == E_GIGGLE || ia == 1
            if (sleeping) {
                c.drawArc(fx - ex - er, faceY - er, fx - ex + er, faceY + er, 20f, 140f, false, stroke)
                c.drawArc(fx + ex - er, faceY - er, fx + ex + er, faceY + er, 20f, 140f, false, stroke)
            } else if (ex0 == E_DIZZY) {
                for (side in intArrayOf(-1, 1)) {
                    val ox = fx + side * ex
                    c.drawArc(ox - er * 1.3f, faceY - er * 1.3f, ox + er * 1.3f, faceY + er * 1.3f, t * 600f, 270f, false, stroke)
                    c.drawArc(ox - er * 0.6f, faceY - er * 0.6f, ox + er * 0.6f, faceY + er * 0.6f, t * 600f + 180f, 270f, false, stroke)
                }
            } else if (ex0 == E_HEART) {
                fill.color = Color.rgb(255, 80, 120)
                val pulse = 1f + 0.15f * sin(t * 10f)
                drawHeart(c, fx - ex, faceY - er * 0.4f, er * 1.15f * pulse)
                drawHeart(c, fx + ex, faceY - er * 0.4f, er * 1.15f * pulse)
            } else if (ex0 == E_SPARKLE) {
                fill.color = Color.rgb(255, 230, 120)
                drawSparkle(c, fx - ex, faceY, er * 0.9f); drawSparkle(c, fx + ex, faceY, er * 0.9f)
            } else if (happyEyes) {
                c.drawArc(fx - ex - er, faceY - er * 0.4f, fx - ex + er, faceY + er * 1.4f, 200f, 140f, false, stroke)
                c.drawArc(fx + ex - er, faceY - er * 0.4f, fx + ex + er, faceY + er * 1.4f, 200f, 140f, false, stroke)
            } else if (blink) {
                c.drawLine(fx - ex - er, faceY, fx - ex + er, faceY, stroke)
                c.drawLine(fx + ex - er, faceY, fx + ex + er, faceY, stroke)
            } else {
                c.drawOval(fx - ex - er, faceY - er * 1.2f, fx - ex + er, faceY + er * 1.2f, fill)
                c.drawOval(fx + ex - er, faceY - er * 1.2f, fx + ex + er, faceY + er * 1.2f, fill)
                fill.color = Color.WHITE
                c.drawCircle(fx - ex + er * 0.35f, faceY - er * 0.45f, er * 0.38f, fill)
                c.drawCircle(fx + ex + er * 0.35f, faceY - er * 0.45f, er * 0.38f, fill)
            }
            val blushy = ex0 == E_BLISS || ex0 == E_HEART || ex0 == E_GIGGLE
            fill.color = Color.argb(if (blushy) 210 else 140, 255, 130, 160)
            val bl = if (blushy) 1.35f else 1f
            c.drawOval(fx - ex * 1.75f - s * 0.05f * bl, faceY + s * 0.035f, fx - ex * 1.75f + s * 0.05f * bl, faceY + s * 0.08f * bl, fill)
            c.drawOval(fx + ex * 1.75f - s * 0.05f * bl, faceY + s * 0.035f, fx + ex * 1.75f + s * 0.05f * bl, faceY + s * 0.08f * bl, fill)
            val my = faceY + s * 0.065f
            val mw = s * 0.06f
            val dark2 = Color.rgb(50, 30, 60)
            if (sleeping) {
                fill.color = dark2; c.drawCircle(fx, my + mw * 0.4f, mw * 0.3f, fill)
                // a little drool bubble
                fill.color = Color.argb(170, 160, 210, 255)
                c.drawCircle(fx + mw * 0.9f, my + mw * 0.9f, mw * (0.25f + 0.2f * (0.5f + 0.5f * sin(t * 2f))), fill)
            } else if (ex0 == E_CHOMP || ia == 1) {
                fill.color = dark2
                val open = if (ex0 == E_CHOMP) 1f + 0.3f * sin(t * 18f) else 0.8f + 0.4f * sin((1.4f - iaT) / 1.4f * 3.14f)
                c.drawOval(fx - mw * 0.9f, my - mw * 0.2f, fx + mw * 0.9f, my + mw * 1.6f * open, fill)
                fill.color = Color.rgb(255, 120, 150)
                c.drawOval(fx - mw * 0.5f, my + mw * 0.7f * open, fx + mw * 0.5f, my + mw * 1.5f * open, fill)
            } else if (ex0 == E_GIGGLE) {
                fill.color = dark2
                c.drawArc(fx - mw, my - mw * 0.7f, fx + mw, my + mw * 1.2f, 0f, 180f, true, fill)
                fill.color = Color.rgb(255, 120, 150)
                c.drawCircle(fx, my + mw * 0.75f, mw * 0.35f, fill)
            } else if (ex0 == E_BLISS || ex0 == E_HEART) {
                c.drawArc(fx - mw, my - mw * 0.8f, fx + mw, my + mw * 0.8f, 20f, 140f, false, stroke)
            } else if (ex0 == E_DIZZY) {
                path.reset(); path.moveTo(fx - mw, my + mw * 0.3f)
                for (k in 1..4) path.lineTo(fx - mw + k * mw * 0.5f, my + mw * (if (k % 2 == 0) 0.3f else -0.1f))
                c.drawPath(path, stroke)
            }
            else when (mood) {
                0 -> c.drawArc(fx - mw, my - mw * 0.8f, fx + mw, my + mw * 0.8f, 20f, 140f, false, stroke)
                2 -> { fill.color = Color.rgb(50, 30, 60); c.drawOval(fx - mw * 0.4f, my, fx + mw * 0.4f, my + mw * 0.9f, fill) }
                3 -> c.drawArc(fx - mw, my + mw * 0.2f, fx + mw, my + mw * 1.6f, 200f, 140f, false, stroke)
                else -> c.drawLine(fx - mw * 0.6f, my + mw * 0.3f, fx + mw * 0.6f, my + mw * 0.3f, stroke)
            }

            // sweat drop when hungry
            if (mood == 2 && !sleeping && !held) {
                val dy = (t * 0.8f) % 1f
                fill.color = Color.argb((220 * (1f - dy)).toInt(), 150, 210, 255)
                val sx = cx + s * 0.36f; val sy = capTop + s * 0.3f + dy * s * 0.2f
                path.reset(); path.moveTo(sx, sy - s * 0.07f)
                path.quadTo(sx + s * 0.05f, sy + s * 0.01f, sx, sy + s * 0.04f)
                path.quadTo(sx - s * 0.05f, sy + s * 0.01f, sx, sy - s * 0.07f); path.close()
                c.drawPath(path, fill)
            }
            // a little arm waving hello
            if (who != null && who.waveT > 0f && !sleeping) {
                c.save()
                c.rotate(-50f + 35f * sin(t * 16f), cx + stemW * 0.45f, faceY + s * 0.08f)
                fill.color = shade(stemC, -0.08f)
                c.drawRoundRect(cx + stemW * 0.42f, faceY + s * 0.04f, cx + stemW * 0.42f + s * 0.2f, faceY + s * 0.12f, s * 0.04f, s * 0.04f, fill)
                c.restore()
            }

            if (who != null && who.stage() >= 3) {
                // Elders: a bit of moss on the cap, plus a beard or little spectacles
                fill.color = Color.rgb(110, 180, 100)
                c.drawOval(cx - s * 0.3f, capTop + s * 0.12f, cx - s * 0.12f, capTop + s * 0.2f, fill)
                c.drawOval(cx + s * 0.15f, capTop + s * 0.22f, cx + s * 0.3f, capTop + s * 0.29f, fill)
                if (who.id % 2L == 1L) {
                    fill.color = Color.rgb(245, 245, 250)
                    for (k in -2..2) c.drawCircle(fx + k * s * 0.045f, my + s * 0.06f + (2 - abs(k)) * s * 0.025f, s * 0.045f, fill)
                } else if (who.wear[1] < 0) {
                    stroke.color = Color.rgb(120, 90, 50); stroke.strokeWidth = max(1f * dp, s * 0.015f)
                    c.drawCircle(fx - ex, faceY, er * 1.7f, stroke); c.drawCircle(fx + ex, faceY, er * 1.7f, stroke)
                    c.drawLine(fx - ex + er * 1.7f, faceY, fx + ex - er * 1.7f, faceY, stroke)
                }
            }
            if (who != null) {
                for (slot in intArrayOf(2, 1, 0)) {
                    val a = who.wear[slot]
                    if (a >= 0) drawAccessory(c, a, if (slot == 1) fx else cx, capTop, faceY, capBottom, s, ex)
                }
            }
        }
        c.restore()
    }

    // ---------- Journal: daily gift, Professor Spore, contest, awards ----------

    private val professor by lazy {
        Shroom(1001L, 36, xp = 900f, name = "Prof. Spore").apply { wear[1] = 9 }
    }

    private fun journalAlert() = lab.giftReady() || lab.requests.any { lab.requestProgress(it).let { p -> p.first >= p.second } } ||
        System.currentTimeMillis() >= lab.nextContest && lab.home().isNotEmpty()

    private fun journalButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 16f * dp
        val tabs = arrayOf("TODAY", "CONTEST", "AWARDS", "DIARY", "GAMES", "NOTES")
        val tw = (w - pad * 2) / tabs.size
        val ty = topBar + 6f * dp
        for ((k, nm) in tabs.withIndex()) {
            list.add(Button("j$k", RectF(pad + k * tw + 2f * dp, ty, pad + (k + 1) * tw - 2f * dp, ty + 40f * dp), nm,
                if (journalTab == k) STYLE_TAB_ON else STYLE_NORMAL) { journalTab = k })
        }
        val top = ty + 54f * dp
        if (journalTab == 2) {
            if (stickerView) {
                list.add(Button("st_prev", RectF(pad, navTop - 54f * dp, pad + 60f * dp, navTop - 12f * dp), "◀", STYLE_NORMAL, stickerPage > 0) { stickerPage-- })
                list.add(Button("st_next", RectF(w - pad - 60f * dp, navTop - 54f * dp, w - pad, navTop - 12f * dp), "▶", STYLE_NORMAL, stickerPage < stickerPages() - 1) { stickerPage++ })
                list.add(Button("st_back", RectF(pad + 70f * dp, navTop - 54f * dp, w - pad - 70f * dp, navTop - 12f * dp), "🏆 AWARDS") { stickerView = false })
            } else {
                list.add(Button("st_open", RectF(pad, navTop - 54f * dp, w - pad, navTop - 12f * dp),
                    "⭐ STICKER BOOK  ${lab.stickers.count { it }}/${Data.STICKERS.size}", STYLE_PRIMARY) { stickerView = true })
            }
        }
        if (journalTab == 5) {
            val (gx, gy, cell) = notesGrid(w)
            list.add(Button("notes_grid", RectF(gx, gy, gx + cell * 12, gy + cell * 12), "", STYLE_GHOST, custom = { _, _ -> }) {
                val col = ((touchX - gx) / cell).toInt(); val row = ((touchY - gy) / cell).toInt()
                if (col in 0..11 && row in 0..11) { notesPick = row * 12 + col; sound.play(Fx.CLICK, 0.5f) }
            })
        }
        if (journalTab == 4) {
            for (g in 0..2) {
                val r = RectF(pad, top + 10f * dp + g * 158f * dp, w - pad, top + 10f * dp + g * 158f * dp + 148f * dp)
                list.add(Button("play$g", RectF(r.right - 132f * dp, r.bottom - 54f * dp, r.right - 16f * dp, r.bottom - 14f * dp), "PLAY", STYLE_PRIMARY) {
                    startMini(g + 1)
                })
            }
        }
        when (journalTab) {
            0 -> {
                val ready = lab.giftReady()
                list.add(Button("gift", RectF(pad + 10f * dp, top + 78f * dp, w - pad - 10f * dp, top + 116f * dp),
                    if (ready) "CLAIM: ${lab.giftText(lab.giftIndex())}" else "Come back tomorrow!", STYLE_PRIMARY, ready) {
                    if (!lab.giftReady()) return@Button
                    val got = lab.claimGift()
                    sound.play(Fx.DISCOVER); buzz(40)
                    burst(w / 2, top + 60f * dp, DUST, 40, P_SPARK)
                    toast("Daily gift: $got", DUST)
                    markDirty()
                })
                val ry = top + 176f * dp
                for ((i, r) in lab.requests.withIndex()) {
                    val (cur, goal) = lab.requestProgress(r)
                    if (cur >= goal) {
                        val y = ry + i * 74f * dp
                        list.add(Button("req$i", RectF(w - pad - 96f * dp, y + 14f * dp, w - pad - 10f * dp, y + 50f * dp), "CLAIM", STYLE_PRIMARY) {
                            val got = lab.claimRequest(i)
                            sound.play(Fx.COIN); buzz(30)
                            toast("Professor Spore: \"Splendid!\"  +$got ✦", DUST)
                            markDirty()
                        })
                    }
                }
                val notify = prefs.getBoolean("notify", true)
                list.add(Button("notify", RectF(pad, navTop - 52f * dp, w - pad, navTop - 12f * dp),
                    "🔔 Reminders: ${if (notify) "ON" else "OFF"}") {
                    prefs.edit().putBoolean("notify", !notify).apply()
                    toast(if (!notify) "I'll remind you when mushrooms get hungry or come home" else "Reminders off")
                })
            }
            1 -> {
                val now = System.currentTimeMillis()
                if (now >= lab.nextContest) {
                    val cands = lab.home().filter { it.stage() >= 1 }
                    val cols = 4
                    val cw = (w - pad * 2) / cols
                    val gy = top + 96f * dp
                    val ch = min(110f * dp, (navTop - 70f * dp - gy) / 3)
                    for ((i, s) in cands.take(12).withIndex()) {
                        val r = RectF(pad + (i % cols) * cw + 3f * dp, gy + (i / cols) * ch + 3f * dp,
                            pad + (i % cols + 1) * cw - 3f * dp, gy + (i / cols + 1) * ch - 3f * dp)
                        list.add(Button("cand${s.id}", r, "", if (contestPick == s.id) STYLE_PICKED else STYLE_CARD, custom = { c, rr ->
                            drawShroom(c, s.species, rr.centerX(), rr.top + rr.height() * 0.66f, rr.width() * 0.42f, s.mood(), 0f, false, false, shiny = s.shiny, who = s)
                            text.textSize = 10f * dp
                            c.drawText(s.name, rr.centerX(), rr.top + rr.height() * 0.82f, text)
                            text.color = DUST
                            c.drawText("${lab.contestScore(s)} pts", rr.centerX(), rr.bottom - 5f * dp, text)
                            text.color = Color.WHITE
                        }) { contestPick = s.id })
                    }
                    val pick = shroom(contestPick)
                    list.add(Button("enter", RectF(pad, navTop - 58f * dp, w - pad, navTop - 12f * dp),
                        if (pick != null) "ENTER ${pick.name.uppercase()}" else "PICK A MUSHROOM", STYLE_PRIMARY, pick != null && !pick.away()) {
                        val s = shroom(contestPick) ?: return@Button
                        val place = lab.runContest(s)
                        contestPick = -1
                        sound.play(if (place == 1) Fx.DISCOVER else Fx.GROW); buzz(60)
                        burst(w / 2, h * 0.3f, if (place == 1) DUST else MINT, 50, P_SPARK)
                        toast(arrayOf("🥇 ${s.name} won the contest!", "🥈 ${s.name} came 2nd!", "🥉 ${s.name} came 3rd!", "${s.name} came 4th. Next time!")[place - 1], DUST)
                        markDirty()
                    })
                }
            }
        }
    }

    private fun drawJournal(c: Canvas, w: Float, h: Float) {
        val pad = 16f * dp
        val top = topBar + 60f * dp
        if (journalTab == 3) { drawDiary(c, w, top); return }
        if (journalTab == 4) { drawGamesTab(c, w, top + 10f * dp); return }
        if (journalTab == 5) { drawNotes(c, w, top); return }
        when (journalTab) {
            0 -> {
                // daily gift
                val card = RectF(pad, top, w - pad, top + 124f * dp)
                fill.color = PANEL; c.drawRoundRect(card, 18f * dp, 18f * dp, fill)
                text.textAlign = Paint.Align.LEFT; text.textSize = 15f * dp
                c.drawText("Daily gift", card.left + 14f * dp, card.top + 24f * dp, text)
                text.textAlign = Paint.Align.RIGHT; text.textSize = 12f * dp; text.color = DUST
                c.drawText("${lab.streak}-day streak 🔥", card.right - 14f * dp, card.top + 24f * dp, text)
                text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                val bw = (card.width() - 28f * dp) / 7
                for (d in 0 until 7) {
                    val bx = card.left + 14f * dp + d * bw
                    val r = RectF(bx + 2f * dp, card.top + 34f * dp, bx + bw - 2f * dp, card.top + 68f * dp)
                    val today = d == lab.giftIndex()
                    val done = d < lab.giftIndex() || (today && !lab.giftReady())
                    fill.color = when { today && lab.giftReady() -> ACCENT; done -> Color.rgb(60, 110, 90); else -> PANEL_LIGHT }
                    c.drawRoundRect(r, 8f * dp, 8f * dp, fill)
                    text.textSize = 11f * dp
                    c.drawText(if (done) "✓" else "${d + 1}", r.centerX(), r.centerY() + 4f * dp, text)
                }
                // Professor Spore
                val py = top + 140f * dp
                drawShroom(c, professor.species, pad + 22f * dp, py + 22f * dp, 34f * dp, 0, sin(t * 2f), (t % 4f) < 0.12f, false, who = professor)
                text.textAlign = Paint.Align.LEFT; text.textSize = 15f * dp
                c.drawText("Professor Spore's requests", pad + 50f * dp, py + 10f * dp, text)
                text.textSize = 11f * dp; text.color = TEXT_DIM
                c.drawText("\"Help my research and I'll pay you in spore dust!\"", pad + 50f * dp, py + 26f * dp, text)
                text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                val ry = top + 170f * dp
                for ((i, r) in lab.requests.withIndex()) {
                    val y = ry + i * 74f * dp
                    val rc = RectF(pad, y, w - pad, y + 66f * dp)
                    fill.color = PANEL; c.drawRoundRect(rc, 16f * dp, 16f * dp, fill)
                    val (cur, goal) = lab.requestProgress(r)
                    text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
                    c.drawText(lab.requestText(r), rc.left + 14f * dp, rc.top + 24f * dp, text)
                    text.textSize = 11f * dp; text.color = DUST
                    c.drawText("Reward: ${r.reward} ✦", rc.left + 14f * dp, rc.top + 56f * dp, text)
                    text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                    if (cur < goal) {
                        val bx = rc.left + 14f * dp; val bw2 = rc.width() - 120f * dp
                        fill.color = Color.argb(50, 255, 255, 255)
                        c.drawRoundRect(bx, rc.top + 34f * dp, bx + bw2, rc.top + 42f * dp, 4f * dp, 4f * dp, fill)
                        fill.color = MINT
                        c.drawRoundRect(bx, rc.top + 34f * dp, bx + bw2 * (cur.toFloat() / goal).coerceIn(0.03f, 1f), rc.top + 42f * dp, 4f * dp, 4f * dp, fill)
                        text.textSize = 12f * dp; text.color = TEXT_DIM
                        c.drawText("$cur / $goal", rc.right - 50f * dp, rc.top + 42f * dp, text)
                        text.color = Color.WHITE
                    }
                }
                drawQuestAndMystery(c, w, ry + lab.requests.size * 74f * dp + 4f * dp)
            }
            1 -> {
                val now = System.currentTimeMillis()
                text.textSize = 18f * dp
                c.drawText("🏅 Beauty Contest", w / 2, top + 22f * dp, text)
                text.textSize = 11f * dp; text.color = TEXT_DIM
                c.drawText("Judged on happiness, growth, rarity, shine, outfits and your garden's decorations", w / 2, top + 42f * dp, text)
                text.color = Color.WHITE
                if (now < lab.nextContest) {
                    text.textSize = 13f * dp; text.color = MINT
                    c.drawText("Next contest in ${timeLeft(lab.nextContest - now)}", w / 2, top + 66f * dp, text)
                    text.color = Color.WHITE
                    val res = lab.lastContest
                    for ((i, e) in res.withIndex()) {
                        val y = top + 90f * dp + i * 92f * dp
                        val rc = RectF(pad, y, w - pad, y + 84f * dp)
                        fill.color = if (e.mine) PANEL_LIGHT else PANEL
                        c.drawRoundRect(rc, 16f * dp, 16f * dp, fill)
                        val mine = if (e.mine) lab.garden.firstOrNull { it.name == e.name } else null
                        drawShroom(c, mine?.species ?: e.species, rc.left + 70f * dp, rc.bottom - 12f * dp, 52f * dp, 0, sin(t * 2f + i), false, false,
                            shiny = mine?.shiny ?: false, who = mine)
                        text.textAlign = Paint.Align.LEFT; text.textSize = 22f * dp
                        c.drawText(arrayOf("🥇", "🥈", "🥉", "4th")[i], rc.left + 12f * dp, rc.top + 32f * dp, text)
                        text.textSize = 15f * dp
                        c.drawText(e.name + if (e.mine) " (you)" else "", rc.left + 120f * dp, rc.top + 36f * dp, text)
                        text.textSize = 13f * dp; text.color = DUST
                        c.drawText("${e.score} points", rc.left + 120f * dp, rc.top + 58f * dp, text)
                        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                    }
                    if (res.isEmpty()) {
                        text.textSize = 14f * dp; text.color = TEXT_DIM
                        c.drawText("Come back soon!", w / 2, h * 0.45f, text); text.color = Color.WHITE
                    }
                } else {
                    text.textSize = 13f * dp; text.color = MINT
                    c.drawText("Pick your contestant (prizes: 160 / 80 / 40 / 15 ✦)", w / 2, top + 66f * dp, text)
                    text.color = Color.WHITE
                    if (lab.home().none { it.stage() >= 1 }) {
                        text.textSize = 14f * dp; text.color = TEXT_DIM
                        c.drawText("Raise a mushroom to Young to enter", w / 2, h * 0.45f, text); text.color = Color.WHITE
                    }
                }
            }
            else -> {
                if (stickerView) { drawStickers(c, w, top); return }
                text.textSize = 14f * dp; text.color = TEXT_DIM
                c.drawText("${lab.achieved.count { it }} of ${Data.ACHIEVEMENTS.size} earned", w / 2, top + 6f * dp, text)
                text.color = Color.WHITE
                val rowH = min(42f * dp, (navTop - top - 76f * dp) / Data.ACHIEVEMENTS.size)
                for ((i, a) in Data.ACHIEVEMENTS.withIndex()) {
                    val y = top + 16f * dp + i * rowH
                    val got = lab.achieved[i]
                    fill.color = if (got) DUST else Color.rgb(60, 48, 100)
                    c.drawCircle(pad + 12f * dp, y + rowH / 2, 9f * dp, fill)
                    if (got) { text.textSize = 11f * dp; text.color = BG; c.drawText("✓", pad + 12f * dp, y + rowH / 2 + 4f * dp, text) }
                    text.textAlign = Paint.Align.LEFT
                    text.textSize = 13f * dp; text.color = if (got) Color.WHITE else Color.argb(200, 255, 255, 255)
                    c.drawText(a.name, pad + 30f * dp, y + rowH / 2 - 1f * dp, text)
                    text.textSize = 10.5f * dp; text.color = TEXT_DIM
                    c.drawText(a.desc, pad + 30f * dp, y + rowH / 2 + 12f * dp, text)
                    text.textAlign = Paint.Align.RIGHT; text.textSize = 12f * dp
                    text.color = if (got) MINT else DUST
                    c.drawText(if (got) "done" else "+${a.reward} ✦", w - pad, y + rowH / 2 + 4f * dp, text)
                    text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                }
            }
        }
    }

    // ---------- Reminders ----------

    private fun scheduleReminders() {
        Notifier.cancelAll(context)
        if (!prefs.getBoolean("notify", true)) return
        val now = System.currentTimeMillis()
        // 1: the first mushroom to get hungry
        lab.home().filter { it.hunger > 25f }.minByOrNull { (it.hunger - 25f) / (if (it.trait == 3) 1.3f else 1f) }?.let { s ->
            val hrs = (s.hunger - 25f) / (9f * (if (s.trait == 3) 1.3f else 1f))
            val at = now + (hrs * 3600_000f).toLong()
            if (at - now > 10 * 60_000L && lab.upgrades[Data.UP_FEEDER] == 0)
                Notifier.schedule(context, 1, at, "${s.name} is getting hungry!", "Your ${Data.SPECIES[s.species].name} would love a snack. 🍄")
        }
        // 2: the next expedition to come home
        lab.garden.filter { it.away() }.minByOrNull { it.awayUntil }?.let { s ->
            Notifier.schedule(context, 2, s.awayUntil, "${s.name} is back!",
                "${s.name} returned from ${Data.ZONES[s.awayZone].name} with loot. Come see what it found!")
        }
        // 3: tomorrow's daily gift
        val tomorrow = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 10); set(java.util.Calendar.MINUTE, 0)
        }
        Notifier.schedule(context, 3, tomorrow.timeInMillis, "Your daily gift is ready 🎁", "Keep your streak going in Shroomology!")
    }

    // ---------- Photo mode ----------

    private fun takePhoto() {
        if (Build.VERSION.SDK_INT < 29) { toast("Photo mode needs Android 10 or newer"); return }
        val bmp = android.graphics.Bitmap.createBitmap(width, navTop.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        capturing = true
        render(Canvas(bmp))
        capturing = false
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Shroomology_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Shroomology")
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw Exception()
            resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            flashT = 0.35f
            sound.play(Fx.PET)
            toast("Saved to Pictures/Shroomology", MINT)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(share, "Share your garden"))
        } catch (_: Exception) {
            toast("Couldn't save the photo", ACCENT)
        }
    }

    private fun drawPhotoFrame(c: Canvas, w: Float) {
        val hgt = navTop
        stroke.color = Color.argb(230, 255, 255, 255); stroke.strokeWidth = 6f * dp
        c.drawRoundRect(10f * dp, 10f * dp, w - 10f * dp, hgt - 10f * dp, 24f * dp, 24f * dp, stroke)
        fill.color = Color.argb(150, 20, 14, 40)
        c.drawRoundRect(22f * dp, hgt - 64f * dp, w * 0.62f, hgt - 22f * dp, 14f * dp, 14f * dp, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 17f * dp; text.color = Color.WHITE
        c.drawText("🍄 Shroomology", 34f * dp, hgt - 42f * dp, text)
        text.textSize = 11f * dp; text.color = TEXT_DIM
        val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        c.drawText("${lab.discoveredCount()} species discovered · $date", 34f * dp, hgt - 28f * dp, text)
        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
    }

    // ---------- Critters: butterflies, ladybugs, snails and a frog ----------

    private class Critter(val type: Int, var gx: Float, var gz: Float, var h: Float, var vx: Float, var vz: Float,
                          var life: Float, val seed: Float, val color: Int) {
        var state = 0          // 0 normal, 1 leaving / hiding
        var timer = 0f
        var rewarded = false
        var hop = 0f
    }

    private val critters = ArrayList<Critter>()
    private var critterTimer = 4f

    private class Bloom(val gx: Float, val gz: Float, val born: Float, val color: Int)
    private val blooms = ArrayList<Bloom>()
    private val ballVel = HashMap<Placed, FloatArray>()

    private fun updateCritters(dt: Float) {
        val dark = LabState.darkness()
        val rain = LabState.raining()
        critterTimer -= dt
        if (critterTimer <= 0f) {
            critterTimer = 12f + Random.nextFloat() * 18f
            val left = Random.nextBoolean()
            val r = Random.nextFloat()
            val pond = lab.placed.firstOrNull { it.kind == 4 }
            when {
                pond != null && critters.none { it.type == 3 } && r < 0.3f ->
                    critters.add(Critter(3, (pond.gx + 0.13f).coerceAtMost(LabState.WORLD - 0.05f), pond.gz + 0.02f, 0f, 0f, 0f, 9999f, Random.nextFloat() * 6f, Color.rgb(110, 200, 90)))
                !rain && dark < 0.6f && critters.count { it.type == 0 } < 2 && r < 0.75f ->
                    critters.add(Critter(0, if (left) camX - 0.55f else camX + 0.55f, 0.1f + Random.nextFloat() * 0.8f, 60f * dp,
                        if (left) 0.06f else -0.06f, 0f, 40f + Random.nextFloat() * 20f, Random.nextFloat() * 6f,
                        Color.HSVToColor(floatArrayOf(listOf(200f, 280f, 330f, 45f, 180f).random(), 0.55f, 1f))))
                critters.none { it.type == 1 } && r < 0.88f ->
                    critters.add(Critter(1, if (left) camX - 0.53f else camX + 0.53f, 0.1f + Random.nextFloat() * 0.85f, 0f,
                        if (left) 0.025f else -0.025f, 0f, 9999f, Random.nextFloat() * 6f, Color.rgb(230, 50, 50)))
                critters.none { it.type == 2 } ->
                    critters.add(Critter(2, if (left) camX - 0.53f else camX + 0.53f, 0.1f + Random.nextFloat() * 0.85f, 0f,
                        if (left) 0.009f else -0.009f, 0f, 9999f, Random.nextFloat() * 6f, Color.rgb(200, 140, 90)))
            }
        }
        val it = critters.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.life -= dt
            when (c.type) {
                0 -> { // butterfly: flutter about, then flutter away
                    c.vx += (Random.nextFloat() - 0.5f) * 0.1f * dt
                    c.vz += (Random.nextFloat() - 0.5f) * 0.1f * dt
                    c.vx = c.vx.coerceIn(-0.08f, 0.08f); c.vz = c.vz.coerceIn(-0.05f, 0.05f)
                    if (c.gz < 0.05f || c.gz > 0.95f) c.vz = -c.vz
                    c.gx += c.vx * dt * (if (c.state == 1) 3f else 1f); c.gz += c.vz * dt
                    c.h = if (c.state == 1 || c.life < 0f) c.h + 160f * dp * dt else 55f * dp + 25f * dp * sin(t * 1.4f + c.seed)
                    if (c.life > 0f && c.state == 0 && (c.gx < 0.03f || c.gx > LabState.WORLD - 0.03f) && c.life < 35f) c.vx = -c.vx
                    if (c.h > 500f * dp || c.gx < camX - 0.8f || c.gx > camX + 0.8f) it.remove()
                }
                1, 2 -> { // ladybug & snail: crawl across the garden
                    if (c.state == 1) {
                        if (c.type == 1) { c.h += 140f * dp * dt; c.gx += c.vx * 3f * dt; if (c.h > 400f * dp) it.remove() }
                        else { c.timer -= dt; if (c.timer <= 0f) c.state = 0 }
                    } else {
                        c.gx += c.vx * dt
                        c.gz += sin(t * 0.7f + c.seed) * 0.004f * dt
                        if (c.gx < camX - 0.7f || c.gx > camX + 0.7f) it.remove()
                    }
                }
                else -> { // frog: sits by the pond and hops
                    if (lab.placed.none { it.kind == 4 }) { it.remove(); continue }
                    if (c.state == 1) { c.timer -= dt; if (c.timer <= 0f) c.state = 0 }
                    else {
                        c.timer -= dt
                        if (c.hop > 0f) c.hop = max(0f, c.hop - dt)
                        if (c.timer <= 0f) { c.timer = 3f + Random.nextFloat() * 4f; c.hop = 0.5f
                            if (Random.nextFloat() < 0.3f) sound.play(Fx.RIBBIT, 0.4f, 0.9f + Random.nextFloat() * 0.2f) }
                        c.h = sin((0.5f - c.hop) / 0.5f * 3.1416f).coerceAtLeast(0f) * 16f * dp * (if (c.hop > 0f) 1f else 0f)
                    }
                }
            }
        }
    }

    private fun critterAt(x: Float, y: Float): Critter? = critters.firstOrNull { c ->
        if (c.type == 3 && c.state == 1) return@firstOrNull false
        val (sx, sy) = toScreen(c.gx, c.gz)
        hypot(x - sx, y - (sy - c.h - 6f * dp)) < 30f * dp
    }

    private fun tapCritter(c: Critter) {
        val (sx, sy) = toScreen(c.gx, c.gz)
        when (c.type) {
            0 -> { c.state = 1; sound.play(Fx.CHIRP, 0.5f, 1.8f) }
            1 -> { c.state = 1; sound.play(Fx.CHIRP, 0.5f, 1.6f) }
            2 -> { c.state = 1; c.timer = 5f; sound.play(Fx.POP, 0.5f, 0.6f) }
            else -> {
                c.state = 1; c.timer = 20f
                sound.play(Fx.RIBBIT, 0.8f, 1f)
                burst(sx, sy, Color.rgb(150, 210, 255), 12, P_SPARK)
            }
        }
        if (!c.rewarded) {
            c.rewarded = true
            val r = when (c.type) { 0 -> 3; 3 -> 4; else -> 2 }
            lab.dust += r
            flyDust(sx, sy - c.h, r)
            toast(arrayOf("🦋 A butterfly!", "🐞 A ladybug!", "🐌 A snail!", "🐸 Ribbit!")[c.type] + "  +$r ✦")
            markDirty()
        }
    }

    private fun drawCritter(cv: Canvas, c: Critter) {
        val (x, y0) = toScreen(c.gx, c.gz)
        val d = depth(c.gz)
        val y = y0 - c.h
        when (c.type) {
            0 -> {
                if (c.h < 400f * dp) groundShadow(cv, x, y0, 7f * dp * d, 50)
                val flap = abs(sin(t * 16f + c.seed))
                fill.color = c.color
                val wsz = 9f * dp * d
                cv.drawOval(x - wsz * (0.3f + flap), y - wsz, x, y + wsz * 0.1f, fill)
                cv.drawOval(x, y - wsz, x + wsz * (0.3f + flap), y + wsz * 0.1f, fill)
                fill.color = shade(c.color, -0.2f)
                cv.drawOval(x - wsz * (0.2f + flap * 0.7f), y, x, y + wsz * 0.7f, fill)
                cv.drawOval(x, y, x + wsz * (0.2f + flap * 0.7f), y + wsz * 0.7f, fill)
                fill.color = Color.rgb(60, 40, 50)
                cv.drawRoundRect(x - 1.5f * dp, y - wsz * 0.8f, x + 1.5f * dp, y + wsz * 0.6f, 1.5f * dp, 1.5f * dp, fill)
            }
            1 -> {
                val r = 6f * dp * d
                if (c.state == 1) {   // flying away with wings open
                    fill.color = Color.argb(150, 255, 255, 255)
                    val flap = abs(sin(t * 30f)) * r
                    cv.drawOval(x - r - flap, y - r * 1.4f, x, y - r * 0.4f, fill); cv.drawOval(x, y - r * 1.4f, x + r + flap, y - r * 0.4f, fill)
                }
                fill.color = Color.rgb(40, 30, 40); cv.drawCircle(x + c.vx.sign() * r * 0.8f, y - r * 0.5f, r * 0.5f, fill)
                fill.color = c.color; cv.drawArc(x - r, y - r * 1.2f, x + r, y + r * 0.8f, 180f, 180f, true, fill)
                fill.color = Color.rgb(30, 20, 30)
                cv.drawCircle(x - r * 0.4f, y - r * 0.55f, r * 0.18f, fill); cv.drawCircle(x + r * 0.35f, y - r * 0.6f, r * 0.18f, fill)
                cv.drawCircle(x, y - r * 0.85f, r * 0.15f, fill)
            }
            2 -> {
                val r = 8f * dp * d
                val dir = c.vx.sign()
                if (c.state == 0) {
                    fill.color = Color.rgb(170, 200, 140)
                    cv.drawRoundRect(x - r * 1.2f, y - r * 0.45f, x + r * 1.2f, y, r * 0.3f, r * 0.3f, fill)
                    cv.drawCircle(x + dir * r * 1.15f, y - r * 0.6f, r * 0.35f, fill)
                    stroke.color = Color.rgb(170, 200, 140); stroke.strokeWidth = 1.5f * dp
                    cv.drawLine(x + dir * r * 1.2f, y - r * 0.8f, x + dir * r * 1.4f, y - r * 1.3f, stroke)
                    fill.color = Color.rgb(40, 30, 40); cv.drawCircle(x + dir * r * 1.4f, y - r * 1.3f, r * 0.12f, fill)
                }
                fill.shader = RadialGradient(x - r * 0.3f, y - r, r * 1.2f, Color.rgb(230, 180, 120), c.color, Shader.TileMode.CLAMP)
                cv.drawCircle(x - dir * r * 0.15f, y - r * 0.85f, r * 0.8f, fill); fill.shader = null
                stroke.color = shade(c.color, -0.3f); stroke.strokeWidth = 1.5f * dp
                cv.drawCircle(x - dir * r * 0.15f, y - r * 0.85f, r * 0.45f, stroke)
            }
            else -> {
                if (c.state == 1) return
                val r = 11f * dp * d
                groundShadow(cv, x, y0, r, 70)
                fill.color = c.color
                cv.drawOval(x - r, y - r * 1.1f, x + r, y, fill)
                cv.drawCircle(x - r * 0.45f, y - r * 1.1f, r * 0.32f, fill); cv.drawCircle(x + r * 0.45f, y - r * 1.1f, r * 0.32f, fill)
                fill.color = Color.WHITE
                cv.drawCircle(x - r * 0.45f, y - r * 1.15f, r * 0.2f, fill); cv.drawCircle(x + r * 0.45f, y - r * 1.15f, r * 0.2f, fill)
                fill.color = Color.rgb(30, 30, 30)
                cv.drawCircle(x - r * 0.42f, y - r * 1.15f, r * 0.1f, fill); cv.drawCircle(x + r * 0.48f, y - r * 1.15f, r * 0.1f, fill)
                stroke.color = Color.rgb(50, 100, 50); stroke.strokeWidth = 1.5f * dp
                cv.drawArc(x - r * 0.4f, y - r * 0.85f, x + r * 0.4f, y - r * 0.45f, 20f, 140f, false, stroke)
                fill.color = Color.argb(150, 255, 140, 160)
                cv.drawCircle(x - r * 0.65f, y - r * 0.6f, r * 0.14f, fill); cv.drawCircle(x + r * 0.65f, y - r * 0.6f, r * 0.14f, fill)
            }
        }
    }

    private fun Float.sign() = if (this >= 0f) 1f else -1f

    // ---------- Flowers that bloom where happy mushrooms walk ----------

    private fun updateBlooms(dt: Float) {
        blooms.removeAll { t - it.born > 90f }
        if (blooms.size >= 30 || area != 0) return
        for (s in local()) {
            if (s.moving && s.mood() == 0 && Random.nextFloat() < dt * 0.25f) {
                blooms.add(Bloom(s.gx, s.gz, t, listOf(ACCENT, Color.rgb(255, 230, 120), Color.WHITE, Color.rgb(170, 150, 255), Color.rgb(255, 160, 100)).random()))
            }
        }
    }

    private fun drawBlooms(c: Canvas) {
        for (b in blooms) {
            val age = t - b.born
            val k = min(1f, age * 2f) * min(1f, (90f - age) / 5f)
            if (k <= 0f) continue
            val (x, y) = toScreen(b.gx, b.gz)
            val r = 3.5f * dp * depth(b.gz) * k
            stroke.color = Color.rgb(100, 170, 100); stroke.strokeWidth = 1.2f * dp
            c.drawLine(x, y, x, y - r * 2.2f, stroke)
            fill.color = b.color
            for (p in 0 until 5) { val a = p * 1.2566f; c.drawCircle(x + cos(a) * r, y - r * 2.4f + sin(a) * r, r * 0.75f, fill) }
            fill.color = Color.rgb(255, 210, 80); c.drawCircle(x, y - r * 2.4f, r * 0.55f, fill)
        }
    }

    // ---------- Toys ----------

    private fun updateBalls(dt: Float) {
        for (d in lab.placed) {
            if (d.kind != 11) continue
            val v = ballVel.getOrPut(d) { floatArrayOf(0f, 0f) }
            // mushrooms walking into the ball give it a nudge
            for (s in local()) {
                if (!s.moving && s.act != ACT_KICK) continue
                val dx = d.gx - s.gx; val dz = (d.gz - s.gz) * 1.6f
                val dist = hypot(dx, dz)
                if (dist < 0.045f && dist > 0.0001f && hypot(v[0], v[1]) < 0.05f) {
                    v[0] = dx / dist * 0.35f; v[1] = dz / dist * 0.2f
                    sound.play(Fx.BOING, 0.35f, 1.6f)
                    express(s, E_GIGGLE, 1f)
                }
            }
            d.gx += v[0] * dt; d.gz += v[1] * dt
            if (d.gx < 0.05f || d.gx > LabState.WORLD - 0.05f) { v[0] = -v[0] * 0.7f; d.gx = d.gx.coerceIn(0.05f, LabState.WORLD - 0.05f) }
            if (d.gz < 0f || d.gz > 1f) { v[1] = -v[1] * 0.7f; d.gz = d.gz.coerceIn(0f, 1f) }
            v[0] *= (1f - dt * 1.2f); v[1] *= (1f - dt * 1.2f)
            if (hypot(v[0], v[1]) < 0.005f) { v[0] = 0f; v[1] = 0f }
        }
    }

    /** Swings and slides move a mushroom along a path: returns screen x, y and a tilt, or null. */
    private fun actPose(s: Shroom): FloatArray? {
        if (s.act != ACT_SWING && s.act != ACT_SLIDE) return null
        val d = lab.placed.getOrNull(s.actTarget) ?: return null
        val (dx, dy) = toScreen(d.gx, d.gz)
        val sz = 58f * dp * depth(d.gz)
        if (s.act == ACT_SWING) {
            val ang = sin(s.phase * 2.2f) * 0.55f
            val px = dx; val py = dy - sz * 1.15f; val len = sz * 0.85f
            return floatArrayOf(px + sin(ang) * len, py + cos(ang) * len, -Math.toDegrees(ang.toDouble()).toFloat())
        }
        val p = (s.phase % 4f) / 4f
        val ladderX = dx - sz * 0.45f; val topY = dy - sz * 0.85f; val endX = dx + sz * 0.7f
        return when {
            p < 0.4f -> floatArrayOf(ladderX, dy - (p / 0.4f) * sz * 0.85f, 0f)
            p < 0.6f -> { val u = (p - 0.4f) / 0.2f; floatArrayOf(ladderX + sz * 0.1f + (endX - ladderX - sz * 0.1f) * u, topY + (dy - topY) * u, 25f) }
            else -> { val u = (p - 0.6f) / 0.4f; floatArrayOf(endX + (ladderX - endX) * u, dy, 0f) }
        }
    }

    private fun drawToy(c: Canvas, kind: Int, x: Float, y: Float, s: Float, occupant: Shroom?) {
        when (kind) {
            11 -> { // ball
                val r = s * 0.2f
                val spin = ((lab.placed.firstOrNull { it.kind == 11 }?.gx ?: 0f) * 2000f)
                fill.shader = RadialGradient(x - r * 0.3f, y - r * 1.3f, r * 1.4f, Color.rgb(255, 160, 140), Color.rgb(220, 60, 70), Shader.TileMode.CLAMP)
                c.drawCircle(x, y - r, r, fill); fill.shader = null
                c.save(); c.rotate(spin, x, y - r)
                stroke.color = Color.WHITE; stroke.strokeWidth = r * 0.28f
                c.drawArc(x - r * 0.8f, y - r * 1.8f, x + r * 0.8f, y - r * 0.2f, 200f, 140f, false, stroke)
                c.restore()
            }
            12 -> { // swing
                stroke.color = Color.rgb(120, 85, 60); stroke.strokeWidth = s * 0.06f
                c.drawLine(x - s * 0.55f, y, x - s * 0.4f, y - s * 1.2f, stroke); c.drawLine(x + s * 0.55f, y, x + s * 0.4f, y - s * 1.2f, stroke)
                c.drawLine(x - s * 0.45f, y - s * 1.18f, x + s * 0.45f, y - s * 1.18f, stroke)
                val ang = if (occupant != null) sin(occupant.phase * 2.2f) * 0.55f else sin(t * 1.2f) * 0.08f
                val len = s * 0.85f
                val sx = x + sin(ang) * len; val sy = y - s * 1.15f + cos(ang) * len
                stroke.color = Color.rgb(200, 190, 170); stroke.strokeWidth = 1.5f * dp
                c.drawLine(x - s * 0.15f, y - s * 1.15f, sx - s * 0.15f, sy, stroke); c.drawLine(x + s * 0.15f, y - s * 1.15f, sx + s * 0.15f, sy, stroke)
                fill.color = Color.rgb(180, 120, 70)
                c.drawRoundRect(sx - s * 0.2f, sy - s * 0.02f, sx + s * 0.2f, sy + s * 0.05f, s * 0.02f, s * 0.02f, fill)
            }
            13 -> { // slide
                stroke.color = Color.rgb(140, 150, 170); stroke.strokeWidth = s * 0.04f
                c.drawLine(x - s * 0.5f, y, x - s * 0.5f, y - s * 0.9f, stroke); c.drawLine(x - s * 0.35f, y, x - s * 0.35f, y - s * 0.9f, stroke)
                for (k in 1..4) c.drawLine(x - s * 0.5f, y - k * s * 0.19f, x - s * 0.35f, y - k * s * 0.19f, stroke)
                path.reset(); path.moveTo(x - s * 0.38f, y - s * 0.9f); path.lineTo(x - s * 0.3f, y - s * 0.92f)
                path.quadTo(x + s * 0.2f, y - s * 0.5f, x + s * 0.8f, y - s * 0.03f); path.lineTo(x + s * 0.75f, y + s * 0.03f)
                path.quadTo(x + s * 0.15f, y - s * 0.42f, x - s * 0.38f, y - s * 0.82f); path.close()
                fill.shader = LinearGradient(x - s * 0.4f, y - s, x + s * 0.8f, y, Color.rgb(255, 210, 90), Color.rgb(255, 140, 60), Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
            }
            14 -> { // trampoline
                val dip = if (occupant != null && (occupant.phase * 3f % 3.1416f) < 0.4f) s * 0.05f else 0f
                stroke.color = Color.rgb(90, 90, 110); stroke.strokeWidth = s * 0.05f
                c.drawLine(x - s * 0.45f, y, x - s * 0.4f, y - s * 0.18f, stroke); c.drawLine(x + s * 0.45f, y, x + s * 0.4f, y - s * 0.18f, stroke)
                fill.color = Color.rgb(90, 160, 255)
                c.drawOval(x - s * 0.55f, y - s * 0.28f, x + s * 0.55f, y - s * 0.08f, fill)
                fill.color = Color.rgb(40, 40, 60)
                c.drawOval(x - s * 0.45f, y - s * 0.25f + dip, x + s * 0.45f, y - s * 0.11f + dip, fill)
            }
            15 -> { // campfire
                fill.color = Color.rgb(120, 120, 130)
                for (k in 0 until 7) { val a = k * 0.9f; c.drawOval(x + cos(a) * s * 0.32f - s * 0.07f, y + sin(a) * s * 0.08f - s * 0.05f, x + cos(a) * s * 0.32f + s * 0.07f, y + sin(a) * s * 0.08f + s * 0.03f, fill) }
                stroke.color = Color.rgb(110, 70, 40); stroke.strokeWidth = s * 0.08f
                c.drawLine(x - s * 0.22f, y - s * 0.02f, x + s * 0.18f, y - s * 0.12f, stroke); c.drawLine(x + s * 0.22f, y - s * 0.02f, x - s * 0.18f, y - s * 0.12f, stroke)
                for ((k, col) in intArrayOf(Color.rgb(255, 120, 40), Color.rgb(255, 190, 60), Color.rgb(255, 245, 170)).withIndex()) {
                    val fh = s * (0.55f - k * 0.15f) * (0.9f + 0.15f * sin(t * 9f + k))
                    val fw = s * (0.22f - k * 0.05f)
                    path.reset(); path.moveTo(x - fw, y - s * 0.08f)
                    path.quadTo(x - fw * 0.8f, y - fh * 0.6f, x + sin(t * 6f + k) * fw * 0.3f, y - fh)
                    path.quadTo(x + fw * 0.8f, y - fh * 0.6f, x + fw, y - s * 0.08f); path.close()
                    fill.color = col; c.drawPath(path, fill)
                }
                if (Random.nextFloat() < 0.15f) particles.add(Particle(x + (Random.nextFloat() - 0.5f) * s * 0.2f, y - s * 0.4f,
                    (Random.nextFloat() - 0.5f) * 20f * dp, -50f * dp, 1f, 1f, Color.rgb(255, 200, 90), P_SPARK))
            }
        }
    }

    // ---------- Weather touches ----------

    private fun drawLeafUmbrella(c: Canvas, x: Float, topY: Float, size: Float) {
        val lx = x + size * 0.12f; val ly = topY - size * 0.18f
        stroke.color = Color.rgb(80, 130, 60); stroke.strokeWidth = max(1.5f * dp, size * 0.03f)
        c.drawLine(lx, ly, x + size * 0.32f, topY + size * 0.45f, stroke)
        c.save(); c.rotate(-12f + sin(t * 2f) * 4f, lx, ly)
        fill.shader = LinearGradient(lx, ly - size * 0.3f, lx, ly + size * 0.1f, Color.rgb(130, 210, 100), Color.rgb(70, 150, 70), Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(lx - size * 0.62f, ly + size * 0.06f)
        path.quadTo(lx, ly - size * 0.5f, lx + size * 0.62f, ly + size * 0.06f)
        path.quadTo(lx, ly - size * 0.08f, lx - size * 0.62f, ly + size * 0.06f); path.close()
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Color.argb(120, 40, 90, 40); stroke.strokeWidth = 1f * dp
        c.drawLine(lx - size * 0.55f, ly + size * 0.04f, lx + size * 0.55f, ly + size * 0.04f, stroke)
        c.restore()
    }

    private fun drawRainbow(c: Canvas, w: Float, hz: Float) {
        val cols = intArrayOf(Color.rgb(255, 90, 90), Color.rgb(255, 170, 70), Color.rgb(255, 235, 100),
            Color.rgb(120, 220, 120), Color.rgb(90, 170, 255), Color.rgb(170, 120, 255))
        stroke.strokeWidth = 7f * dp
        for ((k, col) in cols.withIndex()) {
            stroke.color = Color.argb(110, Color.red(col), Color.green(col), Color.blue(col))
            val r = w * 0.62f - k * 7f * dp
            c.drawArc(w * 0.35f - r, hz - r * 0.75f, w * 0.35f + r, hz + r * 0.75f, 180f, 180f, false, stroke)
        }
    }

    private var lastAmbient = ""
    private fun updateAmbience() {
        val inGarden = tab == Tab.GARDEN && !settingsOpen && popup == null
        val dark = LabState.darkness()
        val rain = LabState.raining()
        val fire = lab.placed.any { it.kind == 15 }
        val key = "$inGarden${dark >= 0.5f}$rain$fire${sound.sfxOn}${sound.ambientReady()}"
        if (key == lastAmbient) return
        lastAmbient = key
        sound.setAmbient(inGarden && dark < 0.5f && !rain, inGarden && dark >= 0.5f && !rain, inGarden && rain, inGarden && fire)
    }

    // ---------- Automated testing support ----------

    /** Set by the test runner: logs where things are on screen so scripted taps can find them. */
    var qaMode = false

    private fun qaReport() {
        val sb = StringBuilder("{\"splash\":${splashT > 0f},\"tab\":\"$tab\",\"tut\":$tutStep,\"popup\":${popup != null},\"sel\":$selected,\"dust\":${lab.dust},\"w\":$width,\"h\":$height,\"shrooms\":[")
        for ((i, s) in lab.home().withIndex()) {
            val (x, y) = shroomScreen(s)
            val size = shroomSize(s)
            if (i > 0) sb.append(",")
            sb.append("{\"id\":${s.id},\"x\":${x.toInt()},\"y\":${(y - size * 0.45f).toInt()},\"act\":${s.act},\"stage\":${s.stage()}}")
        }
        sb.append("],\"buttons\":[")
        for ((i, b) in buttons.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${b.id}\",\"x\":${b.rect.centerX().toInt()},\"y\":${b.rect.centerY().toInt()},\"on\":${b.enabled}}")
        }
        sb.append("]}")
        android.util.Log.d("SHROOMQA", sb.toString())
    }

    // ---------- Bouncy UI helpers ----------

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1f
        val k = x.coerceIn(0f, 1f) - 1f
        return 1f + c3 * k * k * k + c1 * k * k
    }

    // ---------- Splash screen ----------

    private var splashT = 2.6f
    var holdSplash = false   // test runs can freeze the splash fully drawn to photograph it

    private fun drawSplash(c: Canvas, w: Float, h: Float) {
        val age = 2.6f - splashT
        val alpha = (min(1f, splashT / 0.45f) * 255).toInt().coerceIn(0, 255)
        val ev = eventNow()
        c.saveLayerAlpha(0f, 0f, w, h, alpha)
        // sky colors per season
        val (top, bottom, glowC) = when (ev) {
            1 -> Triple(Color.rgb(40, 18, 64), Color.rgb(120, 45, 40), Color.rgb(255, 140, 50))
            2 -> Triple(Color.rgb(24, 40, 96), Color.rgb(90, 130, 190), Color.rgb(200, 230, 255))
            3 -> Triple(Color.rgb(255, 175, 205), Color.rgb(255, 225, 200), Color.rgb(255, 255, 230))
            else -> Triple(Color.rgb(60, 38, 110), Color.rgb(24, 16, 44), Color.rgb(255, 140, 200))
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        fill.shader = RadialGradient(w / 2, h * 0.45f, w * 0.6f, Color.argb(90, Color.red(glowC), Color.green(glowC), Color.blue(glowC)),
            Color.argb(0, Color.red(glowC), Color.green(glowC), Color.blue(glowC)), Shader.TileMode.CLAMP)
        c.drawCircle(w / 2, h * 0.45f, w * 0.6f, fill); fill.shader = null

        when (ev) {
            1 -> splashSpooky(c, w, h, age)
            2 -> splashFrosty(c, w, h, age)
            3 -> splashSpring(c, w, h, age)
            else -> for (k in 0 until 14) {
                val sx = w * ((k * 0.173f + age * 0.02f) % 1f)
                val sy = h * (0.15f + ((k * 0.37f - age * 0.04f) % 0.7f + 0.7f) % 0.7f)
                fill.color = Color.argb((120 + 100 * sin(age * 3f + k)).toInt().coerceIn(0, 255), 255, 240, 200)
                drawSparkle(c, sx, sy, 2.5f * dp)
            }
        }

        // a mound and a mushroom sprouting from it
        val moundY = h * 0.53f
        fill.color = when (ev) { 1 -> Color.rgb(70, 60, 70); 2 -> Color.rgb(235, 242, 255); 3 -> Color.rgb(140, 210, 130); else -> Color.rgb(78, 140, 104) }
        c.drawOval(w / 2 - 110f * dp, moundY, w / 2 + 110f * dp, moundY + 50f * dp, fill)
        val hero = when (ev) { 1 -> 60; 2 -> 65; 3 -> 72; else -> 1 }
        when (ev) {   // things sitting on the mound
            1 -> { drawEventDecor(c, 16, w / 2 - 82f * dp, moundY + 30f * dp, 46f * dp); drawEventDecor(c, 16, w / 2 + 84f * dp, moundY + 34f * dp, 34f * dp) }
            2 -> { splashGift(c, w / 2 - 80f * dp, moundY + 30f * dp, 30f * dp, Color.rgb(230, 60, 70)); splashGift(c, w / 2 + 82f * dp, moundY + 32f * dp, 24f * dp, Color.rgb(80, 170, 110)) }
            3 -> for (k in 0 until 5) splashFlower(c, w / 2 + (k - 2) * 44f * dp + (if (k == 2) 0f else 0f), moundY + 26f * dp + (k % 2) * 8f * dp, 8f * dp,
                    if (k % 2 == 0) Color.rgb(255, 120, 170) else Color.rgb(255, 230, 120), age + k)
        }
        val grow = easeOutBack(((age - 0.2f) / 0.9f).coerceIn(0f, 1f))
        if (grow > 0.01f) drawShroom(c, hero, w / 2, h * 0.55f, 120f * dp * grow, 0, sin(age * 3f), (age % 2.5f) > 2.35f, false)

        // title letters hop in one by one
        val title = "Shroomology"
        text.textSize = 44f * dp
        while (text.textSize > 20f * dp && text.measureText(title) > w - 36f * dp) text.textSize -= 1f * dp
        val total = text.measureText(title)
        var x = w / 2 - total / 2
        text.textAlign = Paint.Align.LEFT
        val titleC = when (ev) { 1 -> Color.rgb(255, 170, 70); 2 -> Color.rgb(235, 248, 255); 3 -> Color.rgb(255, 255, 255); else -> Color.WHITE }
        for ((k, ch) in title.withIndex()) {
            val lt = ((age - 0.5f - k * 0.05f) / 0.35f).coerceIn(0f, 1f)
            val hop = (1f - easeOutBack(lt)) * 30f * dp
            if (ev == 3) { text.color = Color.argb((lt * 120).toInt(), 200, 80, 130); c.drawText(ch.toString(), x + 2f * dp, h * 0.3f + hop + 3f * dp, text) }
            text.color = Color.argb((lt * 255).toInt(), Color.red(titleC), Color.green(titleC), Color.blue(titleC))
            c.drawText(ch.toString(), x, h * 0.3f + hop, text)
            x += text.measureText(ch.toString())
        }
        text.textAlign = Paint.Align.CENTER
        val subA = (((age - 1.0f) / 0.4f).coerceIn(0f, 1f) * 255).toInt()
        if (ev > 0) {
            text.textSize = 18f * dp
            val subC = when (ev) { 1 -> Color.rgb(200, 255, 170); 2 -> Color.rgb(255, 255, 255); else -> Color.rgb(200, 80, 140) }
            text.color = Color.argb(subA, Color.red(subC), Color.green(subC), Color.blue(subC))
            val sub = when (ev) { 1 -> "🎃 Happy Shroomoween! 🎃"; 2 -> "❄️ Frosty Festival ❄️"; else -> "🌸 Spring Bloom 🌸" }
            c.drawText(sub, w / 2, h * 0.3f + 36f * dp, text)
        }
        text.textSize = 14f * dp
        text.color = if (ev == 3) Color.argb((subA * 0.8f).toInt(), 160, 70, 120) else Color.argb((subA * 0.8f).toInt(), 255, 200, 230)
        c.drawText("Made by Omnymara", w / 2, h * 0.88f, text)
        text.color = Color.WHITE
        c.restore()
    }

    /** Shroomoween: a big moon, bats and bobbing little ghosts. */
    private fun splashSpooky(c: Canvas, w: Float, h: Float, age: Float) {
        fill.color = Color.argb(60, 255, 230, 170); c.drawCircle(w * 0.78f, h * 0.13f, 70f * dp, fill)
        fill.color = Color.rgb(255, 235, 180); c.drawCircle(w * 0.78f, h * 0.13f, 46f * dp, fill)
        fill.color = Color.argb(40, 200, 170, 120)
        c.drawCircle(w * 0.76f, h * 0.12f, 9f * dp, fill); c.drawCircle(w * 0.8f, h * 0.15f, 6f * dp, fill)
        drawBatsAndFog(c, w, h * 0.5f)
        for (k in 0 until 3) {
            val gx = w * floatArrayOf(0.15f, 0.85f, 0.25f)[k] + sin(age * 1.5f + k) * 10f * dp
            val gy = h * floatArrayOf(0.42f, 0.4f, 0.68f)[k] + sin(age * 2.2f + k * 2f) * 12f * dp
            val r = floatArrayOf(18f, 14f, 12f)[k] * dp
            fill.color = Color.argb(220, 250, 250, 255)
            path.reset()
            path.moveTo(gx - r, gy + r); path.lineTo(gx - r, gy)
            path.cubicTo(gx - r, gy - r * 1.4f, gx + r, gy - r * 1.4f, gx + r, gy)
            path.lineTo(gx + r, gy + r); path.lineTo(gx + r * 0.5f, gy + r * 0.7f); path.lineTo(gx, gy + r); path.lineTo(gx - r * 0.5f, gy + r * 0.7f)
            path.close(); c.drawPath(path, fill)
            fill.color = Color.rgb(50, 40, 70)
            c.drawOval(gx - r * 0.45f, gy - r * 0.3f, gx - r * 0.2f, gy + r * 0.05f, fill)
            c.drawOval(gx + r * 0.2f, gy - r * 0.3f, gx + r * 0.45f, gy + r * 0.05f, fill)
            fill.color = Color.argb(150, 255, 140, 170)
            c.drawCircle(gx - r * 0.55f, gy + r * 0.2f, r * 0.12f, fill); c.drawCircle(gx + r * 0.55f, gy + r * 0.2f, r * 0.12f, fill)
        }
        // drifting candy corn
        for (k in 0 until 6) {
            val f = (age * 0.08f + k * 0.17f) % 1f
            val x = w * ((k * 0.29f) % 1f); val y = h * (0.1f + f * 0.85f)
            c.save(); c.rotate(age * 60f + k * 50f, x, y)
            drawEventFood(c, 5, x, y, 7f * dp)
            c.restore()
        }
    }

    /** Frosty Festival: falling snow, twinkly pines and a garland of lights. */
    private fun splashFrosty(c: Canvas, w: Float, h: Float, age: Float) {
        for (k in 0 until 3) {
            val tx = w * floatArrayOf(0.12f, 0.88f, 0.78f)[k]; val ty = h * floatArrayOf(0.66f, 0.64f, 0.74f)[k]
            val ts = floatArrayOf(70f, 60f, 44f)[k] * dp
            fill.color = Color.rgb(40, 110, 80)
            for (layer in 0 until 3) {
                val ly = ty - layer * ts * 0.32f; val lw = ts * (0.55f - layer * 0.13f)
                path.reset(); path.moveTo(tx - lw, ly); path.lineTo(tx, ly - ts * 0.45f); path.lineTo(tx + lw, ly); path.close()
                c.drawPath(path, fill)
            }
            fill.color = Color.argb(220, 255, 255, 255)
            c.drawOval(tx - ts * 0.3f, ty - ts * 0.9f, tx + ts * 0.3f, ty - ts * 0.78f, fill)
            fill.color = Color.rgb(255, 220, 90); drawSparkle(c, tx, ty - ts * 1.02f, ts * 0.06f)
            for (b in 0 until 4) {
                fill.color = intArrayOf(Color.rgb(255, 90, 100), Color.rgb(255, 220, 90), Color.rgb(120, 200, 255), Color.rgb(255, 150, 220))[b]
                fill.alpha = (150 + 100 * sin(age * 4f + b + k)).toInt().coerceIn(0, 255)
                c.drawCircle(tx + (b - 1.5f) * ts * 0.18f, ty - ts * 0.1f - (b % 2) * ts * 0.3f, ts * 0.035f, fill)
            }
            fill.alpha = 255
        }
        // string of lights across the top
        stroke.color = Color.argb(150, 30, 40, 60); stroke.strokeWidth = 1.5f * dp
        path.reset(); path.moveTo(0f, h * 0.06f); path.quadTo(w / 2, h * 0.14f, w, h * 0.06f); c.drawPath(path, stroke)
        for (k in 0..9) {
            val f = k / 9f
            val lx = w * f; val ly = h * 0.06f + (1f - (2f * f - 1f) * (2f * f - 1f)) * h * 0.04f
            fill.color = intArrayOf(Color.rgb(255, 90, 100), Color.rgb(255, 220, 90), Color.rgb(120, 220, 140), Color.rgb(120, 190, 255))[k % 4]
            fill.alpha = if (((age * 3f).toInt() + k) % 2 == 0) 255 else 140
            c.drawOval(lx - 4f * dp, ly + 2f * dp, lx + 4f * dp, ly + 12f * dp, fill)
        }
        fill.alpha = 255
        for (k in 0 until 40) {
            val f = (age * (0.05f + (k % 5) * 0.012f) + k * 0.137f) % 1f
            val x = w * ((k * 0.211f) % 1f) + sin(age * 1.5f + k) * 8f * dp
            fill.color = Color.argb(200, 255, 255, 255)
            c.drawCircle(x, h * f, (1.5f + (k % 3)) * dp, fill)
        }
    }

    private fun splashGift(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        fill.color = col; c.drawRoundRect(x - s / 2, y - s, x + s / 2, y, 3f * dp, 3f * dp, fill)
        fill.color = Color.rgb(255, 230, 120)
        c.drawRect(x - s * 0.08f, y - s, x + s * 0.08f, y, fill)
        c.drawRect(x - s / 2, y - s * 0.6f, x + s / 2, y - s * 0.45f, fill)
        c.drawOval(x - s * 0.3f, y - s * 1.2f, x, y - s * 0.95f, fill); c.drawOval(x, y - s * 1.2f, x + s * 0.3f, y - s * 0.95f, fill)
    }

    /** Spring Bloom: falling blossoms, butterflies and a soft sun. */
    private fun splashSpring(c: Canvas, w: Float, h: Float, age: Float) {
        fill.color = Color.argb(70, 255, 255, 220); c.drawCircle(w * 0.2f, h * 0.12f, 60f * dp, fill)
        fill.color = Color.rgb(255, 245, 200); c.drawCircle(w * 0.2f, h * 0.12f, 36f * dp, fill)
        // little clouds
        fill.color = Color.argb(220, 255, 255, 255)
        for ((cx0, cy0) in arrayOf(0.7f to 0.1f, 0.85f to 0.2f)) {
            val cx = w * cx0 + sin(age * 0.5f) * 6f * dp; val cy = h * cy0
            c.drawCircle(cx, cy, 16f * dp, fill); c.drawCircle(cx + 18f * dp, cy + 4f * dp, 13f * dp, fill); c.drawCircle(cx - 18f * dp, cy + 5f * dp, 12f * dp, fill)
        }
        // butterflies
        for (k in 0 until 3) {
            val bx = w * floatArrayOf(0.18f, 0.82f, 0.7f)[k] + sin(age * 1.2f + k * 2f) * 20f * dp
            val by = h * floatArrayOf(0.45f, 0.42f, 0.7f)[k] + cos(age * 1.7f + k) * 14f * dp
            val flap = 0.4f + 0.6f * abs(sin(age * 9f + k))
            val col = intArrayOf(Color.rgb(255, 140, 190), Color.rgb(150, 190, 255), Color.rgb(255, 210, 90))[k]
            fill.color = col
            c.drawOval(bx - 12f * dp * flap, by - 9f * dp, bx, by + 2f * dp, fill)
            c.drawOval(bx, by - 9f * dp, bx + 12f * dp * flap, by + 2f * dp, fill)
            c.drawOval(bx - 8f * dp * flap, by, bx, by + 8f * dp, fill)
            c.drawOval(bx, by, bx + 8f * dp * flap, by + 8f * dp, fill)
            fill.color = Color.rgb(80, 50, 60); c.drawRoundRect(bx - 1.5f * dp, by - 7f * dp, bx + 1.5f * dp, by + 7f * dp, 1.5f * dp, 1.5f * dp, fill)
        }
        // falling petals
        for (k in 0 until 24) {
            val f = (age * (0.06f + (k % 4) * 0.015f) + k * 0.173f) % 1f
            val x = w * ((k * 0.257f) % 1f) + sin(age * 2f + k) * 14f * dp
            val y = h * f
            c.save(); c.rotate(age * 90f + k * 40f, x, y)
            fill.color = if (k % 3 == 0) Color.WHITE else Color.rgb(255, 160, 200)
            c.drawOval(x - 5f * dp, y - 3f * dp, x + 5f * dp, y + 3f * dp, fill)
            c.restore()
        }
    }

    private fun splashFlower(c: Canvas, x: Float, y: Float, r: Float, col: Int, phase: Float) {
        stroke.color = Color.rgb(80, 150, 70); stroke.strokeWidth = 2f * dp
        c.drawLine(x, y, x, y + r * 2f, stroke)
        c.save(); c.rotate(sin(phase * 2f) * 8f, x, y + r * 2f)
        fill.color = col
        for (k in 0 until 5) { val a = k * 1.2566f; c.drawCircle(x + cos(a) * r * 0.7f, y + sin(a) * r * 0.7f, r * 0.55f, fill) }
        fill.color = Color.rgb(255, 240, 150); c.drawCircle(x, y, r * 0.4f, fill)
        c.restore()
    }

    // ---------- The veggie patch (in the meadow, to the right) ----------

    private var growPick = -1      // plot waiting for a seed
    private fun plotGx(i: Int) = 1.42f + (i % 3) * 0.17f + (i / 3) * 0.04f
    private fun plotGz(i: Int) = 0.1f + (i / 3) * 0.2f

    private fun tapPlot(i: Int) {
        val now = System.currentTimeMillis()
        val (px, py) = toScreen(plotGx(i), plotGz(i))
        when {
            lab.plotSeed[i] < 0 -> if (Data.SEEDS.indices.none { lab.seeds[it] > 0 }) toast("No seeds yet! Your mushrooms find them while they play 🌱") else { growPick = i; panelAnim = 0f }
            lab.plotProgress(i, now) >= 1f -> {
                val ing = Data.SEEDS[lab.plotSeed[i]].ingredient
                val n = lab.harvest(i)
                burst(px, py - 30f * dp, Data.INGREDIENTS[ing].color, 18, P_SPARK)
                sound.play(Fx.GROW); buzz(30)
                toast("Harvested $n ${Data.INGREDIENTS[ing].name}!", MINT)
                markDirty()
            }
            lab.water(i) -> {
                burst(px, py - 40f * dp, Color.rgb(140, 200, 255), 14, P_SPARK)
                sound.play(Fx.BUBBLE, 0.7f); toast("Watered! It grows 30% faster 💧", MINT); markDirty()
            }
            else -> toast("Ready in ${timeLeft((lab.plotAt[i] + lab.plotDuration(i)) - now)}")
        }
    }

    private fun seedButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val top = panelTop(); val pad = 16f * dp
        val by = navTop - 62f * dp + panelSlide()
        val owned = Data.SEEDS.indices.filter { lab.seeds[it] > 0 }
        val gy = top + 46f * dp
        val rh = min(50f * dp, (by - 8f * dp - gy) / max(1, owned.size))
        for ((k, sd) in owned.withIndex()) {
            val r = RectF(pad, gy + k * rh + 2f * dp, w - pad, gy + (k + 1) * rh - 2f * dp)
            list.add(Button("seed$sd", r, "", STYLE_CARD, custom = { c, rr ->
                drawSeedPacket(c, sd, rr.left + 22f * dp, rr.centerY(), min(14f * dp, rr.height() * 0.32f))
                text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
                c.drawText("${Data.SEEDS[sd].name}  ×${lab.seeds[sd]}", rr.left + 46f * dp, rr.centerY() - 1f * dp, text)
                text.textSize = 11f * dp; text.color = TEXT_DIM
                c.drawText("Grows ${Data.INGREDIENTS[Data.SEEDS[sd].ingredient].name} in ${fmtHours(Data.SEEDS[sd].hours)}", rr.left + 46f * dp, rr.centerY() + 14f * dp, text)
                text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            }) {
                val i = growPick
                if (i >= 0 && lab.plant(i, sd)) {
                    sound.play(Fx.POP, 0.7f, 0.8f)
                    val (px, py) = toScreen(plotGx(i), plotGz(i)); burst(px, py - 10f * dp, Color.rgb(150, 110, 70), 10, P_DUST)
                    markDirty()
                }
                growPick = -1
            })
        }
        list.add(Button("seed_cancel", RectF(pad, by, w - pad, by + 46f * dp), "CANCEL") { growPick = -1 })
    }

    private fun drawSeedPanel(c: Canvas, w: Float) {
        val top = panelTop()
        panel(c, RectF(8f * dp, top, w - 8f * dp, panelBottom()), 22f * dp)
        text.textSize = 17f * dp; c.drawText("Plant a seed 🌱", w / 2, top + 30f * dp, text)
    }

    private fun fmtHours(hrs: Float) = if (hrs < 1f) "${(hrs * 60).toInt()} min" else "${"%.1f".format(hrs).removeSuffix(".0")} h"

    /** A little garden bed in the meadow: soil, a sprout that grows, and the ripe ingredient. */
    private fun drawPlotWorld(c: Canvas, i: Int, x: Float, y: Float, d: Float) {
        val now = System.currentTimeMillis()
        val rw = 30f * dp * d; val rh = 10f * dp * d
        fill.color = Color.rgb(95, 65, 45); c.drawOval(x - rw, y - rh, x + rw, y + rh, fill)
        fill.color = Color.rgb(125, 85, 58); c.drawOval(x - rw * 0.86f, y - rh * 1.2f, x + rw * 0.86f, y + rh * 0.4f, fill)
        fill.color = Color.rgb(150, 105, 72)
        for (k in -1..1) c.drawOval(x + k * rw * 0.5f - rw * 0.18f, y - rh * 0.9f, x + k * rw * 0.5f + rw * 0.18f, y - rh * 0.3f, fill)
        // little fence posts at the corners
        stroke.color = Color.rgb(170, 120, 75); stroke.strokeWidth = 2.5f * dp * d
        c.drawLine(x - rw * 1.02f, y, x - rw * 1.02f, y - 14f * dp * d, stroke); c.drawLine(x + rw * 1.02f, y, x + rw * 1.02f, y - 14f * dp * d, stroke)
        val sd = lab.plotSeed[i]
        text.textSize = 10f * dp * d
        if (sd < 0) {
            text.color = Color.argb(200, 255, 255, 255); c.drawText("+ plant", x, y - rh * 1.6f, text); text.color = Color.WHITE
            return
        }
        val p = lab.plotProgress(i, now)
        val ing = Data.SEEDS[sd].ingredient
        val base = y - rh * 0.5f
        c.save(); c.rotate(sin(t * 2f + i) * 4f, x, base)
        stroke.color = Color.rgb(90, 170, 80); stroke.strokeWidth = 3f * dp * d
        val stemH = 40f * dp * d * (0.15f + 0.85f * p)
        c.drawLine(x, base, x, base - stemH, stroke)
        fill.color = Color.rgb(120, 200, 100)
        if (p > 0.15f) {
            c.drawOval(x - 14f * dp * d * p - 4f * dp * d, base - stemH * 0.55f - 5f * dp * d, x, base - stemH * 0.55f + 3f * dp * d, fill)
            c.drawOval(x, base - stemH * 0.4f - 5f * dp * d, x + 14f * dp * d * p + 4f * dp * d, base - stemH * 0.4f + 3f * dp * d, fill)
        }
        if (p >= 1f) {
            drawIngredient(c, ing, x, base - stemH - 9f * dp * d + sin(t * 4f) * 2f * dp, 8f * dp * d)
            fill.color = Color.WHITE; drawSparkle(c, x + 12f * dp * d, base - stemH - 18f * dp * d, 2.5f * dp * d)
        } else { fill.color = Data.INGREDIENTS[ing].color; c.drawCircle(x, base - stemH, (2.5f + 4f * p) * dp * d, fill) }
        c.restore()
        text.color = if (p >= 1f) MINT else Color.argb(220, 255, 255, 255)
        val label = if (p >= 1f) "Harvest!" else timeLeft((lab.plotAt[i] + lab.plotDuration(i)) - now) + (if (lab.plotWater[i]) " 💧" else "")
        c.drawText(label, x, y + rh + 12f * dp * d, text)
        text.color = Color.WHITE
    }

    /** A wooden sign that marks the veggie patch. */
    private fun drawPatchSign(c: Canvas, x: Float, y: Float, d: Float) {
        stroke.color = Color.rgb(140, 95, 55); stroke.strokeWidth = 3f * dp * d
        c.drawLine(x, y, x, y - 34f * dp * d, stroke)
        fill.color = Color.rgb(190, 140, 85)
        c.drawRoundRect(x - 34f * dp * d, y - 50f * dp * d, x + 34f * dp * d, y - 30f * dp * d, 4f * dp * d, 4f * dp * d, fill)
        text.textSize = 10f * dp * d; text.color = Color.rgb(80, 50, 25)
        c.drawText("Veggie Patch", x, y - 36f * dp * d, text); text.color = Color.WHITE
    }

    private fun drawEdgeArrow(c: Canvas, r: RectF, dir: Float) {
        val a = (0.55f + 0.25f * sin(t * 3f))
        fill.color = Color.argb((90 * a).toInt(), 0, 0, 0); c.drawRoundRect(r, 12f * dp, 12f * dp, fill)
        stroke.color = Color.argb((255 * a).toInt(), 255, 255, 255); stroke.strokeWidth = 3f * dp
        val cx = r.centerX() + dir * sin(t * 3f) * 2f * dp; val cy = r.centerY()
        c.drawLine(cx - dir * 4f * dp, cy - 9f * dp, cx + dir * 4f * dp, cy, stroke)
        c.drawLine(cx + dir * 4f * dp, cy, cx - dir * 4f * dp, cy + 9f * dp, stroke)
    }

    private fun updateCamera(dt: Float) {
        if (tab != Tab.GARDEN) return
        if (!panning && abs(camVel) > 0.01f) {
            camX = (camX + camVel * dt).coerceIn(camMin(), camMax())
            camVel *= max(0f, 1f - dt * 3.5f)
            if (camX <= camMin() || camX >= camMax()) camVel = 0f
        }
        // dragging something toward an edge scrolls the meadow
        if (dragging && (dragKind == 3 || dragKind == 4 || dragKind == 5)) {
            val edge = 46f * dp
            if (dragX < edge) camX = (camX - dt * 0.9f).coerceIn(camMin(), camMax())
            if (dragX > width - edge) camX = (camX + dt * 0.9f).coerceIn(camMin(), camMax())
        }
    }

    private fun drawSeedPacket(c: Canvas, sd: Int, x: Float, y: Float, s: Float) {
        val col = Data.INGREDIENTS[Data.SEEDS[sd].ingredient].color
        fill.color = Color.rgb(240, 225, 195); c.drawRoundRect(x - s * 0.8f, y - s, x + s * 0.8f, y + s, s * 0.2f, s * 0.2f, fill)
        fill.color = col; c.drawRoundRect(x - s * 0.6f, y - s * 0.55f, x + s * 0.6f, y + s * 0.45f, s * 0.15f, s * 0.15f, fill)
        fill.color = Color.rgb(200, 180, 150); c.drawRect(x - s * 0.8f, y - s, x + s * 0.8f, y - s * 0.75f, fill)
        fill.color = Color.WHITE; c.drawCircle(x, y - s * 0.05f, s * 0.18f, fill)
    }

    // ---------- Lab: COOK ----------

    private fun drawCookedFood(c: Canvas, f: Int, x: Float, y: Float, r: Float) {
        when (f) {
            8 -> { // honey cake: two layers, a drip and a cherry
                fill.color = Color.rgb(240, 200, 140); c.drawRoundRect(x - r * 0.85f, y - r * 0.2f, x + r * 0.85f, y + r * 0.7f, r * 0.2f, r * 0.2f, fill)
                fill.color = Color.rgb(255, 190, 60); c.drawRoundRect(x - r * 0.85f, y - r * 0.55f, x + r * 0.85f, y - r * 0.1f, r * 0.2f, r * 0.2f, fill)
                c.drawCircle(x - r * 0.4f, y - r * 0.05f, r * 0.15f, fill); c.drawCircle(x + r * 0.3f, y, r * 0.18f, fill)
                fill.color = Color.rgb(230, 50, 70); c.drawCircle(x, y - r * 0.72f, r * 0.2f, fill)
            }
            9 -> { // ember stew in a bowl with steam
                fill.color = Color.rgb(230, 100, 60); c.drawOval(x - r * 0.85f, y - r * 0.35f, x + r * 0.85f, y + r * 0.05f, fill)
                fill.color = Color.rgb(150, 110, 90); c.drawArc(x - r * 0.9f, y - r * 0.6f, x + r * 0.9f, y + r * 0.8f, 0f, 180f, true, fill)
                stroke.color = Color.argb(160, 255, 255, 255); stroke.strokeWidth = r * 0.1f
                for (k in -1..1) c.drawArc(x + k * r * 0.35f - r * 0.12f, y - r * 1.1f, x + k * r * 0.35f + r * 0.12f, y - r * 0.5f, 90f, 180f, false, stroke)
            }
            10 -> { // petal tea cup
                fill.color = Color.WHITE; c.drawRoundRect(x - r * 0.7f, y - r * 0.5f, x + r * 0.55f, y + r * 0.6f, r * 0.25f, r * 0.25f, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = r * 0.14f; c.drawArc(x + r * 0.35f, y - r * 0.3f, x + r * 0.95f, y + r * 0.3f, -90f, 180f, false, stroke)
                fill.color = Color.rgb(255, 170, 210); c.drawOval(x - r * 0.6f, y - r * 0.5f, x + r * 0.45f, y - r * 0.25f, fill)
                fill.color = Color.rgb(255, 120, 170); c.drawCircle(x - r * 0.1f, y - r * 0.4f, r * 0.1f, fill)
            }
            11 -> { // frost pop on a stick
                stroke.color = Color.rgb(220, 190, 140); stroke.strokeWidth = r * 0.16f; c.drawLine(x, y + r * 0.2f, x, y + r * 0.95f, stroke)
                fill.color = Color.rgb(160, 220, 255); c.drawRoundRect(x - r * 0.45f, y - r * 0.95f, x + r * 0.45f, y + r * 0.35f, r * 0.4f, r * 0.4f, fill)
                fill.color = Color.argb(160, 255, 255, 255); c.drawRoundRect(x - r * 0.3f, y - r * 0.8f, x - r * 0.12f, y, r * 0.1f, r * 0.1f, fill)
            }
            else -> { // moon pie with a crescent
                fill.color = Color.rgb(200, 150, 100); c.drawOval(x - r * 0.9f, y - r * 0.15f, x + r * 0.9f, y + r * 0.55f, fill)
                fill.color = Color.rgb(190, 140, 255); c.drawOval(x - r * 0.85f, y - r * 0.45f, x + r * 0.85f, y + r * 0.2f, fill)
                fill.color = Color.rgb(255, 245, 200); c.drawCircle(x, y - r * 0.15f, r * 0.24f, fill)
                fill.color = Color.rgb(190, 140, 255); c.drawCircle(x + r * 0.1f, y - r * 0.2f, r * 0.2f, fill)
            }
        }
    }

    private fun drawGiftBox(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        fill.shader = LinearGradient(0f, y - s, 0f, y, shade(col, 0.2f), shade(col, -0.2f), Shader.TileMode.CLAMP)
        c.drawRoundRect(x - s / 2, y - s * 0.8f, x + s / 2, y, s * 0.1f, s * 0.1f, fill); fill.shader = null
        fill.color = shade(col, 0.3f); c.drawRoundRect(x - s * 0.58f, y - s * 0.95f, x + s * 0.58f, y - s * 0.72f, s * 0.08f, s * 0.08f, fill)
        fill.color = Color.WHITE
        c.drawRect(x - s * 0.08f, y - s * 0.95f, x + s * 0.08f, y, fill)
        c.drawOval(x - s * 0.36f, y - s * 1.18f, x - s * 0.02f, y - s * 0.92f, fill); c.drawOval(x + s * 0.02f, y - s * 1.18f, x + s * 0.36f, y - s * 0.92f, fill)
        fill.color = Color.WHITE; drawSparkle(c, x + s * 0.55f, y - s * 1.05f, s * 0.08f)
    }

    private fun growArea(w: Float, h: Float) = RectF(16f * dp, h * 0.45f + 34f * dp, w - 16f * dp, navTop - 10f * dp)

    private fun cookButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val a = growArea(w, h)
        val rh = min(66f * dp, a.height() / Data.RECIPES.size)
        for ((k, rcp) in Data.RECIPES.withIndex()) {
            val y = a.top + k * rh
            val ok = lab.canCook(rcp)
            list.add(Button("cook$k", RectF(a.right - 86f * dp, y + rh / 2 - 20f * dp, a.right, y + rh / 2 + 20f * dp), "COOK", STYLE_PRIMARY, ok) {
                if (!lab.cook(rcp)) { sound.play(Fx.DENIED); toast("Not enough ingredients", ACCENT); return@Button }
                sound.play(Fx.BUBBLE); sound.play(Fx.DISCOVER, 0.5f)
                burst(w / 2, y + rh / 2, Data.FOODS[rcp.food].color, 18, P_SPARK)
                professorSay(listOf("Smells delicious!", "Mmm, a chef!", "Bon appétit!").random(), 2.5f)
                toast("Cooked ${Data.FOODS[rcp.food].name}! Find it in FEED 🍰", MINT)
                markDirty()
            })
        }
    }

    private fun drawCook(c: Canvas, w: Float, h: Float) {
        val a = growArea(w, h)
        text.textSize = 13f * dp; text.color = TEXT_DIM
        c.drawText("Kitchen · cook ingredients into special treats", w / 2, a.top - 12f * dp, text)
        text.color = Color.WHITE
        val rh = min(66f * dp, a.height() / Data.RECIPES.size)
        for ((k, rcp) in Data.RECIPES.withIndex()) {
            val y = a.top + k * rh
            fill.color = if (k % 2 == 0) PANEL else Color.rgb(40, 28, 74)
            c.drawRoundRect(a.left, y + 2f * dp, a.right, y + rh - 2f * dp, 14f * dp, 14f * dp, fill)
            val cy = y + rh / 2
            drawFood(c, rcp.food, a.left + 24f * dp, cy, 14f * dp)
            val f = Data.FOODS[rcp.food]
            text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
            c.drawText(f.name + "  ×${lab.foods[rcp.food]}", a.left + 46f * dp, cy - 6f * dp, text)
            text.textSize = 10.5f * dp; text.color = TEXT_DIM
            c.drawText(Data.FOOD_EFFECTS[rcp.food] ?: "", a.left + 46f * dp, cy + 8f * dp, text)
            text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            // ingredients needed
            val need = rcp.ingredients.toList().groupingBy { it }.eachCount()
            var x = a.left + 46f * dp
            for ((ing, n) in need) {
                drawIngredient(c, ing, x + 6f * dp, cy + 20f * dp, 5f * dp)
                text.textSize = 10f * dp; text.textAlign = Paint.Align.LEFT
                text.color = if (lab.ingredients[ing] >= n) MINT else ACCENT
                c.drawText("${lab.ingredients[ing]}/$n", x + 15f * dp, cy + 24f * dp, text)
                text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
                x += 50f * dp
            }
        }
    }

    // ---------- Shop previews: see it before you buy it ----------

    private var previewKind = -1      // 2 decor, 3 theme, 4 accessory, 6 pet, 7 pack
    private var previewIdx = 0
    private var previewAnim = 0f
    private var previewFromOffer = false

    private fun previewCard(w: Float, h: Float) = RectF(16f * dp, h * 0.08f, w - 16f * dp, navTop - 8f * dp)
    private fun previewScene(w: Float, h: Float): RectF { val c = previewCard(w, h); return RectF(c.left + 12f * dp, c.top + 64f * dp, c.right - 12f * dp, c.top + 64f * dp + (c.width() - 24f * dp) * 0.92f) }

    /** The shop's own buy button for this item, so previews buy exactly the same way. */
    private fun shopBuyButton(kind: Int, i: Int): Button? {
        val tmp = ArrayList<Button>()
        val savedTab = shopTab
        shopTab = when (kind) { 2 -> if (Data.DECOR[i].event > 0) 8 else 2; 3 -> if (Data.THEMES[i].event > 0) 8 else 3; 4 -> if (Data.ACCESSORIES[i].event > 0) 8 else 4; 6 -> 6; else -> 7 }
        shopButtons(tmp, width.toFloat(), height.toFloat())
        shopTab = savedTab
        val id = when (kind) { 2 -> "dbuy$i"; 3 -> "tbuy$i"; 4 -> "abuy$i"; 6 -> "pbuy$i"; else -> "pack$i" }
        return tmp.firstOrNull { it.id == id }
    }

    private fun previewButtons(l: ArrayList<Button>, w: Float, h: Float) {
        val card = previewCard(w, h)
        val half = (card.width() - 36f * dp) / 2
        val y = card.bottom - 58f * dp
        val buy = shopBuyButton(previewKind, previewIdx)
        val forSale = !(previewKind == 2 && Data.DECOR[previewIdx].special > 0) && !(previewKind == 4 && Data.ACCESSORIES[previewIdx].special > 0)
        if (buy != null && forSale) {
            l.add(Button("pv_buy", RectF(card.left + 12f * dp, y, card.left + 12f * dp + half, y + 46f * dp), buy.label, if (buy.enabled) STYLE_PRIMARY else STYLE_NORMAL, buy.enabled) {
                buy.action()
                if (previewKind != 3) { previewKind = -1; if (previewFromOffer) { previewFromOffer = false } }
            })
        }
        l.add(Button("pv_close", RectF(card.right - 12f * dp - half, y, card.right - 12f * dp, y + 46f * dp), "CLOSE") {
            previewKind = -1; previewFromOffer = false
        })
    }

    /** A mini garden drawn into r: a theme, some decorations, and a shroom dressed up with a pet. */
    private fun drawMiniScene(c: Canvas, r: RectF, theme: Int, decor: List<Int>, wear: IntArray, pet: Int, species: Int, bigDecor: Boolean = false) {
        val w = width.toFloat(); val h = height.toFloat()
        val savedTheme = lab.theme; val savedArea = area; val savedCam = camX
        lab.theme = theme; area = 0; camX = 0.5f
        val src = RectF(0f, h * 0.06f, w, h * 0.66f)
        val sc = max(r.width() / src.width(), r.height() / src.height())
        c.save()
        c.clipRect(r)
        c.translate(r.centerX(), r.centerY()); c.scale(sc, sc); c.translate(-src.centerX(), -src.centerY())
        drawGardenScene(c, w, h)
        // decorations spread around the shroom, big ones in front
        val spots = listOf(0.22f to 0.35f, 0.78f to 0.4f, 0.3f to 0.7f, 0.72f to 0.75f, 0.5f to 0.2f, 0.12f to 0.85f)
        val items = ArrayList<Triple<Float, Float, Int>>()
        if (bigDecor && decor.isNotEmpty()) items.add(Triple(0.36f, 0.55f, decor[0]))
        else for ((k, d) in decor.withIndex()) spots.getOrNull(k)?.let { items.add(Triple(it.first, it.second, d)) }
        val shGz = if (bigDecor) 0.62f else 0.55f
        val shGx = if (bigDecor) 0.64f else 0.5f
        val all = (items.map { Pair(it.second, it) } + Pair(shGz, null)).sortedBy { it.first }
        for ((_, it) in all) {
            if (it != null) {
                val (sx, sy) = toScreen(it.first, it.second)
                drawDecor(c, it.third, sx, sy, depth(it.second) * (if (bigDecor) 1.5f else 1f))
            } else {
                val (sx, sy) = toScreen(shGx, shGz)
                val size = 64f * dp * depth(shGz) * (if (bigDecor) 1f else 1.2f)
                val who = Shroom(-7L, species).apply { xp = 200f; for (k in 0..2) this.wear[k] = wear[k]; expr = E_GIGGLE; exprT = 1f }
                drawShroom(c, species, sx, sy, size, 0, sin(t * 2.5f), (t % 3f) < 0.12f, false, facing = -1f, who = who)
                if (pet >= 0) {
                    if (pet == 1 || pet == 2 || pet == Data.MINI_DRAGON) {
                        val a = t * 1.6f
                        drawPet(c, pet, sx + cos(a) * size * 0.6f, sy - size * 1.05f + sin(a * 1.3f) * size * 0.12f, size * 0.32f, t * 10f, if (cos(a) > 0) -1f else 1f, false)
                    } else {
                        val (px, py) = toScreen(shGx - 0.14f, shGz + 0.04f)
                        drawPet(c, pet, px + sin(t * 1.5f) * 6f * dp, py, size * 0.45f, t * 8f, 1f, false)
                    }
                }
            }
        }
        drawAmbient(c, w)
        c.restore()
        lab.theme = savedTheme; area = savedArea; camX = savedCam
        stroke.color = Color.argb(160, 255, 255, 255); stroke.strokeWidth = 2f * dp
        c.drawRoundRect(r, 16f * dp, 16f * dp, stroke)
    }

    /** Which shroom models the preview: the one you have selected, your first, or a Button Cap. */
    private fun previewSpecies() = shroom(selected)?.species ?: lab.garden.firstOrNull()?.species ?: 1

    private fun drawPreview(c: Canvas, w: Float, h: Float) {
        val card = previewCard(w, h)
        val k = 0.85f + 0.15f * easeOutBack(previewAnim)
        c.save(); c.scale(k, k, card.centerX(), card.centerY())
        panel(c, card, 24f * dp, PANEL_LIGHT)
        val scene = previewScene(w, h)
        val sp = previewSpecies()
        val noWear = intArrayOf(-1, -1, -1)
        var title = ""; var sub = ""
        when (previewKind) {
            3 -> { val th = Data.THEMES[previewIdx]; title = th.name; sub = "Garden theme · new sky, ground and weather"
                drawMiniScene(c, scene, previewIdx, listOf(1, 7), noWear, -1, sp) }
            2 -> { val d = Data.DECOR[previewIdx]; title = d.name; sub = if (d.glow) "Decoration · glows at night" else if (previewIdx in 11..15) "Toy · your shrooms play with it" else if (previewIdx == Data.COZY_BED) "Decoration · one shroom at a time naps here" else "Decoration"
                drawMiniScene(c, scene, lab.theme, listOf(previewIdx), noWear, -1, sp, bigDecor = true) }
            4 -> { val a = Data.ACCESSORIES[previewIdx]; title = a.name; sub = "Worn on the " + arrayOf("cap", "face", "stem")[a.slot]
                val wr = intArrayOf(-1, -1, -1); wr[a.slot] = previewIdx
                drawMiniScene(c, scene, lab.theme, emptyList(), wr, -1, sp) }
            6 -> { val pt = Data.PETS[previewIdx]; title = pt.name; sub = "Pet · " + pt.desc
                drawMiniScene(c, scene, lab.theme, emptyList(), noWear, previewIdx, sp) }
            else -> {
                val pk = Data.PACKS[previewIdx]; title = pk.name; sub = pk.price
                val b = pk.bundle
                val ev = eventNow()
                val theme = b.themes.firstOrNull() ?: (if (pk.id == "event_boost" && ev > 0) Data.THEMES.indices.firstOrNull { Data.THEMES[it].event == ev } else null) ?: lab.theme
                val decor = if (pk.id == "event_boost" && ev > 0) Data.DECOR.indices.filter { Data.DECOR[it].event == ev } else b.decor.toList()
                val wr = intArrayOf(-1, -1, -1); for (a in b.acc) { val sl = Data.ACCESSORIES[a].slot; if (wr[sl] < 0 || Data.ACCESSORIES[a].special > 0) wr[sl] = a }
                drawMiniScene(c, scene, theme, decor.take(5), wr, b.pets.firstOrNull() ?: -1, sp)
                drawPackContents(c, pk, RectF(card.left + 14f * dp, scene.bottom + 10f * dp, card.right - 14f * dp, card.bottom - 66f * dp))
            }
        }
        text.textSize = 22f * dp; text.color = Color.WHITE
        c.drawText(title, w / 2, card.top + 32f * dp, text)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        c.drawText(sub, w / 2, card.top + 52f * dp, text)
        text.color = Color.WHITE
        if (previewKind != 7) {
            val owned = when (previewKind) { 3 -> (lab.themesOwned shr previewIdx) and 1 == 1; 2 -> lab.decorOwned[previewIdx] > 0 || lab.placed.any { it.kind == previewIdx }
                4 -> lab.accOwned[previewIdx] > 0; else -> lab.petOwned[previewIdx] > 0 }
            text.textSize = 13f * dp; text.color = if (owned) MINT else TEXT_DIM
            c.drawText(if (owned) "✓ You own this" else "Modeled by ${shroom(selected)?.name ?: lab.garden.firstOrNull()?.name ?: "a Button Cap"}", w / 2, scene.bottom + 26f * dp, text)
            text.color = Color.WHITE
        }
        c.restore()
    }

    /** Little tiles for everything in a pack. */
    private fun drawPackContents(c: Canvas, pk: Pack, r: RectF) {
        val b = pk.bundle
        val ev = eventNow()
        val tiles = ArrayList<Pair<String, (Float, Float, Float) -> Unit>>()
        if (b.dust > 0) tiles.add("${b.dust} ✦" to { x, y, s -> fill.shader = RadialGradient(x - s * 0.2f, y - s * 0.2f, s, Color.rgb(255, 245, 190), DUST, Shader.TileMode.CLAMP); c.drawCircle(x, y, s * 0.6f, fill); fill.shader = null })
        val themes = b.themes.toMutableList(); if (pk.id == "event_boost" && ev > 0) Data.THEMES.indices.filter { Data.THEMES[it].event == ev }.forEach { themes.add(it) }
        for (t0 in themes) tiles.add(Data.THEMES[t0].name to { x, y, s -> val th = Data.THEMES[t0]
            fill.shader = LinearGradient(0f, y - s * 0.7f, 0f, y + s * 0.7f, intArrayOf(th.skyTop, th.skyBottom, th.groundFar, th.groundNear), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(x - s * 0.75f, y - s * 0.6f, x + s * 0.75f, y + s * 0.6f, s * 0.2f, s * 0.2f, fill); fill.shader = null })
        val decor = b.decor.toMutableList(); if (pk.id == "event_boost" && ev > 0) Data.DECOR.indices.filter { Data.DECOR[it].event == ev }.forEach { decor.add(it) }
        for (d in decor) tiles.add((if (Data.DECOR[d].special > 0) "★ " else "") + Data.DECOR[d].name to { x, y, s -> c.save(); c.clipRect(x - s, y - s, x + s, y + s); drawDecor(c, d, x, y + s * 0.75f, s / (40f * dp) * 0.55f); c.restore() })
        for (a in b.acc) tiles.add((if (Data.ACCESSORIES[a].special > 0) "★ " else "") + Data.ACCESSORIES[a].name to { x, y, s -> drawAccessoryIcon(c, a, x, y, s * 0.55f) })
        for (p in b.pets) tiles.add("★ " + Data.PETS[p].name to { x, y, s -> drawPet(c, p, x, y + s * 0.4f, s * 0.9f, t * 8f, 1f, false) })
        if (pk.id == "event_boost" && ev > 0) tiles.add("10× event ingredients" to { x, y, s -> Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event == ev }.forEachIndexed { k, ing -> drawIngredient(c, ing, x + (k - 0.5f) * s * 0.7f, y, s * 0.3f) } })
        if (b.foods.isNotEmpty()) tiles.add("Treats" to { x, y, s -> drawFood(c, b.foods[0], x, y, s * 0.4f) })
        val cols = 4
        val tw = r.width() / cols
        val th = min(tw * 0.95f, r.height() / max(1, (tiles.size + cols - 1) / cols))
        for ((k, tile) in tiles.withIndex()) {
            val tx = r.left + (k % cols) * tw; val ty = r.top + (k / cols) * th
            fill.color = Color.argb(45, 255, 255, 255); c.drawRoundRect(tx + 3f * dp, ty + 3f * dp, tx + tw - 3f * dp, ty + th - 3f * dp, 12f * dp, 12f * dp, fill)
            tile.second(tx + tw / 2, ty + th * 0.42f, min(tw, th) * 0.3f)
            text.textSize = 9.5f * dp; text.color = if (tile.first.startsWith("★")) DUST else Color.WHITE
            val label = tile.first
            text.textSize = 9.5f * dp
            while (text.textSize > 7f * dp && text.measureText(label) > tw - 8f * dp) text.textSize -= 0.5f * dp
            c.drawText(label, tx + tw / 2, ty + th - 9f * dp, text)
            text.color = Color.WHITE
        }
        text.textSize = 10.5f * dp; text.color = TEXT_DIM
        c.drawText("★ = only from this pack", r.centerX(), r.bottom + 2f * dp, text); text.color = Color.WHITE
    }

    // ---------- Pack offers: shown once, then a 30-minute button on the garden ----------

    private var offerPack = -1
    private var sessionStart = System.currentTimeMillis()
    private val OFFER_MS = 30 * 60_000L

    private fun offerKey(pk: Pack) = "offer_" + pk.id + (if (pk.eventOnly) "_" + eventNow() + "_" + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) else "")

    private fun showOffer(i: Int) {
        val pk = Data.PACKS[i]
        if (lab.packOwned(pk) || offerPack >= 0 || popup != null || tutStep >= 0 || qaMode) return
        val key = offerKey(pk)
        if (prefs.getBoolean(key + "_shown", false)) return
        prefs.edit().putBoolean(key + "_shown", true).apply()
        offerPack = i
        sound.play(Fx.CHIRP, 0.6f, 1.2f)
    }

    private fun closeOffer() {
        val i = offerPack; offerPack = -1
        if (i < 0) return
        val key = offerKey(Data.PACKS[i])
        if (prefs.getLong(key + "_until", 0L) == 0L) prefs.edit().putLong(key + "_until", System.currentTimeMillis() + OFFER_MS).apply()
    }

    /** Offers appear at friendly moments, each only once. */
    private fun checkOffers() {
        if (tab != Tab.GARDEN || offerPack >= 0 || popup != null || tutStep >= 0 || settingsOpen) return
        val now = System.currentTimeMillis()
        val starter = Data.PACKS.indexOfFirst { it.id == "starter" }
        val boost = Data.PACKS.indexOfFirst { it.id == "event_boost" }
        if (eventNow() > 0 && lab.brewed >= 2 && now - sessionStart > 60_000L) showOffer(boost)
        if (offerPack < 0 && lab.brewed >= 3 && now - sessionStart > 120_000L) showOffer(starter)
    }

    /** Called whenever something costs more dust than you have. */
    private fun notEnoughDust() {
        toast("Not enough spore dust ✦", ACCENT)
        sound.play(Fx.DENIED)
        if (lab.brewed >= 3) showOffer(Data.PACKS.indexOfFirst { it.id == "spores_medium" })
    }

    private fun offerCard(w: Float, h: Float) = RectF(24f * dp, h * 0.13f, w - 24f * dp, h * 0.13f + 510f * dp)

    private fun drawOffer(c: Canvas, w: Float, h: Float) {
        val pk = Data.PACKS[offerPack]
        val card = offerCard(w, h)
        val k = easeOutBack(min(1f, (System.currentTimeMillis() % 100000L) / 1000f))
        panel(c, card, 26f * dp, PANEL_LIGHT)
        // rays behind the gift
        c.save(); c.translate(w / 2, card.top + 120f * dp); c.rotate(t * 15f)
        fill.color = Color.argb(45, Color.red(pk.color), Color.green(pk.color), Color.blue(pk.color))
        for (r in 0 until 10) { c.rotate(36f); path.reset(); path.moveTo(0f, 0f); path.lineTo(-14f * dp, -120f * dp); path.lineTo(14f * dp, -120f * dp); path.close(); c.drawPath(path, fill) }
        c.restore()
        drawGiftBox(c, w / 2, card.top + 160f * dp + sin(t * 3f) * 4f * dp, 90f * dp, pk.color)
        text.textSize = 13f * dp; text.color = DUST
        c.drawText(if (pk.eventOnly) "${Data.EVENT_ICONS[eventNow()]} Limited event offer" else if (pk.oneTime) "✨ One-time offer" else "✨ Special offer", w / 2, card.top + 34f * dp, text)
        text.textSize = 26f * dp; text.color = Color.WHITE
        c.drawText(pk.name, w / 2, card.top + 210f * dp, text)
        text.textSize = 13f * dp; text.color = Color.argb(225, 255, 255, 255)
        for ((i, l) in wrap(pk.desc, card.width() - 48f * dp).take(4).withIndex()) c.drawText(l, w / 2, card.top + 238f * dp + i * 18f * dp, text)
        text.color = Color.WHITE
    }

    /** Small buttons on the right edge of the garden for offers you closed, for 30 minutes. */
    private fun offerSideButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        var y = h * 0.27f
        for ((i, pk) in Data.PACKS.withIndex()) {
            if (pk.eventOnly && eventNow() == 0) continue
            if (lab.packOwned(pk)) continue
            val until = prefs.getLong(offerKey(pk) + "_until", 0L)
            if (until <= now) continue
            val left = until - now
            list.add(Button("offerside$i", RectF(w - 62f * dp, y, w - 6f * dp, y + 62f * dp), "", STYLE_GHOST, custom = { c, rr ->
                fill.color = Color.argb(200, 30, 20, 60); c.drawRoundRect(rr, 14f * dp, 14f * dp, fill)
                stroke.color = pk.color; stroke.strokeWidth = 2f * dp; c.drawRoundRect(rr, 14f * dp, 14f * dp, stroke)
                drawGiftBox(c, rr.centerX(), rr.top + 36f * dp + sin(t * 4f) * 2f * dp, 28f * dp, pk.color)
                text.textSize = 10f * dp; text.color = DUST
                c.drawText("%d:%02d".format(left / 60000, (left / 1000) % 60), rr.centerX(), rr.bottom - 6f * dp, text)
                text.color = Color.WHITE
            }) { offerPack = i })
            y += 70f * dp
        }
    }

    // ---------- The traveling merchant ----------

    private var merchantOpen = false
    private fun merchantHere() = Data.merchantVisits(LabState.dayKey(), LabState.hour())
    private fun merchantCard(w: Float, h: Float) = RectF(18f * dp, h * 0.1f, w - 18f * dp, h * 0.1f + 120f * dp + 4 * 70f * dp + 80f * dp)

    /** A friendly beetle with a big backpack and a lantern. */
    private fun drawMerchant(c: Canvas, x: Float, y: Float, s: Float) {
        groundShadow(c, x, y, s * 0.5f, 90)
        val bob = abs(sin(t * 2f)) * s * 0.03f
        // backpack
        fill.color = Color.rgb(170, 110, 60); c.drawRoundRect(x - s * 0.5f, y - s * 1.1f - bob, x + s * 0.05f, y - s * 0.35f - bob, s * 0.1f, s * 0.1f, fill)
        fill.color = Color.rgb(120, 75, 40); c.drawRect(x - s * 0.5f, y - s * 0.85f - bob, x + s * 0.05f, y - s * 0.78f - bob, fill)
        fill.color = Color.rgb(230, 80, 90); c.drawCircle(x - s * 0.36f, y - s * 1.16f - bob, s * 0.1f, fill)
        fill.color = Color.rgb(120, 200, 120); c.drawCircle(x - s * 0.16f, y - s * 1.2f - bob, s * 0.09f, fill)
        // six little legs, stepping in place
        stroke.color = Color.rgb(30, 70, 65); stroke.strokeWidth = s * 0.045f
        for ((k, lx) in floatArrayOf(-0.2f, 0f, 0.2f).withIndex()) {
            val step = sin(t * 5f + k * 2f) * s * 0.03f
            for (side in intArrayOf(-1, 1)) {
                val hx = x + lx * s + side * s * 0.05f
                path.reset(); path.moveTo(hx, y - s * 0.25f)
                path.lineTo(hx + side * s * 0.12f, y - s * 0.14f + step)
                path.lineTo(hx + side * s * 0.16f, y - (if (side < 0) step else -step).coerceAtLeast(0f))
                c.drawPath(path, stroke)
            }
        }
        // beetle body
        fill.shader = RadialGradient(x - s * 0.05f, y - s * 0.6f - bob, s * 0.5f, Color.rgb(100, 210, 170), Color.rgb(30, 110, 100), Shader.TileMode.CLAMP)
        c.drawOval(x - s * 0.3f, y - s * 0.85f - bob, x + s * 0.32f, y - s * 0.05f, fill); fill.shader = null
        stroke.color = Color.rgb(20, 80, 70); stroke.strokeWidth = s * 0.03f
        c.drawLine(x + 0.01f * s, y - s * 0.8f - bob, x + 0.01f * s, y - s * 0.1f, stroke)
        // head with a little hat
        fill.color = Color.rgb(40, 60, 70); c.drawCircle(x + s * 0.12f, y - s * 0.9f - bob, s * 0.2f, fill)
        fill.color = Color.WHITE; c.drawCircle(x + s * 0.06f, y - s * 0.94f - bob, s * 0.06f, fill); c.drawCircle(x + s * 0.2f, y - s * 0.94f - bob, s * 0.06f, fill)
        fill.color = Color.rgb(30, 25, 40); c.drawCircle(x + s * 0.07f, y - s * 0.93f - bob, s * 0.03f, fill); c.drawCircle(x + s * 0.21f, y - s * 0.93f - bob, s * 0.03f, fill)
        stroke.color = Color.rgb(40, 60, 70); stroke.strokeWidth = s * 0.03f
        c.drawLine(x + s * 0.06f, y - s * 1.06f - bob, x - s * 0.02f, y - s * 1.25f - bob, stroke)
        c.drawLine(x + s * 0.2f, y - s * 1.06f - bob, x + s * 0.28f, y - s * 1.25f - bob, stroke)
        // two arms: one waves, one holds the lantern
        stroke.color = Color.rgb(30, 70, 65); stroke.strokeWidth = s * 0.05f
        c.drawLine(x + s * 0.24f, y - s * 0.6f - bob, x + s * 0.42f, y - s * 0.74f, stroke)
        val wave = sin(t * 6f) * s * 0.06f
        path.reset(); path.moveTo(x - s * 0.22f, y - s * 0.62f - bob); path.lineTo(x - s * 0.36f, y - s * 0.78f - bob); path.lineTo(x - s * 0.3f + wave, y - s * 0.95f - bob)
        c.drawPath(path, stroke)
        fill.color = Color.rgb(30, 70, 65); c.drawCircle(x - s * 0.3f + wave, y - s * 0.97f - bob, s * 0.04f, fill)
        // lantern
        stroke.color = Color.rgb(90, 70, 50); stroke.strokeWidth = s * 0.03f; c.drawLine(x + s * 0.42f, y - s * 0.74f, x + s * 0.48f, y - s * 0.76f, stroke)
        fill.shader = RadialGradient(x + s * 0.48f, y - s * 0.66f, s * 0.3f, Color.argb(160, 255, 220, 120), Color.argb(0, 255, 220, 120), Shader.TileMode.CLAMP)
        c.drawCircle(x + s * 0.48f, y - s * 0.66f, s * 0.3f, fill); fill.shader = null
        fill.color = Color.rgb(255, 220, 120); c.drawRoundRect(x + s * 0.42f, y - s * 0.75f, x + s * 0.54f, y - s * 0.58f, s * 0.03f, s * 0.03f, fill)
        // "!" bubble so you notice
        text.textSize = s * 0.32f; text.color = DUST
        c.drawText("!", x + s * 0.12f, y - s * 1.35f - abs(sin(t * 3f)) * s * 0.08f, text); text.color = Color.WHITE
    }

    private fun drawMerchantPanel(c: Canvas, w: Float, h: Float) {
        val card = merchantCard(w, h)
        panel(c, card, 24f * dp)
        drawMerchant(c, card.left + 56f * dp, card.top + 104f * dp, 70f * dp)
        text.textAlign = Paint.Align.LEFT; text.textSize = 20f * dp
        c.drawText("Bartleby the Beetle", card.left + 104f * dp, card.top + 40f * dp, text)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        for ((k, l) in wrap("\"Psst! Rare goodies from far-off forests. Only today!\"", card.width() - 124f * dp).withIndex())
            c.drawText(l, card.left + 104f * dp, card.top + 62f * dp + k * 16f * dp, text)
        text.color = Color.WHITE
        val day = LabState.dayKey()
        val rh = 64f * dp
        for ((k, o) in Data.merchantOffers(day).withIndex()) {
            val y = card.top + 120f * dp + k * (rh + 6f * dp)
            fill.color = PANEL_LIGHT; c.drawRoundRect(card.left + 14f * dp, y, card.right - 14f * dp, y + rh, 14f * dp, 14f * dp, fill)
            val ix = card.left + 40f * dp; val iy = y + rh / 2
            val (name, sub) = when (o.kind) {
                0 -> { drawIngredient(c, o.item, ix, iy, 12f * dp); Pair("${o.qty}× ${Data.INGREDIENTS[o.item].name}", "A bundle at a bargain") }
                1 -> { drawSeedPacket(c, o.item, ix, iy, 14f * dp); Pair("${o.qty}× ${Data.SEEDS[o.item].name}", "Plant in Lab → GROW") }
                2 -> { drawAccessoryIcon(c, o.item, ix, iy + 4f * dp, 22f * dp); Pair(Data.ACCESSORIES[o.item].name, "Only sold by Bartleby!") }
                else -> { drawMysteryEgg(c, ix, iy + 10f * dp, 22f * dp); Pair("Mystery Egg", "What will hatch? 🥚") }
            }
            text.textSize = 14f * dp; c.drawText(name, card.left + 66f * dp, iy - 2f * dp, text)
            text.textSize = 11f * dp; text.color = TEXT_DIM; c.drawText(sub, card.left + 66f * dp, iy + 14f * dp, text); text.color = Color.WHITE
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawMysteryEgg(c: Canvas, x: Float, y: Float, s: Float) {
        c.save(); c.rotate(sin(t * 6f) * 6f, x, y)
        fill.color = Color.rgb(250, 240, 225); c.drawOval(x - s * 0.4f, y - s * 1.0f, x + s * 0.4f, y, fill)
        fill.color = Color.rgb(255, 160, 200); c.drawCircle(x - s * 0.15f, y - s * 0.6f, s * 0.08f, fill)
        fill.color = Color.rgb(140, 200, 255); c.drawCircle(x + s * 0.15f, y - s * 0.35f, s * 0.07f, fill)
        fill.color = Color.rgb(255, 220, 110); c.drawCircle(x + s * 0.1f, y - s * 0.78f, s * 0.06f, fill)
        c.restore()
    }

    // ---------- Seasons & weather play ----------

    private class Snowman(val gx: Float, val gz: Float, var prog: Float = 0f)
    private val snowmen = ArrayList<Snowman>()
    private val PUDDLES = listOf(0.25f to 0.55f, 0.72f to 0.35f, 0.55f to 0.85f, 1.2f to 0.6f, 1.75f to 0.4f)
    private val LEAF_PILES = listOf(0.15f to 0.3f, 0.82f to 0.62f, 0.4f to 0.92f, 1.3f to 0.3f, 1.85f to 0.75f)
    private val pileKick = FloatArray(5)

    private fun snowy() = area == 0 && !LabState.raining() && (Data.season() == 3 || eventNow() == 2)

    private fun drawSeasonGround(c: Canvas, w: Float) {
        if (!snowy()) snowmen.clear()
        val season = Data.season()
        if (LabState.raining()) for ((px, pz) in PUDDLES) {
            val (x, y) = toScreen(px, pz); val d = depth(pz)
            fill.color = Color.argb(150, 110, 150, 210); c.drawOval(x - 34f * dp * d, y - 9f * dp * d, x + 34f * dp * d, y + 9f * dp * d, fill)
            fill.color = Color.argb(90, 220, 235, 255); c.drawOval(x - 20f * dp * d, y - 5f * dp * d, x + 4f * dp * d, y - 1f * dp * d, fill)
        }
        when (season) {
            3 -> { // a dusting of frost
                fill.shader = LinearGradient(0f, groundTop(), 0f, groundBottom() + 40f * dp, Color.argb(70, 240, 248, 255), Color.argb(25, 240, 248, 255), Shader.TileMode.CLAMP)
                c.drawRect(0f, groundTop(), w, navTop, fill); fill.shader = null
            }
            2 -> for ((k, pile) in LEAF_PILES.withIndex()) { // crunchy leaf piles
                val (x, y) = toScreen(pile.first, pile.second); val d = depth(pile.second)
                val cols = intArrayOf(Color.rgb(220, 110, 40), Color.rgb(240, 170, 50), Color.rgb(190, 80, 40), Color.rgb(250, 200, 80))
                val r = Random(k * 13)
                for (j in 0 until 14) {
                    fill.color = cols[j % 4]
                    val lx = x + (r.nextFloat() - 0.5f) * 50f * dp * d; val ly = y - r.nextFloat() * 12f * dp * d
                    c.save(); c.rotate(r.nextFloat() * 180f, lx, ly)
                    c.drawOval(lx - 6f * dp * d, ly - 3f * dp * d, lx + 6f * dp * d, ly + 3f * dp * d, fill); c.restore()
                }
            }
            0 -> { // little spring flowers dotted evenly through the grass
                val r = Random(5)
                for (k in 0 until 32) {
                    val fgx = ((k % 8) + 0.2f + r.nextFloat() * 0.6f) / 8f * LabState.WORLD
                    val fgz = ((k / 8) + 0.2f + r.nextFloat() * 0.6f) / 4f
                    if (abs(fgx - camX) > 0.62f) continue
                    val (x, y) = toScreen(fgx, fgz)
                    fill.color = if (k % 3 == 0) Color.rgb(255, 240, 120) else if (k % 3 == 1) Color.rgb(255, 170, 210) else Color.WHITE
                    for (p in 0 until 5) { val a = p * 1.2566f; c.drawCircle(x + cos(a) * 2.6f * dp, y + sin(a) * 2.6f * dp, 2f * dp, fill) }
                    fill.color = Color.rgb(255, 200, 80); c.drawCircle(x, y, 1.4f * dp, fill)
                }
            }
        }
    }

    /** Season effects in the air, plus little reactions from the shrooms. */
    private fun drawSeasonAir(c: Canvas, w: Float) {
        val hz = horizon(); val bottom = navTop
        when (Data.season()) {
            0 -> if (lab.theme != 6) for (k in 0 until 8) {   // drifting blossoms
                val p = (t * 0.06f * (1 + k % 3) + k * 0.17f) % 1f
                val x = (k * 0.13f * w + sin(t + k) * 26f * dp + p * 40f * dp) % w
                val y = hz * 0.4f + p * (bottom - hz * 0.4f)
                c.save(); c.rotate(t * 70f + k * 50f, x, y)
                fill.color = if (k % 2 == 0) Color.rgb(255, 190, 215) else Color.WHITE
                c.drawOval(x - 4f * dp, y - 2f * dp, x + 4f * dp, y + 2f * dp, fill); c.restore()
            }
            1 -> { // summer: sunbeams by day, extra fireflies at dusk
                val dark = LabState.darkness()
                if (dark < 0.3f && !LabState.raining()) {
                    fill.color = Color.argb(18, 255, 240, 180)
                    for (k in 0..2) { path.reset(); val x0 = w * (0.2f + k * 0.3f) + sin(t * 0.3f + k) * 20f * dp
                        path.moveTo(x0, 0f); path.lineTo(x0 + 40f * dp, 0f); path.lineTo(x0 + 120f * dp, groundBottom()); path.lineTo(x0 + 40f * dp, groundBottom()); path.close(); c.drawPath(path, fill) }
                } else if (dark > 0.3f) for (k in 0 until 10) {
                    val fx = w * ((k * 0.191f + t * 0.015f * (1 + k % 2)) % 1f)
                    val fy = hz + (groundBottom() - hz) * (0.3f + 0.6f * ((k * 0.37f) % 1f)) + sin(t * 1.7f + k) * 12f * dp
                    val a = 0.5f + 0.5f * sin(t * 4f + k * 2.1f)
                    fill.color = Color.argb((80 * a * dark).toInt(), 255, 250, 150); c.drawCircle(fx, fy, 8f * dp, fill)
                    fill.color = Color.argb((255 * a * dark).toInt(), 255, 250, 170); c.drawCircle(fx, fy, 2f * dp, fill)
                }
            }
            2 -> if (lab.theme != 2) for (k in 0 until 8) {   // falling leaves
                val p = (t * 0.07f * (1 + k % 3) + k * 0.19f) % 1f
                val x = (k * 0.12f * w + sin(t + k) * 30f * dp + p * 50f * dp) % w
                val y = hz * 0.3f + p * (bottom - hz * 0.3f)
                c.save(); c.rotate(t * 90f + k * 40f, x, y)
                fill.color = if (k % 2 == 0) Color.rgb(230, 120, 40) else Color.rgb(250, 190, 60)
                c.drawOval(x - 5f * dp, y - 2.5f * dp, x + 5f * dp, y + 2.5f * dp, fill); c.restore()
            }
            else -> if (lab.theme != 4 && !LabState.raining()) for (k in 0 until 22) {   // light snowfall
                val p = (t * 0.045f * (1 + k % 4) + k * 0.083f) % 1f
                val x = (k * 0.045f * w + sin(t * 0.7f + k) * 12f * dp) % w
                fill.color = Color.argb(200, 255, 255, 255)
                c.drawCircle(x, p * bottom, (1.3f + k % 3) * dp, fill)
            }
        }
    }

    private fun updateSeason(dt: Float) {
        if (tab != Tab.GARDEN || area != 0) return
        val season = Data.season()
        for (k in pileKick.indices) pileKick[k] = max(0f, pileKick[k] - dt)
        for (s in local()) {
            if (season == 3 && !s.sleeping && Random.nextFloat() < dt * 0.25f) {   // frosty breath
                val (x, y) = shroomScreen(s); val size = shroomSize(s)
                particles.add(Particle(x + s.facing * size * 0.25f, y - size * 0.32f, s.facing * 18f * dp, -10f * dp, 0.9f, 0.9f, Color.argb(200, 255, 255, 255), P_BUBBLE))
            }
            if (season == 2 && s.moving) for ((k, pile) in LEAF_PILES.withIndex()) {
                if (pileKick[k] > 0f || hypot(s.gx - pile.first, (s.gz - pile.second) * 1.6f) > 0.06f) continue
                pileKick[k] = 2.5f
                val (x, y) = toScreen(pile.first, pile.second)
                for (j in 0 until 14) { val a = -1.57f + (Random.nextFloat() - 0.5f) * 2.6f
                    particles.add(Particle(x, y - 6f * dp, cos(a) * 90f * dp, sin(a) * 150f * dp, 0.9f, 0.9f, listOf(Color.rgb(230, 120, 40), Color.rgb(250, 190, 60)).random(), P_SPARK)) }
                sound.play(Fx.STIR, 0.3f, 1.4f); express(s, E_GIGGLE, 1.2f)
            }
        }
    }

    private fun drawSnowman(c: Canvas, x: Float, y: Float, s: Float, prog: Float) {
        groundShadow(c, x, y, s * 0.4f * max(0.3f, prog), 60)
        fill.color = Color.rgb(248, 250, 255)
        val b1 = s * 0.32f * min(1f, prog / 0.4f)
        if (b1 > 0f) c.drawCircle(x, y - b1, b1, fill)
        if (prog > 0.4f) { val b2 = s * 0.23f * min(1f, (prog - 0.4f) / 0.3f); c.drawCircle(x, y - s * 0.6f - b2 * 0.6f, b2, fill) }
        if (prog > 0.7f) {
            val b3 = s * 0.16f * min(1f, (prog - 0.7f) / 0.2f); val hy = y - s * 0.98f
            c.drawCircle(x, hy, b3, fill)
            if (prog >= 0.95f) {
                fill.color = Color.rgb(40, 35, 50); c.drawCircle(x - s * 0.05f, hy - s * 0.03f, s * 0.025f, fill); c.drawCircle(x + s * 0.05f, hy - s * 0.03f, s * 0.025f, fill)
                fill.color = Color.rgb(255, 140, 40)
                path.reset(); path.moveTo(x, hy); path.lineTo(x + s * 0.14f, hy + s * 0.02f); path.lineTo(x, hy + s * 0.04f); path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(220, 60, 70); c.drawRoundRect(x - s * 0.16f, hy + s * 0.1f, x + s * 0.16f, hy + s * 0.15f, s * 0.03f, s * 0.03f, fill)
            }
        }
    }

    // ---------- Dreams & thoughts ----------

    private fun drawDream(c: Canvas, s: Shroom, x: Float, y: Float, size: Float) {
        val bx = x + size * 0.55f; val by = y - size * 1.3f + sin(t * 1.5f + s.id) * 3f * dp
        val r = 15f * dp
        fill.color = Color.argb(215, 255, 255, 255)
        c.drawCircle(x + size * 0.28f, y - size * 0.92f, 2.5f * dp, fill)
        c.drawCircle(x + size * 0.4f, y - size * 1.06f, 4f * dp, fill)
        for ((dx, dy) in arrayOf(-0.6f to 0f, 0f to -0.35f, 0.6f to 0f, 0f to 0.25f, 0f to 0f)) c.drawCircle(bx + dx * r, by + dy * r, r * 0.75f, fill)
        val bestie = lab.garden.firstOrNull { it.id == s.bestie }
        val kind = ((t / 4f).toInt() + s.id.toInt()) % (if (bestie != null) 4 else 3)
        when (kind) {
            0 -> drawFood(c, Data.SPECIES[s.species].favFood, bx, by, r * 0.45f)
            1 -> { fill.color = Color.rgb(255, 120, 160); drawHeart(c, bx, by - r * 0.1f, r * 0.32f) }
            2 -> { fill.color = Color.rgb(255, 210, 90); drawSparkle(c, bx, by, r * 0.35f); fill.color = Color.rgb(170, 190, 255); drawSparkle(c, bx + r * 0.4f, by - r * 0.3f, r * 0.15f) }
            else -> bestie?.let { drawShroom(c, it.species, bx, by + r * 0.45f, r * 0.9f, 0, 0f, false, false) }
        }
    }

    private fun thoughtFor(s: Shroom): String {
        val opts = ArrayList<String>()
        val bestie = lab.garden.firstOrNull { it.id == s.bestie }
        when {
            s.hunger < 30f -> opts.addAll(listOf("My tummy is rumbling...", "Is it snack time?"))
            s.happy < 35f -> opts.addAll(listOf("Could I get a hug?", "I missed you!"))
        }
        if (bestie != null) opts.add("Where's ${bestie.name}? 💕")
        if (LabState.raining()) opts.addAll(listOf("Puddles!!", "I love the sound of rain"))
        if (LabState.darkness() > 0.6f) opts.addAll(listOf("The stars are so pretty", "I'm a little sleepy..."))
        opts.add(when (Data.season()) { 0 -> "Everything smells like flowers!"; 1 -> "It's so sunny today!"; 2 -> "Crunchy leaves are the best"; else -> "Brr! My cap is chilly" })
        opts.add(arrayOf("Five more minutes...", "Boing boing boing!", "Oh! You startled me...", "I could eat a whole cake", "What's over there?", "What a lovely day!")[s.trait])
        opts.addAll(listOf("Is that a cloud or a sheep?", "I wonder what the professor is brewing", "Hehe, that tickles!", "I'm growing, right?"))
        if (s.pet >= 0) opts.add("${Data.PETS[s.pet].name} is my best buddy")
        return opts.random()
    }

    private fun drawThought(c: Canvas, s: Shroom, x: Float, top: Float) {
        text.textSize = 11.5f * dp
        val lines = wrap(s.thought, 130f * dp)
        val bw = (lines.maxOfOrNull { text.measureText(it) } ?: 0f) + 18f * dp
        val bh = lines.size * 14f * dp + 12f * dp
        val bx = (x - bw / 2).coerceIn(6f * dp, width - bw - 6f * dp); val by = top - bh - 8f * dp
        val a = min(1f, s.thoughtT / 0.3f)
        fill.color = Color.argb((235 * a).toInt(), 255, 255, 255)
        c.drawRoundRect(bx, by, bx + bw, by + bh, 12f * dp, 12f * dp, fill)
        c.drawCircle(x - 4f * dp, top - 4f * dp, 3.5f * dp, fill); c.drawCircle(x - 1f * dp, top + 3f * dp, 2f * dp, fill)
        text.color = Color.argb((255 * a).toInt(), 45, 30, 70)
        for ((k, l) in lines.withIndex()) c.drawText(l, bx + bw / 2, by + 16f * dp + k * 14f * dp, text)
        text.color = Color.WHITE
    }

    // ---------- Glow Cave ----------

    private fun drawCaveScene(c: Canvas, w: Float, h: Float) {
        val saved = lab.theme
        lab.theme = 3
        drawGardenScene(c, w, h)
        lab.theme = saved
        // glowing spores drifting up through the cave
        for (k in 0 until 26) {
            val f = (t * (0.03f + (k % 4) * 0.01f) + k * 0.173f) % 1f
            val x = w * ((k * 0.137f) % 1f) + sin(t + k) * 10f * dp
            val y = groundBottom() - f * (groundBottom() - horizon() * 0.3f)
            val col = if (k % 3 == 0) Color.rgb(190, 140, 255) else Color.rgb(120, 255, 220)
            val a = (sin(f * 3.14f) * 200).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a / 3, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(x, y, 6f * dp, fill)
            fill.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(x, y, 1.8f * dp, fill)
        }
    }

    // ---------- Best friends, bedtime, the garden band ----------

    private fun drawBestieHearts(c: Canvas) {
        val here = local()
        for (a in here) {
            if (a.bestie < 0 || a.sleeping) continue
            val b = here.firstOrNull { it.id == a.bestie } ?: continue
            if (a.id > b.id || hypot(a.gx - b.gx, (a.gz - b.gz) * 1.6f) > 0.17f) continue
            val (ax, ay) = shroomScreen(a); val (bx, by) = shroomScreen(b)
            val hy = min(ay - shroomSize(a), by - shroomSize(b)) - 14f * dp + sin(t * 3f) * 3f * dp
            fill.color = Color.argb((170 + 60 * sin(t * 4f)).toInt(), 255, 110, 160)
            drawHeart(c, (ax + bx) / 2, hy, 5f * dp)
        }
    }

    private fun bedtime() {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 7) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 7); cal.set(java.util.Calendar.MINUTE, 0)
        lab.tuckedUntil = cal.timeInMillis
        lab.usedBedtime = true
        for (s in lab.home()) {
            if (s.held) continue
            s.act = ACT_SLEEP; s.actTarget = -1; s.pendingAct = 0; s.actTimer = 900f; s.sleeping = true; s.awakeUntil = 0L
        }
        sound.play(Fx.YAWN, 0.7f)
        for (s in local()) { val (x, y) = shroomScreen(s); burst(x, y - shroomSize(s), Color.rgb(200, 210, 255), 3, P_Z) }
        toast("Sweet dreams! 🌙 Tucked in, they grow 30% faster until morning", MINT)
        lab.addDiary("Tucked everyone in for the night. 🌙")
        markDirty()
    }

    /** A big soft leaf tucked over a mushroom napping in the moss nest. */
    private fun drawLeafBlanket(c: Canvas, x: Float, y: Float, size: Float) {
        val top = y - size * 0.32f
        path.reset()
        path.moveTo(x - size * 0.62f, y + size * 0.02f)
        path.quadTo(x - size * 0.5f, top - size * 0.1f, x + size * 0.15f, top)
        path.quadTo(x + size * 0.7f, top + size * 0.05f, x + size * 0.66f, y + size * 0.02f)
        path.close()
        fill.shader = LinearGradient(x, top, x, y, Color.rgb(140, 210, 110), Color.rgb(90, 160, 80), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Color.rgb(80, 140, 70); stroke.strokeWidth = size * 0.025f
        c.drawLine(x - size * 0.5f, y - size * 0.06f, x + size * 0.55f, top + size * 0.12f, stroke)
        for (k in 0..3) { val px = x - size * 0.3f + k * size * 0.24f; val py = y - size * 0.1f - k * size * 0.04f
            c.drawLine(px, py, px + size * 0.08f, py - size * 0.12f, stroke) }
    }

    /** A cozy patchwork blanket over a sleeping, tucked-in mushroom. */
    private fun drawBlanket(c: Canvas, x: Float, y: Float, size: Float, id: Long) {
        val cols = intArrayOf(Color.rgb(255, 150, 180), Color.rgb(150, 190, 255), Color.rgb(170, 220, 150), Color.rgb(255, 210, 120), Color.rgb(200, 160, 255))
        val col = cols[(id % cols.size).toInt()]
        val bw = size * 0.62f; val top = y - size * 0.34f
        path.reset()
        path.moveTo(x - bw, y + size * 0.04f)
        path.quadTo(x - bw * 1.05f, top + size * 0.05f, x - bw * 0.6f, top)
        path.lineTo(x + bw * 0.6f, top)
        path.quadTo(x + bw * 1.05f, top + size * 0.05f, x + bw, y + size * 0.04f)
        path.close()
        fill.color = col; c.drawPath(path, fill)
        c.save(); c.clipPath(path)
        fill.color = shade(col, 0.25f)
        val sq = size * 0.16f
        var row = 0
        var yy = top
        while (yy < y + size * 0.05f) {
            var xx = x - bw + (if (row % 2 == 0) 0f else sq)
            while (xx < x + bw) { c.drawRect(xx, yy, xx + sq, yy + sq, fill); xx += sq * 2 }
            yy += sq; row++
        }
        c.restore()
        fill.color = Color.rgb(255, 250, 245)
        c.drawRoundRect(x - bw * 0.65f, top - size * 0.03f, x + bw * 0.65f, top + size * 0.05f, size * 0.04f, size * 0.04f, fill)
    }

    // The band: every jam lasts a little while; members play their part on each beat
    private var jamT = 0f
    private var jamBeat = 0f
    private var jamStep = 0
    private val jamInst = HashMap<Long, Int>()
    private val JAM_MELODY = intArrayOf(0, 2, 4, 7, 4, 2, 0, -1, 4, 7, 9, 7, 4, 2, 4, -1, 0, 2, 4, 2, 0, -3, 0, -1)

    private fun startJam() {
        val band = local().filter { !it.sleeping && !it.held && it.act != ACT_INSIDE && !it.airborne() }.take(5)
        if (band.size < 2) return
        jamT = 18f; jamBeat = 0f; jamStep = 0; jamInst.clear()
        val n = band.size
        for ((k, s) in band.withIndex()) {
            jamInst[s.id] = k
            s.act = 0; s.pendingAct = ACT_JAM; s.actTarget = -1; s.idle = 0f; s.sleeping = false
            val off = k - (n - 1) / 2f
            s.tx = (camX + off * 0.2f).coerceIn(0.08f, LabState.WORLD - 0.08f); s.tz = (0.42f + abs(off) * 0.07f).coerceIn(0f, 1f)
        }
        sound.play(Fx.CLINK, 0.6f)
        toast("🎵 The garden band is getting together!", MINT)
    }

    private fun updateJam(dt: Float) {
        if (jamT <= 0f) return
        jamT -= dt
        val band = local().filter { it.act == ACT_JAM }
        val mid = band.map { it.gx }.average().toFloat()
        for (s in band) { s.facing = if (s.gx < mid) 1f else -1f; s.actTimer = max(s.actTimer, 0.5f) }
        if (jamT <= 0f) {
            for (s in local().filter { it.act == ACT_JAM || it.pendingAct == ACT_JAM }) {
                if (s.act == ACT_JAM) endAct(s)
                s.pendingAct = 0
                s.happy = min(100f, s.happy + 15f)
                express(s, E_BLISS, 2f)
            }
            jamInst.clear()
            lab.jammed = true
            toast("What a jam! Everyone's happier 🎵", MINT)
            val key = "jam-" + LabState.dayKey()
            if (chatted.add(key)) lab.addDiary("The garden band played a little song together. 🎵")
            markDirty()
            return
        }
        if (band.isEmpty()) return
        jamBeat += dt
        if (jamBeat < 0.28f) return
        jamBeat -= 0.28f
        val step = jamStep++
        val note = JAM_MELODY[step % JAM_MELODY.size]
        fun ratio(semi: Int) = Math.pow(2.0, semi / 12.0).toFloat()
        for (s in band) {
            val (x, y) = shroomScreen(s)
            val hit = when (jamInst[s.id] ?: 0) {
                0 -> when (step % 4) { 0 -> { sound.play(Fx.BOING, 0.5f, 0.55f); true }; 2 -> { sound.play(Fx.CLICK, 0.45f, 0.7f); true }; else -> false }
                1 -> if (note >= 0 || note == -3) { sound.play(Fx.POP, 0.45f, ratio(if (note == -3) -3 else note)); true } else false
                2 -> if (step % 2 == 0 && note >= 0) { sound.play(Fx.CHIRP, 0.3f, 0.85f * ratio(note)); true } else false
                3 -> { sound.play(Fx.CLICK, 0.18f, 1.7f); step % 2 == 0 }
                else -> if (step % 8 == 0) { sound.play(Fx.CLINK, 0.35f, 1.2f); true } else false
            }
            if (hit) {
                s.jiggle = max(s.jiggle, 0.35f)
                if (Random.nextFloat() < 0.35f)
                    particles.add(Particle(x + (Random.nextFloat() - 0.5f) * 20f * dp, y - shroomSize(s) * 1.1f, (Random.nextFloat() - 0.5f) * 30f * dp, -40f * dp, 1.4f, 1.4f,
                        listOf(Color.rgb(255, 220, 150), ACCENT, MINT, Color.rgb(180, 160, 255)).random(), P_NOTE))
            }
        }
    }

    /** 0 drum, 1 ukulele, 2 flute, 3 maracas, 4 triangle */
    private fun drawInstrument(c: Canvas, k: Int, x: Float, y: Float, s: Float, hit: Float) {
        c.save(); c.rotate(hit * 20f, x, y)
        when (k) {
            0 -> {
                fill.color = Color.rgb(230, 80, 90); c.drawRoundRect(x - s * 0.5f, y - s * 0.2f, x + s * 0.5f, y + s * 0.35f, s * 0.1f, s * 0.1f, fill)
                fill.color = Color.rgb(250, 240, 225); c.drawOval(x - s * 0.5f, y - s * 0.32f, x + s * 0.5f, y - s * 0.08f, fill)
                stroke.color = Color.rgb(255, 210, 90); stroke.strokeWidth = s * 0.06f
                c.drawLine(x - s * 0.5f, y + s * 0.08f, x + s * 0.5f, y + s * 0.08f, stroke)
            }
            1 -> {
                fill.color = Color.rgb(200, 130, 70); c.drawCircle(x, y + s * 0.15f, s * 0.3f, fill); c.drawCircle(x, y - s * 0.12f, s * 0.22f, fill)
                fill.color = Color.rgb(90, 55, 35); c.drawCircle(x, y + s * 0.05f, s * 0.09f, fill)
                c.drawRect(x - s * 0.05f, y - s * 0.75f, x + s * 0.05f, y - s * 0.1f, fill)
            }
            2 -> {
                stroke.color = Color.rgb(170, 220, 255); stroke.strokeWidth = s * 0.14f
                c.drawLine(x - s * 0.55f, y + s * 0.2f, x + s * 0.45f, y - s * 0.3f, stroke)
                fill.color = Color.rgb(60, 90, 140)
                for (h in 0..2) c.drawCircle(x - s * 0.2f + h * s * 0.2f, y + s * 0.03f - h * s * 0.1f, s * 0.035f, fill)
            }
            3 -> for (side in intArrayOf(-1, 1)) {
                val mx = x + side * s * 0.28f; val my = y - s * 0.1f + (if (side > 0) hit else -hit) * s * 0.2f
                stroke.color = Color.rgb(140, 90, 60); stroke.strokeWidth = s * 0.08f
                c.drawLine(mx, my, mx, my + s * 0.45f, stroke)
                fill.color = if (side < 0) Color.rgb(255, 200, 70) else Color.rgb(120, 220, 160); c.drawCircle(mx, my, s * 0.2f, fill)
            }
            else -> {
                stroke.color = Color.rgb(220, 225, 235); stroke.strokeWidth = s * 0.06f
                path.reset(); path.moveTo(x, y - s * 0.45f); path.lineTo(x + s * 0.4f, y + s * 0.3f); path.lineTo(x - s * 0.4f, y + s * 0.3f); path.close()
                c.drawPath(path, stroke)
            }
        }
        c.restore()
    }

    // ---------- Pets ----------

    /** 0 snail, 1 ladybug, 2 firefly, 3 froggy, 4 ghostie, 5 penguin, 6 bunny */
    private fun drawPet(c: Canvas, p: Int, x: Float, y0: Float, s: Float, phase: Float, facing: Float, asleep: Boolean) {
        val f = if (facing >= 0) 1f else -1f
        val eye = Color.rgb(45, 30, 55)
        fun eyes(ex: Float, ey: Float, r: Float, gap: Float) {
            fill.color = eye
            if (asleep) { stroke.color = eye; stroke.strokeWidth = r * 0.6f
                c.drawLine(ex - gap - r, ey, ex - gap + r, ey, stroke); c.drawLine(ex + gap - r, ey, ex + gap + r, ey, stroke) }
            else { c.drawCircle(ex - gap, ey, r, fill); c.drawCircle(ex + gap, ey, r, fill)
                fill.color = Color.WHITE; c.drawCircle(ex - gap + r * 0.3f, ey - r * 0.3f, r * 0.35f, fill); c.drawCircle(ex + gap + r * 0.3f, ey - r * 0.3f, r * 0.35f, fill) }
        }
        when (p) {
            0 -> { // snail
                groundShadow(c, x, y0, s * 0.5f, 70)
                fill.color = Color.rgb(200, 215, 190)
                c.drawRoundRect(x - s * 0.55f, y0 - s * 0.18f, x + s * 0.55f, y0, s * 0.09f, s * 0.09f, fill)
                stroke.color = Color.rgb(200, 215, 190); stroke.strokeWidth = s * 0.05f
                val hx = x + f * s * 0.48f
                c.drawLine(hx, y0 - s * 0.15f, hx + f * s * 0.08f, y0 - s * 0.42f, stroke)
                c.drawLine(hx - f * s * 0.1f, y0 - s * 0.15f, hx - f * s * 0.06f, y0 - s * 0.4f, stroke)
                fill.color = eye; c.drawCircle(hx + f * s * 0.08f, y0 - s * 0.44f, s * 0.05f, fill); c.drawCircle(hx - f * s * 0.06f, y0 - s * 0.42f, s * 0.05f, fill)
                fill.shader = RadialGradient(x - f * s * 0.1f, y0 - s * 0.45f, s * 0.4f, Color.rgb(255, 190, 170), Color.rgb(220, 120, 110), Shader.TileMode.CLAMP)
                c.drawCircle(x - f * s * 0.08f, y0 - s * 0.35f, s * 0.32f, fill); fill.shader = null
                stroke.color = Color.rgb(190, 90, 90); stroke.strokeWidth = s * 0.05f
                c.drawArc(x - f * s * 0.08f - s * 0.2f, y0 - s * 0.55f, x - f * s * 0.08f + s * 0.2f, y0 - s * 0.15f, 0f, 270f, false, stroke)
                c.drawArc(x - f * s * 0.08f - s * 0.09f, y0 - s * 0.44f, x - f * s * 0.08f + s * 0.09f, y0 - s * 0.26f, 90f, 270f, false, stroke)
                fill.color = Color.argb(140, 255, 245, 200)
                for (k in 1..3) c.drawCircle(x - f * (s * 0.5f + k * s * 0.25f), y0 - s * 0.02f, s * 0.035f, fill)
            }
            1 -> { // ladybug
                val flap = abs(sin(phase * 2f))
                fill.color = Color.argb(140, 255, 255, 255)
                c.drawOval(x - s * 0.5f, y0 - s * 0.45f - flap * s * 0.15f, x - s * 0.05f, y0 - s * 0.1f, fill)
                c.drawOval(x + s * 0.05f, y0 - s * 0.45f - flap * s * 0.15f, x + s * 0.5f, y0 - s * 0.1f, fill)
                fill.color = Color.rgb(40, 30, 40); c.drawCircle(x + f * s * 0.3f, y0, s * 0.16f, fill)
                fill.color = Color.rgb(230, 50, 60); c.drawOval(x - s * 0.32f, y0 - s * 0.26f, x + s * 0.3f, y0 + s * 0.24f, fill)
                fill.color = Color.rgb(40, 30, 40)
                c.drawRect(x - s * 0.01f, y0 - s * 0.26f, x + s * 0.01f, y0 + s * 0.24f, fill)
                for ((dx, dy) in arrayOf(-0.15f to -0.08f, 0.13f to -0.1f, -0.12f to 0.1f, 0.12f to 0.1f)) c.drawCircle(x + dx * s, y0 + dy * s, s * 0.06f, fill)
            }
            2 -> { // firefly
                val glow = 0.5f + 0.5f * sin(phase * 0.6f)
                val night = 0.4f + 0.6f * LabState.darkness()
                fill.shader = RadialGradient(x - f * s * 0.2f, y0 + s * 0.05f, s * 0.9f, Color.argb((160 * glow * night).toInt(), 255, 240, 120), Color.argb(0, 255, 240, 120), Shader.TileMode.CLAMP)
                c.drawCircle(x - f * s * 0.2f, y0 + s * 0.05f, s * 0.9f, fill); fill.shader = null
                fill.color = Color.argb(150, 255, 255, 255)
                val flap = abs(sin(phase * 2.5f))
                c.drawOval(x - s * 0.3f, y0 - s * 0.4f - flap * s * 0.12f, x, y0 - s * 0.1f, fill)
                c.drawOval(x, y0 - s * 0.4f - flap * s * 0.12f, x + s * 0.3f, y0 - s * 0.1f, fill)
                fill.color = Color.rgb(70, 60, 80); c.drawOval(x - s * 0.25f, y0 - s * 0.14f, x + s * 0.25f, y0 + s * 0.14f, fill)
                fill.color = Color.rgb(255, 245, 140); c.drawCircle(x - f * s * 0.22f, y0 + s * 0.04f, s * 0.13f, fill)
            }
            3 -> { // froggy hops along
                val hop = abs(sin(phase * 0.5f)) * s * 0.3f
                groundShadow(c, x, y0, s * 0.45f, 70)
                val y = y0 - hop
                fill.color = Color.rgb(110, 200, 110); c.drawOval(x - s * 0.45f, y - s * 0.5f, x + s * 0.45f, y, fill)
                c.drawCircle(x - s * 0.22f, y - s * 0.5f, s * 0.15f, fill); c.drawCircle(x + s * 0.22f, y - s * 0.5f, s * 0.15f, fill)
                fill.color = Color.WHITE; c.drawCircle(x - s * 0.22f, y - s * 0.52f, s * 0.1f, fill); c.drawCircle(x + s * 0.22f, y - s * 0.52f, s * 0.1f, fill)
                eyes(x, y - s * 0.52f, s * 0.055f, s * 0.22f)
                fill.color = Color.rgb(170, 235, 160); c.drawOval(x - s * 0.25f, y - s * 0.25f, x + s * 0.25f, y - s * 0.02f, fill)
                stroke.color = Color.rgb(60, 120, 60); stroke.strokeWidth = s * 0.04f
                c.drawArc(x - s * 0.15f, y - s * 0.38f, x + s * 0.15f, y - s * 0.24f, 20f, 140f, false, stroke)
            }
            4 -> { // ghostie floats
                val y = y0 - s * 0.35f + sin(phase * 0.4f) * s * 0.12f
                groundShadow(c, x, y0, s * 0.35f, 40)
                fill.color = Color.argb(225, 250, 250, 255)
                path.reset()
                path.moveTo(x - s * 0.38f, y + s * 0.3f); path.lineTo(x - s * 0.38f, y - s * 0.05f)
                path.cubicTo(x - s * 0.38f, y - s * 0.55f, x + s * 0.38f, y - s * 0.55f, x + s * 0.38f, y - s * 0.05f)
                path.lineTo(x + s * 0.38f, y + s * 0.3f)
                for (k in 0..3) path.lineTo(x + s * 0.38f - (k + 0.5f) * s * 0.19f, y + s * (if (k % 2 == 0) 0.18f else 0.3f))
                path.lineTo(x - s * 0.38f, y + s * 0.3f); path.close()
                c.drawPath(path, fill)
                eyes(x + f * s * 0.04f, y - s * 0.08f, s * 0.06f, s * 0.13f)
                fill.color = Color.argb(150, 255, 140, 170); c.drawCircle(x - s * 0.22f, y + s * 0.05f, s * 0.05f, fill); c.drawCircle(x + s * 0.26f, y + s * 0.05f, s * 0.05f, fill)
            }
            5 -> { // penguin waddles
                c.save(); c.rotate(sin(phase * 0.8f) * 8f, x, y0)
                groundShadow(c, x, y0, s * 0.35f, 70)
                fill.color = Color.rgb(255, 160, 60)
                c.drawOval(x - s * 0.25f, y0 - s * 0.06f, x - s * 0.02f, y0 + s * 0.04f, fill); c.drawOval(x + s * 0.02f, y0 - s * 0.06f, x + s * 0.25f, y0 + s * 0.04f, fill)
                fill.color = Color.rgb(45, 50, 70); c.drawOval(x - s * 0.32f, y0 - s * 0.85f, x + s * 0.32f, y0 - s * 0.02f, fill)
                fill.color = Color.WHITE; c.drawOval(x - s * 0.21f, y0 - s * 0.62f, x + s * 0.21f, y0 - s * 0.06f, fill)
                eyes(x + f * s * 0.04f, y0 - s * 0.62f, s * 0.045f, s * 0.1f)
                fill.color = Color.rgb(255, 170, 60)
                path.reset(); path.moveTo(x + f * s * 0.02f, y0 - s * 0.56f); path.lineTo(x + f * s * 0.2f, y0 - s * 0.52f); path.lineTo(x + f * s * 0.02f, y0 - s * 0.48f); path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(220, 50, 60); c.drawRect(x - s * 0.28f, y0 - s * 0.47f, x + s * 0.28f, y0 - s * 0.4f, fill)
                c.restore()
            }
            Data.MINI_DRAGON -> { // tiny dragon flapping around
                val flap = sin(phase * 1.6f)
                fill.color = Color.rgb(150, 110, 230)
                path.reset(); path.moveTo(x - f * s * 0.05f, y0 - s * 0.1f)
                path.lineTo(x - f * s * 0.45f, y0 - s * 0.45f - flap * s * 0.2f); path.lineTo(x - f * s * 0.3f, y0 - s * 0.05f); path.close(); c.drawPath(path, fill)
                fill.shader = RadialGradient(x, y0 - s * 0.05f, s * 0.4f, Color.rgb(140, 230, 200), Color.rgb(60, 160, 140), Shader.TileMode.CLAMP)
                c.drawOval(x - s * 0.28f, y0 - s * 0.25f, x + s * 0.22f, y0 + s * 0.15f, fill); fill.shader = null
                fill.color = Color.rgb(90, 190, 160)
                path.reset(); path.moveTo(x - f * s * 0.2f, y0); path.quadTo(x - f * s * 0.5f, y0 + s * 0.1f, x - f * s * 0.55f, y0 - s * 0.1f); path.lineTo(x - f * s * 0.45f, y0 + s * 0.05f); path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(140, 230, 200); c.drawCircle(x + f * s * 0.22f, y0 - s * 0.2f, s * 0.17f, fill)
                fill.color = Color.rgb(255, 230, 150)
                path.reset(); path.moveTo(x + f * s * 0.15f, y0 - s * 0.32f); path.lineTo(x + f * s * 0.12f, y0 - s * 0.48f); path.lineTo(x + f * s * 0.21f, y0 - s * 0.34f); path.close(); c.drawPath(path, fill)
                eyes(x + f * s * 0.26f, y0 - s * 0.22f, s * 0.04f, s * 0.06f)
                if ((phase * 0.3f) % 3f < 0.4f) { fill.color = Color.rgb(255, 210, 90); drawSparkle(c, x + f * s * 0.5f, y0 - s * 0.18f, s * 0.08f) }
            }
            else -> { // bunny
                val hop = abs(sin(phase * 0.45f)) * s * 0.22f
                groundShadow(c, x, y0, s * 0.4f, 70)
                val y = y0 - hop
                fill.color = Color.rgb(250, 248, 252)
                c.drawOval(x - s * 0.18f, y - s * 1.0f, x - s * 0.03f, y - s * 0.45f, fill); c.drawOval(x + s * 0.03f, y - s * 1.0f, x + s * 0.18f, y - s * 0.45f, fill)
                fill.color = Color.rgb(255, 180, 200)
                c.drawOval(x - s * 0.14f, y - s * 0.92f, x - s * 0.07f, y - s * 0.55f, fill); c.drawOval(x + s * 0.07f, y - s * 0.92f, x + s * 0.14f, y - s * 0.55f, fill)
                fill.color = Color.rgb(250, 248, 252)
                c.drawOval(x - s * 0.36f, y - s * 0.58f, x + s * 0.36f, y, fill)
                c.drawCircle(x - f * s * 0.36f, y - s * 0.18f, s * 0.1f, fill)
                eyes(x + f * s * 0.05f, y - s * 0.36f, s * 0.045f, s * 0.11f)
                fill.color = Color.rgb(255, 150, 180); c.drawCircle(x + f * s * 0.05f, y - s * 0.27f, s * 0.035f, fill)
            }
        }
    }

    // ---------- Journal extras: event quest, weekly mystery, recipe notebook ----------

    private fun drawQuestAndMystery(c: Canvas, w: Float, y0: Float) {
        val pad = 16f * dp
        var y = y0
        val step = lab.questNow()
        val ev = eventNow()
        if (step >= 0) {
            val q = Data.QUESTS[ev]
            val r = RectF(pad, y, w - pad, y + 92f * dp)
            fill.color = Color.argb(70, Color.red(Data.EVENT_COLORS[ev]), Color.green(Data.EVENT_COLORS[ev]), Color.blue(Data.EVENT_COLORS[ev]))
            c.drawRoundRect(r, 16f * dp, 16f * dp, fill)
            stroke.color = Data.EVENT_COLORS[ev]; stroke.strokeWidth = 1.5f * dp; c.drawRoundRect(r, 16f * dp, 16f * dp, stroke)
            drawPet(c, q.pet, r.right - 34f * dp, r.top + 58f * dp, 34f * dp, t * 6f, -1f, false)
            text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
            c.drawText("${Data.EVENT_ICONS[ev]} ${q.title}" + if (step >= 3) "  ✓" else "  (${step + 1}/3)", r.left + 14f * dp, r.top + 22f * dp, text)
            text.textSize = 11.5f * dp; text.color = TEXT_DIM
            val body = if (step >= 3) q.outro else q.intro
            for ((k, l) in wrap(body, r.width() - 90f * dp).take(2).withIndex()) c.drawText(l, r.left + 14f * dp, r.top + 40f * dp + k * 15f * dp, text)
            if (step < 3) {
                val st = q.steps[step]
                text.color = MINT; text.textSize = 12f * dp
                c.drawText("→ " + st.text + (if (st.type == 2) "  (${lab.questCount}/${st.count})" else ""), r.left + 14f * dp, r.bottom - 12f * dp, text)
            }
            text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            y += 100f * dp
        }
        val wk = Data.weekKey()
        val m = Data.mysteryFor(wk)
        val solved = lab.mysteryClaimed == wk
        val r = RectF(pad, y, w - pad, y + 64f * dp)
        fill.color = PANEL; c.drawRoundRect(r, 16f * dp, 16f * dp, fill)
        drawShroom(c, m, r.left + 30f * dp, r.top + 50f * dp, 30f * dp, 0, sin(t * 2f), false, !solved)
        text.textAlign = Paint.Align.LEFT; text.textSize = 14f * dp
        c.drawText("🔮 Mystery recipe of the week" + if (solved) "  ✓" else "  · 120 ✦", r.left + 58f * dp, r.top + 24f * dp, text)
        text.textSize = 11.5f * dp; text.color = if (solved) MINT else TEXT_DIM
        c.drawText(if (solved) "Solved: ${Data.SPECIES[m].name}! A new one comes next week." else "Hint: ${Data.SPECIES[m].hint}", r.left + 58f * dp, r.top + 44f * dp, text)
        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
    }

    private var notesPick = -1
    private var notesBmp: android.graphics.Bitmap? = null
    private var notesKey = ""

    private fun notesGrid(w: Float): Triple<Float, Float, Float> {
        val pad = 16f * dp
        val head = 24f * dp
        val cell = (w - pad * 2 - head) / 12
        return Triple(pad + head, topBar + 60f * dp + 30f * dp + head, cell)
    }

    private fun drawNotes(c: Canvas, w: Float, top: Float) {
        val pad = 16f * dp
        val (gx, gy, cell) = notesGrid(w)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        c.drawText("Every pair you've brewed · ${lab.tried.count { it.key.split(",").all { k -> k.toInt() < 12 } }} of 78 tried · tap a square", w / 2, top + 16f * dp, text)
        text.color = Color.WHITE
        // the grid only changes when you brew, so it's drawn once into a picture
        val key = "${lab.tried.size}-${width}-${lab.tried.values.sum()}"
        if (notesBmp == null || key != notesKey) {
            notesKey = key
            val bmp = android.graphics.Bitmap.createBitmap((cell * 12 + 24f * dp + 2).toInt(), (cell * 12 + 24f * dp + 2).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
            val bc = Canvas(bmp)
            val ox = 24f * dp; val oy = 24f * dp
            for (i in 0 until 12) {
                drawIngredient(bc, i, ox + i * cell + cell / 2, oy / 2, 7f * dp)
                drawIngredient(bc, i, oy / 2, oy + i * cell + cell / 2, 7f * dp)
            }
            for (a in 0 until 12) for (b in 0 until 12) {
                val x = ox + b * cell; val yy = oy + a * cell
                fill.color = if ((a + b) % 2 == 0) Color.argb(40, 255, 255, 255) else Color.argb(22, 255, 255, 255)
                bc.drawRoundRect(x + 1f, yy + 1f, x + cell - 1f, yy + cell - 1f, 5f * dp, 5f * dp, fill)
                val sp = lab.triedPair(a, b)
                if (sp >= 0) drawShroom(bc, sp, x + cell / 2, yy + cell * 0.88f, cell * 0.62f, 0, 0f, false, false)
                else { fill.color = Color.argb(60, 255, 255, 255); bc.drawCircle(x + cell / 2, yy + cell / 2, 1.6f * dp, fill) }
            }
            notesBmp = bmp
        }
        notesBmp?.let { c.drawBitmap(it, gx - 24f * dp, gy - 24f * dp, null) }
        if (notesPick >= 0) {
            val a = notesPick / 12; val b = notesPick % 12
            stroke.color = DUST; stroke.strokeWidth = 2f * dp
            c.drawRoundRect(gx + b * cell, gy + a * cell, gx + (b + 1) * cell, gy + (a + 1) * cell, 5f * dp, 5f * dp, stroke)
        }
        var y = gy + cell * 12 + 22f * dp
        text.textSize = 13f * dp
        if (notesPick >= 0) {
            val a = notesPick / 12; val b = notesPick % 12
            val sp = lab.triedPair(a, b)
            c.drawText("${Data.INGREDIENTS[a].name} + ${Data.INGREDIENTS[b].name} → " + (if (sp >= 0) Data.SPECIES[sp].name else "not tried yet"), w / 2, y, text)
        } else {
            text.color = TEXT_DIM; c.drawText("Tap a square to see what that pair grows", w / 2, y, text); text.color = Color.WHITE
        }
        y += 24f * dp
        text.textAlign = Paint.Align.LEFT; text.textSize = 13f * dp
        c.drawText("Rare 3-ingredient recipes", pad, y, text)
        y += 6f * dp
        for (i in Data.SPECIES.indices.filter { Data.SPECIES[it].tier == 4 && Data.SPECIES[it].event == 0 }) {
            y += 17f * dp
            if (y > navTop - 8f * dp) break
            val known = lab.discovered[i]
            val f = lab.fizzles[i]
            text.textSize = 11.5f * dp
            text.color = if (known) MINT else Color.WHITE
            val nm = if (known || f >= 2) Data.SPECIES[i].name else "???"
            c.drawText((if (known) "✓ " else "• ") + nm, pad, y, text)
            text.color = TEXT_DIM
            c.drawText(if (known) Data.recipeText(i) else (if (f > 0) "fizzled ${f}× · " else "") + Data.SPECIES[i].hint, pad + 120f * dp, y, text)
        }
        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
    }

    // ---------- Hide & Seek ----------

    private val hsHidden = IntArray(9) { -1 }      // species hiding in each bush
    private val hsFound = BooleanArray(9)
    private val hsTapped = BooleanArray(9)
    private val hsRustle = FloatArray(9)
    private var hsTaps = 0
    private var hsRound = 0
    private var hsNext = 0f
    private var hsHint = 0f

    private fun hsNewRound() {
        hsRound++
        for (i in 0 until 9) { hsHidden[i] = -1; hsFound[i] = false; hsTapped[i] = false; hsRustle[i] = 0f }
        val kinds = lab.home().map { it.species }.ifEmpty { listOf(1, 2, 3) }
        for (b in (0 until 9).shuffled().take(3)) hsHidden[b] = kinds.random()
        hsTaps = 5; hsNext = 0f; hsHint = 1f
    }

    private fun tapBush(i: Int) {
        if (miniOver || hsNext > 0f || hsTapped[i]) return
        hsTapped[i] = true; hsTaps--
        if (hsHidden[i] >= 0) {
            hsFound[i] = true; miniScore++
            sound.play(Fx.GIGGLE, 0.7f, 1f + Random.nextFloat() * 0.3f); buzz(25)
        } else sound.play(Fx.POP, 0.5f, 0.7f)
        val left = (0 until 9).count { hsHidden[it] >= 0 && !hsFound[it] }
        if (left == 0 || hsTaps <= 0) hsNext = 1.4f
    }

    private fun updateHide(dt: Float) {
        for (i in 0 until 9) hsRustle[i] = max(0f, hsRustle[i] - dt)
        if (hsNext > 0f) {
            hsNext -= dt
            if (hsNext <= 0f) { if (hsRound >= 3) endMini() else hsNewRound() }
            return
        }
        hsHint -= dt
        if (hsHint <= 0f) {
            hsHint = 0.9f + Random.nextFloat() * 0.9f
            val hiding = (0 until 9).filter { hsHidden[it] >= 0 && !hsFound[it] }
            val b = if (hiding.isNotEmpty() && Random.nextFloat() < 0.7f) hiding.random() else (0 until 9).filter { !hsTapped[it] }.randomOrNull() ?: return
            hsRustle[b] = 0.6f
        }
    }

    private fun hideRect(i: Int, w: Float, h: Float): RectF {
        val cell = min((w - 40f * dp) / 3, (h * 0.62f) / 3)
        val gx = w / 2 - cell * 1.5f; val gy = topBar + 90f * dp
        return RectF(gx + (i % 3) * cell + 4f * dp, gy + (i / 3) * cell + 4f * dp, gx + (i % 3 + 1) * cell - 4f * dp, gy + (i / 3 + 1) * cell - 4f * dp)
    }

    private fun drawBush(c: Canvas, x: Float, y: Float, s: Float, wiggle: Float) {
        c.save(); c.rotate(wiggle * 6f, x, y)
        groundShadow(c, x, y, s * 0.6f, 80)
        for ((k, d) in arrayOf(Triple(-0.3f, -0.35f, 0.32f), Triple(0.3f, -0.35f, 0.32f), Triple(0f, -0.55f, 0.36f), Triple(-0.1f, -0.25f, 0.34f), Triple(0.15f, -0.22f, 0.3f)).withIndex()) {
            fill.color = if (k % 2 == 0) Color.rgb(70, 150, 80) else Color.rgb(90, 175, 95)
            c.drawCircle(x + d.first * s, y + d.second * s, d.third * s, fill)
        }
        fill.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(x - s * 0.1f, y - s * 0.72f, s * 0.1f, fill)
        for ((k, d) in arrayOf(-0.32f to -0.5f, 0.12f to -0.7f, 0.36f to -0.3f, -0.05f to -0.28f).withIndex()) {
            fill.color = if (k % 2 == 0) Color.rgb(255, 170, 200) else Color.rgb(255, 235, 140)
            c.drawCircle(x + d.first * s, y + d.second * s, s * 0.05f, fill)
            fill.color = Color.WHITE; c.drawCircle(x + d.first * s, y + d.second * s, s * 0.018f, fill)
        }
        c.restore()
    }

    private fun drawHideCell(c: Canvas, r: RectF, i: Int) {
        val x = r.centerX(); val y = r.bottom - r.height() * 0.12f
        val s = r.width() * 0.62f
        val missed = !hsFound[i] && hsNext > 0f && hsHidden[i] >= 0
        if (missed) drawShroom(c, hsHidden[i], x + s * 0.2f, y - s * 0.55f, s * 0.5f, 0, 0f, false, true)
        drawBush(c, x, y, s, if (hsRustle[i] > 0f) sin(t * 40f) else 0f)
        if (hsFound[i]) {   // found! it hops out in front of its bush
            drawShroom(c, hsHidden[i], x + s * 0.18f, y + s * 0.02f - abs(sin(t * 5f)) * s * 0.06f, s * 0.6f, 0, sin(t * 4f), false, false,
                who = Shroom(-9L, hsHidden[i]).apply { expr = E_GIGGLE; exprT = 1f; xp = 200f })
        }
        if (hsTapped[i] && hsHidden[i] < 0) { text.textSize = 13f * dp; text.color = TEXT_DIM; c.drawText("nobody", x, r.top + 16f * dp, text); text.color = Color.WHITE }
    }

    // ---------- Family tree ----------

    private fun drawFamily(c: Canvas, w: Float, h: Float, s: Shroom) {
        text.textSize = 24f * dp; text.color = Color.WHITE
        c.drawText("${s.name}'s family", w / 2, h * 0.1f, text)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        c.drawText(if (s.family[0].isEmpty()) "Brewed in the lab, so Professor Spore is like a parent!" else "Grandparents · parents · ${s.name}", w / 2, h * 0.1f + 22f * dp, text)
        text.color = Color.WHITE
        // positions: grandparents row, parents row, the mushroom itself
        val gy = h * 0.3f; val py = h * 0.52f; val cy = h * 0.76f
        val gxs = floatArrayOf(w * 0.14f, w * 0.38f, w * 0.62f, w * 0.86f)
        val pxs = floatArrayOf(w * 0.26f, w * 0.74f)
        stroke.color = Color.argb(120, 255, 200, 230); stroke.strokeWidth = 2.5f * dp
        fun link(x1: Float, y1: Float, x2: Float, y2: Float) {
            path.reset(); path.moveTo(x1, y1); path.cubicTo(x1, (y1 + y2) / 2, x2, (y1 + y2) / 2, x2, y2); c.drawPath(path, stroke)
        }
        if (s.family[0].isNotEmpty()) {
            for (k in 0..1) {
                link(pxs[k], py + 8f * dp, w / 2, cy - 80f * dp)
                for (g in 0..1) if (s.family[2 + k * 2 + g].isNotEmpty()) link(gxs[k * 2 + g], gy + 8f * dp, pxs[k], py - 64f * dp)
            }
        }
        fun member(entry: String, x: Float, y: Float, size: Float) {
            val parts = entry.split("|")
            if (entry.isEmpty() || parts.size < 2) {
                fill.color = Color.argb(40, 255, 255, 255); c.drawCircle(x, y - size * 0.5f, size * 0.45f, fill)
                text.textSize = 16f * dp; text.color = TEXT_DIM; c.drawText("?", x, y - size * 0.4f, text)
                text.textSize = 11f * dp; c.drawText("unknown", x, y + 16f * dp, text); text.color = Color.WHITE
                return
            }
            val sp = parts[1].toIntOrNull()?.coerceIn(0, Data.SPECIES.size - 1) ?: 0
            drawShroom(c, sp, x, y, size, 0, sin(t * 2f + x), (t + x) % 4f < 0.12f, false)
            text.textSize = 12f * dp; text.color = Color.WHITE
            c.drawText(parts[0], x, y + 17f * dp, text)
            text.textSize = 10f * dp; text.color = TEXT_DIM
            c.drawText(Data.SPECIES[sp].name, x, y + 30f * dp, text)
            text.color = Color.WHITE
        }
        if (s.family[0].isNotEmpty()) {
            for (g in 0..3) member(s.family[2 + g], gxs[g], gy, 46f * dp)
            for (k in 0..1) member(s.family[k], pxs[k], py, 62f * dp)
        } else {
            drawShroom(c, professor.species, w / 2, py, 70f * dp, 0, sin(t * 2f), (t % 4f) < 0.12f, false, who = professor)
            text.textSize = 13f * dp; c.drawText("Professor Spore", w / 2, py + 20f * dp, text)
            link(w / 2, py + 30f * dp, w / 2, cy - 80f * dp)
        }
        drawShroom(c, s.species, w / 2, cy, 84f * dp, s.mood(), sin(t * 2.5f), (t % 3f) < 0.12f, false, shiny = s.shiny, who = s)
        text.textSize = 16f * dp; c.drawText(s.name, w / 2, cy + 22f * dp, text)
        // brothers and sisters still in the garden
        val sibs = lab.garden.filter { it !== s && it.family[0].isNotEmpty() && setOf(it.family[0], it.family[1]) == setOf(s.family[0], s.family[1]) }
        if (sibs.isNotEmpty()) {
            text.textSize = 11f * dp; text.color = TEXT_DIM
            c.drawText("Siblings: " + sibs.joinToString(", ") { it.name }, w / 2, cy + 40f * dp, text)
            text.color = Color.WHITE
        }
    }

    // ---------- Profile cards ----------

    private var cardFor = -1L

    private fun cardRect(w: Float, h: Float): RectF {
        val cw = min(w - 48f * dp, 320f * dp)
        val ch = cw * 1.4f
        return RectF(w / 2 - cw / 2, h * 0.08f, w / 2 + cw / 2, h * 0.08f + ch)
    }

    private fun drawProfileCard(c: Canvas, r: RectF, s: Shroom) {
        val sp = Data.SPECIES[s.species]
        val rad = 24f * dp
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, shade(sp.cap, 0.55f), shade(sp.cap, -0.25f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, fill); fill.shader = null
        stroke.color = Color.WHITE; stroke.strokeWidth = 5f * dp
        c.drawRoundRect(r.left + 6f * dp, r.top + 6f * dp, r.right - 6f * dp, r.bottom - 6f * dp, rad - 4f * dp, rad - 4f * dp, stroke)
        // portrait window
        val pw = RectF(r.left + 22f * dp, r.top + 22f * dp, r.right - 22f * dp, r.top + r.width() * 0.78f)
        fill.shader = LinearGradient(0f, pw.top, 0f, pw.bottom, Color.rgb(60, 40, 100), Color.rgb(90, 150, 110), Shader.TileMode.CLAMP)
        c.drawRoundRect(pw, 16f * dp, 16f * dp, fill); fill.shader = null
        for (k in 0 until 10) { fill.color = Color.argb(150, 255, 255, 230); drawSparkle(c, pw.left + pw.width() * ((k * 0.37f) % 1f), pw.top + pw.height() * ((k * 0.23f) % 0.5f), 2f * dp) }
        drawShroom(c, s.species, pw.centerX(), pw.bottom - 18f * dp, pw.height() * 0.62f, 0, sin(t * 2f), (t % 3f) < 0.12f, false, shiny = s.shiny, who = s)
        // name & info
        text.textSize = 26f * dp; text.color = Color.WHITE
        c.drawText(s.name, r.centerX(), pw.bottom + 36f * dp, text)
        text.textSize = 14f * dp; text.color = Color.argb(230, 255, 255, 255)
        c.drawText((if (s.shiny) "✨ Shiny " else "") + sp.name, r.centerX(), pw.bottom + 58f * dp, text)
        val tags = ArrayList<String>()
        tags.add(Data.STAGE_NAMES[s.stage()]); tags.add(Data.TIER_NAMES[sp.tier]); tags.add(Data.TRAITS[s.trait])
        if (s.variant == 1) tags.add("Albino") else if (s.variant == 2) tags.add("Neon")
        text.textSize = 12f * dp
        c.drawText(tags.joinToString(" · "), r.centerX(), pw.bottom + 78f * dp, text)
        for (k in 0 until 5) {
            fill.color = if (k < s.hearts()) Color.rgb(255, 90, 140) else Color.argb(90, 255, 255, 255)
            drawHeart(c, r.centerX() + (k - 2) * 20f * dp, pw.bottom + 94f * dp, 6f * dp)
        }
        text.textSize = 11f * dp; text.color = Color.argb(200, 255, 255, 255)
        c.drawText("Shroomology · made by Omnymara", r.centerX(), r.bottom - 18f * dp, text)
        text.color = Color.WHITE
    }

    private fun shareCard(s: Shroom) {
        val r = cardRect(width.toFloat(), height.toFloat())
        val bmp = android.graphics.Bitmap.createBitmap(r.width().toInt() + 2, r.height().toInt() + 2, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.translate(-r.left, -r.top)
        drawProfileCard(cv, r, s)
        saveAndShare(bmp, "Shroomology_${s.name}", "Share ${s.name}'s card")
    }

    /** Saves a picture to Pictures/Shroomology and opens the share menu. */
    private fun saveAndShare(bmp: android.graphics.Bitmap, fileName: String, chooser: String) {
        if (Build.VERSION.SDK_INT < 29) { toast("Saving pictures needs Android 10 or newer"); return }
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${fileName}_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Shroomology")
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw Exception()
            resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            flashT = 0.35f
            sound.play(Fx.PET)
            toast("Saved to Pictures/Shroomology", MINT)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(share, chooser))
        } catch (_: Exception) {
            toast("Couldn't save the picture", ACCENT)
        }
    }

    // ---------- Lab charm: Professor Spore, a flask with a face, wobbly jars ----------

    private var profSay = ""
    private var profSayT = 0f
    private var flaskYum = 0f
    private val jarWobble = FloatArray(6)
    private val tips = arrayOf(
        "Every pair of ingredients grows something!", "Stir in circles for a shiny chance ✨",
        "Grown mushrooms can crossbreed!", "The Big Flask lets you brew with three!",
        "Mushrooms adore their favorite food ❤", "Decorations make mushrooms happier!",
        "Send Grown mushrooms exploring for loot!", "Check the Journal for daily gifts!",
        "Hold a mushroom to give it a hug 🤗", "Rub a mushroom back and forth to pet it!",
        "Toss a mushroom for a big wheee!", "Shake your phone... if you dare!",
        "Rare recipes can fizzle. Stir well to boost the odds!", "Happy parents crossbreed better!",
    )

    private fun profPos(w: Float): Pair<Float, Float> = Pair(w * 0.13f, flaskY() + 62f * dp + 64f * dp)

    private fun professorSay(msg: String, secs: Float = 3.5f) { profSay = msg; profSayT = secs }

    private fun drawLabFriends(c: Canvas, w: Float) {
        val (px, py) = profPos(w)
        val hop = if (brewing > 0f) abs(sin(t * 8f)) * 8f * dp else 0f
        val excited = popup?.let { it.isNew && popupT > 0.9f } ?: false
        professor.expr = when { excited -> E_SPARKLE; brewing > 0f -> E_GIGGLE; else -> professor.expr }
        professor.exprT = if (excited || brewing > 0f) 0.2f else professor.exprT
        professor.waveT = if (profSayT > 0f) 1f else 0f
        drawShroom(c, professor.species, px, py - hop, 46f * dp, 0, sin(t * 2f), (t % 4f) < 0.12f, false, who = professor)
        if (brewing > 0f && profSayT <= 0f) professorSay(listOf("Stir, stir!", "Ooh, bubbly!", "Faster! Faster!", "Science!").random(), 1.5f)
    }

    /** The professor's speech bubble, drawn after the buttons so nothing covers it. */
    private fun drawProfBubble(c: Canvas, w: Float) {
        val (px, py) = profPos(w)
        if (profSayT > 0f && profSay.isNotEmpty()) {
            text.textSize = 12f * dp
            val lines = wrap(profSay, 120f * dp)
            val bw = (lines.maxOfOrNull { text.measureText(it) } ?: 0f) + 20f * dp
            val bh = lines.size * 15f * dp + 12f * dp
            // beside the professor's head and below the ingredient slot, so it never covers a slot
            val bx = px + 16f * dp; val by = slotRect(0).bottom + 8f * dp
            fill.color = Color.WHITE
            c.drawRoundRect(bx, by, bx + bw, by + bh, 10f * dp, 10f * dp, fill)
            path.reset(); path.moveTo(bx + 1f * dp, by + bh * 0.45f); path.lineTo(bx - 9f * dp, by + bh * 0.8f); path.lineTo(bx + 1f * dp, by + bh * 0.8f); path.close()
            c.drawPath(path, fill)
            text.color = BG; text.textAlign = Paint.Align.LEFT
            for ((k, l) in lines.withIndex()) c.drawText(l, bx + 10f * dp, by + 18f * dp + k * 15f * dp, text)
            text.textAlign = Paint.Align.CENTER; text.color = Color.WHITE
        }
    }

    /** A cute face on the flask that reacts to what you do. */
    private fun drawFlaskFace(c: Canvas, fx: Float, fy: Float, r: Float, shakeX: Float) {
        val cx = fx + shakeX; val cy = fy + r * 0.42f
        val ex = r * 0.22f; val er = r * 0.06f
        val q = quality()
        fill.color = Color.rgb(45, 30, 60)
        stroke.color = Color.rgb(45, 30, 60); stroke.strokeWidth = 2.5f * dp
        when {
            brewing > 0f && q >= 1f -> { fill.color = Color.rgb(255, 230, 120); drawSparkle(c, cx - ex, cy, er * 1.6f); drawSparkle(c, cx + ex, cy, er * 1.6f) }
            brewing > 0f -> {
                c.drawArc(cx - ex - er * 1.4f, cy - er, cx - ex + er * 1.4f, cy + er * 2f, 200f, 140f, false, stroke)
                c.drawArc(cx + ex - er * 1.4f, cy - er, cx + ex + er * 1.4f, cy + er * 2f, 200f, 140f, false, stroke)
            }
            (t % 4f) < 0.12f -> { c.drawLine(cx - ex - er, cy, cx - ex + er, cy, stroke); c.drawLine(cx + ex - er, cy, cx + ex + er, cy, stroke) }
            else -> { c.drawCircle(cx - ex, cy, er, fill); c.drawCircle(cx + ex, cy, er, fill)
                fill.color = Color.WHITE; c.drawCircle(cx - ex + er * 0.35f, cy - er * 0.35f, er * 0.35f, fill); c.drawCircle(cx + ex + er * 0.35f, cy - er * 0.35f, er * 0.35f, fill) }
        }
        fill.color = Color.argb(150, 255, 130, 160)
        c.drawOval(cx - ex * 1.8f - r * 0.06f, cy + r * 0.06f, cx - ex * 1.8f + r * 0.06f, cy + r * 0.12f, fill)
        c.drawOval(cx + ex * 1.8f - r * 0.06f, cy + r * 0.06f, cx + ex * 1.8f + r * 0.06f, cy + r * 0.12f, fill)
        val my = cy + r * 0.13f; val mw = r * 0.1f
        fill.color = Color.rgb(45, 30, 60)
        when {
            brewing > 0f -> c.drawOval(cx - mw * 0.5f, my - mw * 0.2f, cx + mw * 0.5f, my + mw * (0.8f + 0.3f * sin(t * 20f)), fill)
            flaskYum > 0f || slots.take(slotCount()).any { it >= 0 } -> c.drawArc(cx - mw, my - mw * 0.8f, cx + mw, my + mw, 0f, 180f, true, fill)
            else -> c.drawArc(cx - mw, my - mw * 0.6f, cx + mw, my + mw * 0.6f, 20f, 140f, false, stroke)
        }
    }

    private fun jarPos(k: Int, w: Float): Pair<Float, Float> {
        val spots = floatArrayOf(0.08f, 0.2f, 0.32f, 0.68f, 0.8f, 0.92f)
        return Pair(w * spots[k], flaskY() - 62f * dp * 1.75f)
    }

    private fun tapLab(x: Float, y: Float) {
        val w = width.toFloat()
        val (px, py) = profPos(w)
        if (hypot(x - px, y - (py - 22f * dp)) < 34f * dp) {
            professorSay(tips.random())
            professor.jiggle = 1f
            sound.play(Fx.GIGGLE, 0.6f, 0.9f)
            return
        }
        for (k in 0 until 6) {
            val (jx, jy) = jarPos(k, w)
            if (abs(x - jx) < 22f * dp && y > jy - 36f * dp && y < jy + 6f * dp) {
                jarWobble[k] = 1f
                val found = Data.DEX_ORDER.filter { lab.discovered[it] }
                sound.play(if (k < found.size) Fx.GIGGLE else Fx.CLINK, 0.6f, 1.2f + k * 0.08f)
                return
            }
        }
        if (hypot(x - w / 2, y - (flaskY() + 30f * dp)) < 60f * dp) { flaskYum = 0.8f; sound.play(Fx.BUBBLE, 0.5f, 1.3f) }
    }

    // ---------- Mini-games: Spore Catch & Memory Match ----------

    private var mini = 0               // 0 none, 1 Spore Catch, 2 Memory Match, 3 Hide & Seek
    private var miniOver = false
    private var miniScore = 0
    private var miniReward = 0
    private var miniT = 0f

    // Spore Catch
    private class Drop(var x: Float, var y: Float, val kind: Int, val vy: Float)   // kind: 0 spore, 1 star, 2 stinky
    private val drops = ArrayList<Drop>()
    private var catchX = 0f
    private var catchTarget = 0f
    private var dropTimer = 0f
    private var catchDizzy = 0f
    private var catchSpecies = 1

    // Memory Match
    private val mmCards = IntArray(16)
    private val mmOpen = BooleanArray(16)
    private val mmDone = BooleanArray(16)
    private val mmFlip = FloatArray(16)
    private var mmHide = 0f
    private var mmMoves = 0

    private fun startMini(kind: Int) {
        mini = kind; miniOver = false; miniScore = 0; miniReward = 0; miniT = 0f
        if (kind == 1) {
            drops.clear(); catchX = width / 2f; catchTarget = catchX; dropTimer = 0.5f; catchDizzy = 0f
            catchSpecies = lab.home().randomOrNull()?.species ?: 1
            miniT = 30f
        } else if (kind == 3) {
            hsRound = 0; hsNewRound()
        } else {
            val known = Data.DEX_ORDER.filter { lab.discovered[it] }.shuffled()
            val pool = (known + Data.SPECIES.indices.filter { Data.SPECIES[it].tier == 1 }.shuffled()).distinct().take(8)
            val deck = (pool + pool).shuffled()
            for (i in 0 until 16) { mmCards[i] = deck[i]; mmOpen[i] = false; mmDone[i] = false; mmFlip[i] = 0f }
            mmHide = 0f; mmMoves = 0
        }
        sound.play(Fx.POP)
    }

    /** Pays out mini-game dust, up to 150 ✦ a day. */
    private fun payMini(amount: Int): Int {
        val today = LabState.dayKey()
        val earned = if (prefs.getInt("mgDay", 0) == today) prefs.getInt("mgEarned", 0) else 0
        val pay = min(amount, max(0, 160 - earned))
        prefs.edit().putInt("mgDay", today).putInt("mgEarned", earned + pay).apply()
        lab.dust += pay
        markDirty()
        return pay
    }

    private fun endMini() {
        miniOver = true
        if (mini == 1) {
            val best = prefs.getInt("bestCatch", 0)
            if (miniScore > best) prefs.edit().putInt("bestCatch", miniScore).apply()
            miniReward = payMini(min(40, miniScore / 2))
        } else if (mini == 3) {
            if (miniScore > prefs.getInt("bestHide", 0)) prefs.edit().putInt("bestHide", miniScore).apply()
            miniReward = payMini(miniScore * 5)
        } else {
            val best = prefs.getInt("bestMemory", 999)
            if (mmMoves < best) prefs.edit().putInt("bestMemory", mmMoves).apply()
            miniReward = payMini(max(10, 50 - (mmMoves - 8) * 3))
        }
        sound.play(Fx.DISCOVER)
        burst(width / 2f, height * 0.4f, DUST, 40, P_SPARK)
    }

    private fun updateMini(dt: Float) {
        if (mini == 0 || miniOver) return
        if (mini == 3) { updateHide(dt); return }
        if (mini == 1) {
            miniT -= dt
            if (miniT <= 0f) { endMini(); return }
            if (catchDizzy > 0f) catchDizzy -= dt
            catchX += (catchTarget - catchX) * min(1f, dt * (if (catchDizzy > 0f) 3f else 12f))
            dropTimer -= dt
            val speedUp = 1f + (30f - miniT) / 25f
            if (dropTimer <= 0f) {
                dropTimer = (0.55f / speedUp) * (0.6f + Random.nextFloat() * 0.8f)
                val r = Random.nextFloat()
                val kind = when { r < 0.08f -> 1; r < 0.25f -> 2; else -> 0 }
                drops.add(Drop(30f * dp + Random.nextFloat() * (width - 60f * dp), topBar + 40f * dp, kind, (180f + Random.nextFloat() * 90f) * dp * speedUp))
            }
            val catchY = height * 0.8f - 50f * dp
            val it = drops.iterator()
            while (it.hasNext()) {
                val d = it.next()
                d.y += d.vy * dt
                if (d.y > catchY - 10f * dp && d.y < catchY + 30f * dp && abs(d.x - catchX) < 46f * dp) {
                    it.remove()
                    when (d.kind) {
                        0 -> { miniScore += 1; sound.play(Fx.COIN, 0.5f, 1f + (miniScore % 8) * 0.05f) }
                        1 -> { miniScore += 5; sound.play(Fx.GROW, 0.7f); burst(d.x, d.y, DUST, 14, P_SPARK) }
                        else -> { miniScore = max(0, miniScore - 3); catchDizzy = 0.8f; sound.play(Fx.SNEEZE, 0.6f); buzz(40) }
                    }
                    continue
                }
                if (d.y > height) it.remove()
            }
        } else {
            for (i in 0 until 16) {
                val target = if (mmOpen[i] || mmDone[i]) 1f else 0f
                mmFlip[i] += (target - mmFlip[i]) * min(1f, dt * 10f)
            }
            if (mmHide > 0f) {
                mmHide -= dt
                if (mmHide <= 0f) for (i in 0 until 16) if (!mmDone[i]) mmOpen[i] = false
            }
        }
    }

    private fun tapMemory(i: Int) {
        if (miniOver || mmDone[i] || mmOpen[i] || mmHide > 0f) return
        mmOpen[i] = true
        sound.play(Fx.POP, 0.6f, 1.2f)
        val open = (0 until 16).filter { mmOpen[it] && !mmDone[it] }
        if (open.size == 2) {
            mmMoves++
            val (a, b) = open
            if (mmCards[a] == mmCards[b]) {
                mmDone[a] = true; mmDone[b] = true
                sound.play(Fx.GROW, 0.7f)
                if (mmDone.all { it }) endMini()
            } else mmHide = 0.8f
        }
    }

    private fun miniButtons(list: ArrayList<Button>, w: Float, h: Float) {
        if (miniOver) {
            list.add(Button("mg_again", RectF(w / 2 - 150f * dp, h * 0.62f, w / 2 - 6f * dp, h * 0.62f + 52f * dp), "PLAY AGAIN", STYLE_PRIMARY) { startMini(mini) })
            list.add(Button("mg_done", RectF(w / 2 + 6f * dp, h * 0.62f, w / 2 + 150f * dp, h * 0.62f + 52f * dp), "DONE") { mini = 0 })
            return
        }
        list.add(Button("mg_quit", RectF(w - 98f * dp, 16f * dp, w - 14f * dp, 52f * dp), "QUIT") { mini = 0 })
        if (mini == 3) for (i in 0 until 9) {
            list.add(Button("hs$i", hideRect(i, w, h), "", STYLE_GHOST, custom = { c, rr -> drawHideCell(c, rr, i) }) { tapBush(i) })
        }
        if (mini == 2) {
            val gw = min(w - 32f * dp, 380f * dp)
            val cell = gw / 4
            val gx = w / 2 - gw / 2; val gy = topBar + 70f * dp
            for (i in 0 until 16) {
                val r = RectF(gx + (i % 4) * cell + 4f * dp, gy + (i / 4) * cell * 1.15f + 4f * dp, gx + (i % 4 + 1) * cell - 4f * dp, gy + (i / 4 + 1) * cell * 1.15f - 4f * dp)
                list.add(Button("mm$i", r, "", STYLE_CARD, custom = { c, rr -> drawMemoryCard(c, rr, i) }) { tapMemory(i) })
            }
        }
    }

    private fun drawMemoryCard(c: Canvas, r: RectF, i: Int) {
        val f = mmFlip[i]
        val sx = abs(1f - 2f * f)   // shrinks to 0 halfway through the flip
        c.save(); c.scale(max(0.05f, sx), 1f, r.centerX(), r.centerY())
        if (f < 0.5f) {
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(150, 110, 230), Color.rgb(100, 70, 180), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 12f * dp, 12f * dp, fill); fill.shader = null
            text.textSize = 26f * dp; text.color = Color.argb(200, 255, 255, 255)
            c.drawText("?", r.centerX(), r.centerY() + 9f * dp, text); text.color = Color.WHITE
        } else {
            fill.color = if (mmDone[i]) Color.rgb(60, 120, 95) else Color.rgb(245, 238, 255)
            c.drawRoundRect(r, 12f * dp, 12f * dp, fill)
            drawShroom(c, mmCards[i], r.centerX(), r.bottom - 10f * dp, r.width() * 0.55f, 0, 0f, false, false)
        }
        c.restore()
    }

    private fun drawMini(c: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(50, 32, 92), Color.rgb(24, 16, 44), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        text.textAlign = Paint.Align.LEFT; text.textSize = 22f * dp
        c.drawText(when (mini) { 1 -> "Spore Catch"; 3 -> "Hide & Seek"; else -> "Memory Match" }, 18f * dp, 42f * dp, text)
        text.textAlign = Paint.Align.CENTER
        if (mini == 1) {
            // timer bar + score
            fill.color = Color.argb(50, 255, 255, 255); c.drawRoundRect(18f * dp, 58f * dp, w - 18f * dp, 66f * dp, 4f * dp, 4f * dp, fill)
            fill.color = MINT; c.drawRoundRect(18f * dp, 58f * dp, 18f * dp + (w - 36f * dp) * (miniT / 30f).coerceIn(0f, 1f), 66f * dp, 4f * dp, 4f * dp, fill)
            text.textSize = 30f * dp; text.color = DUST
            c.drawText("$miniScore", w / 2, topBar + 50f * dp, text); text.color = Color.WHITE
            for (d in drops) {
                when (d.kind) {
                    0 -> { fill.shader = RadialGradient(d.x - 3f * dp, d.y - 3f * dp, 11f * dp, Color.rgb(255, 245, 190), DUST, Shader.TileMode.CLAMP)
                        c.drawCircle(d.x, d.y, 9f * dp, fill); fill.shader = null }
                    1 -> { fill.color = Color.rgb(255, 230, 120); drawSparkle(c, d.x, d.y, 6f * dp) }
                    else -> { fill.color = Color.rgb(140, 200, 80); c.drawCircle(d.x, d.y, 10f * dp, fill)
                        fill.color = Color.rgb(90, 140, 50); c.drawCircle(d.x - 3f * dp, d.y - 2f * dp, 2.5f * dp, fill); c.drawCircle(d.x + 3f * dp, d.y + 2f * dp, 2f * dp, fill) }
                }
            }
            val cy = h * 0.8f
            fill.color = Color.rgb(78, 140, 104); c.drawRect(0f, cy, w, h, fill)
            val face = if (catchDizzy > 0f) E_DIZZY else E_GIGGLE
            professor.expr = 0
            val catcher = Shroom(-5L, catchSpecies).apply { expr = face; exprT = 1f; xp = 200f }
            drawShroom(c, catchSpecies, catchX, cy, 80f * dp, 0, sin(t * 6f), false, false, who = catcher)
            if (!miniOver) { text.textSize = 13f * dp; text.color = TEXT_DIM
                c.drawText("Drag to catch golden spores ✦ · avoid the stinky green ones!", w / 2, h - 30f * dp, text); text.color = Color.WHITE }
        } else if (mini == 3) {
            fill.color = Color.rgb(78, 140, 104)
            c.drawRect(0f, topBar + 70f * dp, w, h, fill)
            text.textSize = 14f * dp; text.color = TEXT_DIM
            c.drawText("Round ${min(hsRound, 3)}/3 · taps left: $hsTaps · found: $miniScore", w / 2, topBar + 50f * dp, text)
            if (!miniOver) c.drawText("Watch for rustling bushes, then tap to peek!", w / 2, h - 30f * dp, text)
            text.color = Color.WHITE
        } else {
            text.textSize = 14f * dp; text.color = TEXT_DIM
            c.drawText("Find the matching mushrooms · moves: $mmMoves", w / 2, topBar + 50f * dp, text); text.color = Color.WHITE
        }
        if (miniOver) {
            fill.color = Color.argb(170, 10, 6, 24); c.drawRect(0f, 0f, w, h, fill)
            panel(c, RectF(28f * dp, h * 0.3f, w - 28f * dp, h * 0.6f + 70f * dp), 24f * dp, PANEL_LIGHT)
            text.textSize = 28f * dp
            c.drawText(when (mini) { 1 -> "Score: $miniScore"; 3 -> "Found $miniScore of 9!"; else -> "Done in $mmMoves moves!" }, w / 2, h * 0.38f, text)
            text.textSize = 15f * dp; text.color = TEXT_DIM
            val best = when (mini) { 1 -> "Best: ${prefs.getInt("bestCatch", 0)}"; 3 -> "Best: ${prefs.getInt("bestHide", 0)} found"; else -> "Best: ${prefs.getInt("bestMemory", 0)} moves" }
            c.drawText(best, w / 2, h * 0.38f + 28f * dp, text)
            text.textSize = 20f * dp; text.color = DUST
            c.drawText(if (miniReward > 0) "+$miniReward ✦" else "Daily mini-game dust limit reached", w / 2, h * 0.5f, text)
            text.color = Color.WHITE
        }
        for (b in buttons) drawButton(c, b)
    }

    private fun drawGamesTab(c: Canvas, w: Float, top: Float) {
        val pad = 16f * dp
        val r3 = RectF(pad, top + 2 * 158f * dp, w - pad, top + 2 * 158f * dp + 148f * dp)
        panel(c, r3, 20f * dp)
        text.textAlign = Paint.Align.LEFT; text.textSize = 19f * dp
        c.drawText("Hide & Seek", r3.left + 120f * dp, r3.top + 34f * dp, text)
        text.textSize = 12f * dp; text.color = TEXT_DIM
        for ((k, l) in wrap("Your mushrooms hide in the bushes. Watch for rustling leaves and find them!", r3.width() - 140f * dp).withIndex())
            c.drawText(l, r3.left + 120f * dp, r3.top + 54f * dp + k * 16f * dp, text)
        text.color = DUST
        c.drawText("Best: ${prefs.getInt("bestHide", 0)} / 9 found", r3.left + 120f * dp, r3.top + 96f * dp, text)
        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
        drawBush(c, r3.left + 60f * dp, r3.top + 110f * dp, 46f * dp, sin(t * 9f) * (if ((t % 3f) < 0.6f) 1f else 0f))
        drawShroom(c, 1, r3.left + 78f * dp, r3.top + 92f * dp - abs(sin(t * 1.5f)) * 10f * dp, 26f * dp, 0, 0f, false, false)
        for (g in 0..1) {
            val r = RectF(pad, top + g * 158f * dp, w - pad, top + g * 158f * dp + 148f * dp)
            panel(c, r, 20f * dp)
            text.textAlign = Paint.Align.LEFT
            text.textSize = 19f * dp
            c.drawText(if (g == 0) "Spore Catch" else "Memory Match", r.left + 120f * dp, r.top + 38f * dp, text)
            text.textSize = 12f * dp; text.color = TEXT_DIM
            val lines = wrap(if (g == 0) "Catch falling golden spores in 30 seconds. Dodge the stinky ones!" else "Flip cards to find the matching mushroom pairs in as few moves as you can.", r.width() - 140f * dp)
            for ((k, l) in lines.withIndex()) c.drawText(l, r.left + 120f * dp, r.top + 60f * dp + k * 16f * dp, text)
            text.color = DUST
            c.drawText(if (g == 0) "Best: ${prefs.getInt("bestCatch", 0)}" else "Best: ${prefs.getInt("bestMemory", 999).let { if (it == 999) "-" else "$it moves" }}", r.left + 120f * dp, r.top + 108f * dp, text)
            text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
            if (g == 0) {
                fill.shader = RadialGradient(r.left + 60f * dp, r.top + 70f * dp, 14f * dp, Color.rgb(255, 245, 190), DUST, Shader.TileMode.CLAMP)
                c.drawCircle(r.left + 60f * dp, r.top + 50f * dp + abs(sin(t * 3f)) * 20f * dp, 9f * dp, fill); fill.shader = null
                drawShroom(c, 2, r.left + 60f * dp, r.top + 130f * dp, 60f * dp, 0, sin(t * 3f), false, false)
            } else {
                for (k in 0..1) {
                    val cr = RectF(r.left + 22f * dp + k * 44f * dp, r.top + 50f * dp, r.left + 60f * dp + k * 44f * dp, r.top + 100f * dp)
                    fill.color = if (k == 0) Color.rgb(150, 110, 230) else Color.rgb(245, 238, 255)
                    c.drawRoundRect(cr, 8f * dp, 8f * dp, fill)
                    if (k == 1) drawShroom(c, 6, cr.centerX(), cr.bottom - 6f * dp, 26f * dp, 0, 0f, false, false)
                    else { text.textSize = 18f * dp; c.drawText("?", cr.centerX(), cr.centerY() + 6f * dp, text) }
                }
            }
        }
        text.textSize = 11f * dp; text.color = TEXT_DIM
        c.drawText("Mini-games pay up to 160 ✦ a day", w / 2, top + 3 * 158f * dp + 10f * dp, text)
        text.color = Color.WHITE
    }

    private fun drawDiary(c: Canvas, w: Float, top: Float) {
        val pad = 18f * dp
        if (lab.diary.isEmpty()) {
            text.textSize = 14f * dp; text.color = TEXT_DIM
            c.drawText("Your garden's story will be written here.", w / 2, top + 60f * dp, text)
            text.color = Color.WHITE
            return
        }
        var y = top + 10f * dp
        text.textAlign = Paint.Align.LEFT
        for (entry in lab.diary) {
            val (date, body) = entry.split(" · ", limit = 2).let { if (it.size == 2) Pair(it[0], it[1]) else Pair("", entry) }
            text.textSize = 13.5f * dp
            val lines = wrap(body, w - pad * 2 - 56f * dp)
            val bh = lines.size * 18f * dp + 14f * dp
            if (y + bh > navTop - 8f * dp) break
            fill.color = Color.argb(40, 255, 255, 255)
            c.drawRoundRect(pad - 6f * dp, y, w - pad + 6f * dp, y + bh, 12f * dp, 12f * dp, fill)
            text.textSize = 11f * dp; text.color = ACCENT
            c.drawText(date, pad, y + 20f * dp, text)
            text.textSize = 13.5f * dp; text.color = Color.WHITE
            for ((k, l) in lines.withIndex()) c.drawText(l, pad + 56f * dp, y + 20f * dp + k * 18f * dp, text)
            y += bh + 6f * dp
        }
        text.textAlign = Paint.Align.CENTER
    }

    // ---------- Seasonal events ----------

    private fun eventNow() = Data.currentEvent()

    /** Shown once per event per year, with a free welcome gift. */
    private fun eventWelcome() {
        val ev = eventNow()
        if (ev == 0) return
        val key = "eventSeen_${ev}_${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val gift = Data.ACCESSORIES.indices.first { Data.ACCESSORIES[it].event == ev }
        lab.accOwned[gift]++
        markDirty()
        toast("${Data.EVENT_ICONS[ev]} ${Data.EVENTS[ev]} has begun! Here's a free ${Data.ACCESSORIES[gift].name}. Check the Shop → EVENT tab!", DUST)
    }

    /** During Shroomoween, a mushroom in costume brings you a treat once a day when you tap it. */
    private fun trickOrTreat(s: Shroom) {
        if (eventNow() != 1) return
        if (s.wear.none { it >= 0 && Data.ACCESSORIES[it].event == 1 }) return
        val key = "treat_${s.id}"
        val today = LabState.dayKey()
        if (prefs.getInt(key, 0) == today) return
        prefs.edit().putInt(key, today).apply()
        lab.foods[5]++
        val (x, y) = shroomScreen(s)
        burst(x, y - shroomSize(s), Color.rgb(255, 170, 60), 14, P_SPARK)
        toast("🍬 Trick or treat! ${s.name} found you a Candy Corn!", Color.rgb(255, 170, 60))
        markDirty()
    }

    private fun drawEventDecor(c: Canvas, kind: Int, x: Float, y: Float, s: Float) {
        when (kind) {
            16 -> { // jack-o'-lantern
                for ((k, ox) in floatArrayOf(-0.18f, 0.18f, 0f).withIndex()) {
                    fill.shader = RadialGradient(x + ox * s - s * 0.08f, y - s * 0.35f, s * 0.4f, Color.rgb(255, 170, 60), Color.rgb(220, 100, 20), Shader.TileMode.CLAMP)
                    c.drawOval(x + ox * s - s * 0.24f, y - s * 0.52f, x + ox * s + s * 0.24f, y, fill)
                    fill.shader = null
                    if (k == 2) break
                }
                fill.color = Color.rgb(90, 130, 50); c.drawRect(x - s * 0.03f, y - s * 0.62f, x + s * 0.03f, y - s * 0.48f, fill)
                val glow = Color.rgb(255, 230, 120 + (40 * sin(t * 9f)).toInt())
                fill.color = glow
                path.reset(); path.moveTo(x - s * 0.2f, y - s * 0.33f); path.lineTo(x - s * 0.1f, y - s * 0.4f); path.lineTo(x - s * 0.08f, y - s * 0.3f); path.close()
                path.moveTo(x + s * 0.2f, y - s * 0.33f); path.lineTo(x + s * 0.1f, y - s * 0.4f); path.lineTo(x + s * 0.08f, y - s * 0.3f); path.close()
                c.drawPath(path, fill)
                c.drawArc(x - s * 0.18f, y - s * 0.28f, x + s * 0.18f, y - s * 0.08f, 0f, 180f, true, fill)
            }
            17 -> { // spooky tree
                stroke.color = Color.rgb(50, 35, 50); stroke.strokeWidth = s * 0.1f
                c.drawLine(x, y, x - s * 0.05f, y - s * 0.9f, stroke)
                stroke.strokeWidth = s * 0.05f
                c.drawLine(x - s * 0.04f, y - s * 0.55f, x - s * 0.45f, y - s * 0.85f, stroke)
                c.drawLine(x - s * 0.05f, y - s * 0.7f, x + s * 0.35f, y - s * 1.05f, stroke)
                c.drawLine(x - s * 0.3f, y - s * 0.75f, x - s * 0.4f, y - s * 1.05f, stroke)
                c.drawLine(x + s * 0.2f, y - s * 0.88f, x + s * 0.45f, y - s * 0.88f, stroke)
                fill.color = Color.rgb(25, 15, 25); c.drawOval(x - s * 0.08f, y - s * 0.45f, x + s * 0.06f, y - s * 0.28f, fill)
                if ((t % 4f) > 0.15f) {
                    fill.color = Color.rgb(200, 150, 255)
                    c.drawCircle(x - s * 0.04f, y - s * 0.38f, s * 0.02f, fill); c.drawCircle(x + s * 0.02f, y - s * 0.38f, s * 0.02f, fill)
                }
            }
            18 -> { // tombstone (a friendly one)
                fill.shader = LinearGradient(x - s * 0.25f, 0f, x + s * 0.25f, 0f, Color.rgb(170, 170, 185), Color.rgb(110, 110, 125), Shader.TileMode.CLAMP)
                c.drawRoundRect(x - s * 0.25f, y - s * 0.6f, x + s * 0.25f, y, s * 0.22f, s * 0.22f, fill)
                fill.shader = null
                text.textSize = s * 0.15f; text.color = Color.rgb(70, 70, 85)
                c.drawText("BOO!", x, y - s * 0.33f, text); text.color = Color.WHITE
                fill.color = Color.rgb(100, 170, 90)
                c.drawOval(x - s * 0.32f, y - s * 0.06f, x + s * 0.32f, y + s * 0.04f, fill)
            }
            19 -> { // candy bowl
                val cols = intArrayOf(Color.rgb(255, 120, 150), Color.rgb(255, 200, 80), Color.rgb(140, 220, 255), Color.rgb(170, 130, 255), Color.rgb(140, 230, 140))
                for (k in 0 until 7) { fill.color = cols[k % cols.size]; c.drawCircle(x + (k - 3) * s * 0.07f, y - s * 0.3f - (k % 2) * s * 0.05f, s * 0.06f, fill) }
                fill.shader = LinearGradient(0f, y - s * 0.3f, 0f, y, Color.rgb(255, 150, 50), Color.rgb(200, 90, 20), Shader.TileMode.CLAMP)
                c.drawArc(x - s * 0.3f, y - s * 0.55f, x + s * 0.3f, y, 0f, 180f, true, fill); fill.shader = null
                stroke.color = Color.rgb(40, 30, 40); stroke.strokeWidth = 1.5f * dp
                c.drawArc(x - s * 0.12f, y - s * 0.2f, x + s * 0.12f, y - s * 0.06f, 20f, 140f, false, stroke)
            }
            20 -> { // snowman
                fill.color = Color.WHITE
                c.drawCircle(x, y - s * 0.22f, s * 0.24f, fill); c.drawCircle(x, y - s * 0.58f, s * 0.17f, fill)
                fill.color = Color.rgb(40, 40, 50)
                c.drawCircle(x - s * 0.06f, y - s * 0.62f, s * 0.025f, fill); c.drawCircle(x + s * 0.06f, y - s * 0.62f, s * 0.025f, fill)
                c.drawCircle(x, y - s * 0.3f, s * 0.025f, fill); c.drawCircle(x, y - s * 0.18f, s * 0.025f, fill)
                fill.color = Color.rgb(255, 140, 40)
                path.reset(); path.moveTo(x, y - s * 0.57f); path.lineTo(x + s * 0.16f, y - s * 0.55f); path.lineTo(x, y - s * 0.53f); path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(220, 50, 60); c.drawRoundRect(x - s * 0.18f, y - s * 0.46f, x + s * 0.18f, y - s * 0.4f, s * 0.03f, s * 0.03f, fill)
            }
            21 -> { // gift box
                fill.color = Color.rgb(220, 60, 80); c.drawRect(x - s * 0.22f, y - s * 0.4f, x + s * 0.22f, y, fill)
                fill.color = Color.rgb(240, 90, 110); c.drawRect(x - s * 0.25f, y - s * 0.48f, x + s * 0.25f, y - s * 0.38f, fill)
                fill.color = Color.rgb(255, 220, 90)
                c.drawRect(x - s * 0.04f, y - s * 0.48f, x + s * 0.04f, y, fill)
                c.drawOval(x - s * 0.2f, y - s * 0.6f, x, y - s * 0.46f, fill); c.drawOval(x, y - s * 0.6f, x + s * 0.2f, y - s * 0.46f, fill)
            }
            22 -> { // flower arch
                stroke.color = Color.rgb(90, 160, 90); stroke.strokeWidth = s * 0.07f
                c.drawArc(x - s * 0.55f, y - s * 1.2f, x + s * 0.55f, y + s * 0.3f, 180f, 180f, false, stroke)
                c.drawLine(x - s * 0.55f, y - s * 0.45f, x - s * 0.55f, y, stroke); c.drawLine(x + s * 0.55f, y - s * 0.45f, x + s * 0.55f, y, stroke)
                val cols = intArrayOf(Color.rgb(255, 150, 190), Color.WHITE, Color.rgb(255, 220, 110), Color.rgb(200, 160, 255))
                for (k in 0..10) {
                    val a = Math.PI + k * Math.PI / 10
                    val fx = x + (cos(a) * s * 0.55f).toFloat(); val fy = y - s * 0.45f + (sin(a) * s * 0.75f).toFloat()
                    fill.color = cols[k % cols.size]; c.drawCircle(fx, fy, s * 0.06f, fill)
                }
            }
            else -> { // egg basket
                val eggs = intArrayOf(Color.rgb(255, 190, 220), Color.rgb(190, 230, 255), Color.rgb(255, 240, 160), Color.rgb(200, 255, 200))
                for (k in 0 until 4) { fill.color = eggs[k]; c.drawOval(x - s * 0.24f + k * s * 0.12f, y - s * 0.42f, x - s * 0.12f + k * s * 0.12f, y - s * 0.22f, fill) }
                fill.shader = LinearGradient(0f, y - s * 0.3f, 0f, y, Color.rgb(210, 160, 100), Color.rgb(150, 100, 60), Shader.TileMode.CLAMP)
                c.drawRoundRect(x - s * 0.3f, y - s * 0.3f, x + s * 0.3f, y, s * 0.08f, s * 0.08f, fill); fill.shader = null
                stroke.color = Color.rgb(150, 100, 60); stroke.strokeWidth = s * 0.03f
                c.drawArc(x - s * 0.25f, y - s * 0.6f, x + s * 0.25f, y - s * 0.1f, 180f, 180f, false, stroke)
            }
        }
    }

    private fun drawEventAccessory(c: Canvas, a: Int, cx: Float, capTop: Float, faceY: Float, neckY: Float, s: Float, ex: Float) {
        when (a) {
            14 -> { // pumpkin hat
                fill.shader = RadialGradient(cx - s * 0.06f, capTop - s * 0.1f, s * 0.25f, Color.rgb(255, 180, 70), Color.rgb(230, 110, 20), Shader.TileMode.CLAMP)
                c.drawOval(cx - s * 0.2f, capTop - s * 0.25f, cx + s * 0.2f, capTop + s * 0.06f, fill); fill.shader = null
                stroke.color = Color.rgb(210, 100, 20); stroke.strokeWidth = s * 0.015f
                c.drawLine(cx, capTop - s * 0.24f, cx, capTop + s * 0.05f, stroke)
                fill.color = Color.rgb(90, 130, 50); c.drawRect(cx - s * 0.025f, capTop - s * 0.33f, cx + s * 0.025f, capTop - s * 0.22f, fill)
                c.drawOval(cx + s * 0.02f, capTop - s * 0.32f, cx + s * 0.12f, capTop - s * 0.26f, fill)
            }
            15 -> { // witch hat
                fill.color = Color.rgb(45, 30, 60)
                path.reset(); path.moveTo(cx - s * 0.18f, capTop + s * 0.04f)
                path.quadTo(cx, capTop - s * 0.35f, cx - s * 0.12f, capTop - s * 0.52f)
                path.quadTo(cx + s * 0.08f, capTop - s * 0.25f, cx + s * 0.18f, capTop + s * 0.04f); path.close()
                c.drawPath(path, fill)
                c.drawOval(cx - s * 0.36f, capTop - s * 0.01f, cx + s * 0.36f, capTop + s * 0.08f, fill)
                fill.color = Color.rgb(160, 90, 220); c.drawRect(cx - s * 0.17f, capTop - s * 0.06f, cx + s * 0.17f, capTop, fill)
                fill.color = Color.rgb(255, 220, 90); c.drawRect(cx - s * 0.03f, capTop - s * 0.07f, cx + s * 0.03f, capTop + s * 0.01f, fill)
            }
            16 -> { // ghost sheet over the whole mushroom, with eye holes
                fill.color = Color.argb(240, 250, 250, 255)
                path.reset()
                path.moveTo(cx - s * 0.55f, neckY + s * 0.32f)
                path.cubicTo(cx - s * 0.6f, capTop - s * 0.2f, cx + s * 0.6f, capTop - s * 0.2f, cx + s * 0.55f, neckY + s * 0.32f)
                for (k in 0..5) {
                    val px = cx + s * 0.55f - (k + 1) * s * 1.1f / 6f
                    path.quadTo(px + s * 0.09f, neckY + s * (if (k % 2 == 0) 0.42f else 0.26f) + sin(t * 3f + k) * s * 0.02f, px, neckY + s * 0.32f)
                }
                path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(40, 30, 50)
                c.drawOval(cx - ex - s * 0.05f, faceY - s * 0.07f, cx - ex + s * 0.05f, faceY + s * 0.06f, fill)
                c.drawOval(cx + ex - s * 0.05f, faceY - s * 0.07f, cx + ex + s * 0.05f, faceY + s * 0.06f, fill)
                c.drawOval(cx - s * 0.03f, faceY + s * 0.09f, cx + s * 0.03f, faceY + s * 0.15f, fill)
            }
            17 -> { // bat wings
                val flap = sin(t * 5f) * 0.15f
                fill.color = Color.rgb(60, 40, 70)
                for (side in intArrayOf(-1, 1)) {
                    c.save(); c.rotate(side * (20f + flap * 60f), cx + side * s * 0.15f, neckY + s * 0.1f)
                    path.reset(); path.moveTo(cx + side * s * 0.15f, neckY + s * 0.05f)
                    path.lineTo(cx + side * s * 0.55f, neckY - s * 0.15f)
                    path.lineTo(cx + side * s * 0.5f, neckY + s * 0.05f)
                    path.lineTo(cx + side * s * 0.4f, neckY + s * 0.0f)
                    path.lineTo(cx + side * s * 0.33f, neckY + s * 0.15f)
                    path.lineTo(cx + side * s * 0.22f, neckY + s * 0.12f); path.close()
                    c.drawPath(path, fill); c.restore()
                }
            }
            18 -> { // santa hat
                fill.color = Color.rgb(220, 40, 50)
                path.reset(); path.moveTo(cx - s * 0.24f, capTop + s * 0.06f)
                path.quadTo(cx - s * 0.05f, capTop - s * 0.35f, cx + s * 0.3f, capTop - s * 0.18f)
                path.lineTo(cx + s * 0.24f, capTop + s * 0.06f); path.close(); c.drawPath(path, fill)
                fill.color = Color.WHITE
                c.drawRoundRect(cx - s * 0.27f, capTop + s * 0.01f, cx + s * 0.27f, capTop + s * 0.1f, s * 0.05f, s * 0.05f, fill)
                c.drawCircle(cx + s * 0.31f, capTop - s * 0.17f, s * 0.06f, fill)
            }
            19 -> { // antlers
                stroke.color = Color.rgb(150, 100, 60); stroke.strokeWidth = s * 0.04f
                for (side in intArrayOf(-1, 1)) {
                    val bx = cx + side * s * 0.14f
                    c.drawLine(bx, capTop + s * 0.04f, bx + side * s * 0.12f, capTop - s * 0.3f, stroke)
                    c.drawLine(bx + side * s * 0.06f, capTop - s * 0.13f, bx + side * s * 0.2f, capTop - s * 0.17f, stroke)
                    c.drawLine(bx + side * s * 0.1f, capTop - s * 0.24f, bx + side * s * 0.02f, capTop - s * 0.33f, stroke)
                }
            }
            20 -> { // bunny ears
                for (side in intArrayOf(-1, 1)) {
                    c.save(); c.rotate(side * (12f + 4f * sin(t * 2f + side)), cx + side * s * 0.1f, capTop + s * 0.05f)
                    fill.color = Color.rgb(250, 245, 250)
                    c.drawOval(cx + side * s * 0.1f - s * 0.07f, capTop - s * 0.42f, cx + side * s * 0.1f + s * 0.07f, capTop + s * 0.08f, fill)
                    fill.color = Color.rgb(255, 180, 200)
                    c.drawOval(cx + side * s * 0.1f - s * 0.035f, capTop - s * 0.35f, cx + side * s * 0.1f + s * 0.035f, capTop, fill)
                    c.restore()
                }
            }
            else -> { // daisy clip
                val fx = cx + s * 0.28f; val fy = capTop + s * 0.12f
                fill.color = Color.WHITE
                for (k in 0 until 8) { val an = k * 0.785f; c.drawOval(fx + cos(an) * s * 0.06f - s * 0.04f, fy + sin(an) * s * 0.06f - s * 0.025f,
                    fx + cos(an) * s * 0.06f + s * 0.04f, fy + sin(an) * s * 0.06f + s * 0.025f, fill) }
                fill.color = Color.rgb(255, 210, 60); c.drawCircle(fx, fy, s * 0.04f, fill)
            }
        }
    }

    private fun drawBatsAndFog(c: Canvas, w: Float, hz: Float) {
        fill.color = Color.rgb(30, 20, 40)
        for (k in 0 until 4) {
            val bx = ((k * 0.31f + t * 0.03f * (1 + k % 2)) % 1.2f) * w - 0.1f * w
            val by = hz * (0.25f + 0.15f * k) + sin(t * 2f + k) * 10f * dp
            val flap = sin(t * 14f + k) * 5f * dp
            path.reset()
            path.moveTo(bx, by)
            path.quadTo(bx - 7f * dp, by - 4f * dp - flap, bx - 14f * dp, by - flap)
            path.quadTo(bx - 8f * dp, by + 1f * dp, bx, by + 3f * dp)
            path.quadTo(bx + 8f * dp, by + 1f * dp, bx + 14f * dp, by - flap)
            path.quadTo(bx + 7f * dp, by - 4f * dp - flap, bx, by)
            path.close(); c.drawPath(path, fill)
        }
        fill.shader = LinearGradient(0f, hz - 20f * dp, 0f, hz + 50f * dp, Color.argb(0, 200, 180, 220), Color.argb(70, 200, 180, 220), Shader.TileMode.CLAMP)
        c.drawRect(0f, hz - 20f * dp, w, hz + 50f * dp, fill)
        fill.shader = null
    }

    private fun drawEventFood(c: Canvas, f: Int, x: Float, y: Float, r: Float) {
        when (f) {
            5 -> { // candy corn
                val cols = intArrayOf(Color.WHITE, Color.rgb(255, 170, 50), Color.rgb(255, 230, 120))
                for ((k, col) in cols.withIndex()) {
                    fill.color = col
                    val t0 = y - r + k * r * 0.66f; val t1 = t0 + r * 0.66f
                    val w0 = r * 0.25f + k * r * 0.25f; val w1 = r * 0.25f + (k + 1) * r * 0.25f
                    path.reset(); path.moveTo(x - w0, t0); path.lineTo(x + w0, t0); path.lineTo(x + w1, t1); path.lineTo(x - w1, t1); path.close()
                    c.drawPath(path, fill)
                }
            }
            6 -> { // hot cocoa
                fill.color = Color.rgb(240, 240, 250)
                c.drawRoundRect(x - r * 0.65f, y - r * 0.5f, x + r * 0.55f, y + r * 0.8f, r * 0.2f, r * 0.2f, fill)
                stroke.color = Color.rgb(240, 240, 250); stroke.strokeWidth = r * 0.18f
                c.drawArc(x + r * 0.35f, y - r * 0.2f, x + r * 0.95f, y + r * 0.45f, -90f, 180f, false, stroke)
                fill.color = Color.rgb(140, 90, 60); c.drawOval(x - r * 0.55f, y - r * 0.55f, x + r * 0.45f, y - r * 0.3f, fill)
                stroke.color = Color.argb(150, 255, 255, 255); stroke.strokeWidth = r * 0.08f
                c.drawLine(x - r * 0.15f, y - r * 0.7f, x - r * 0.05f, y - r * 1.05f, stroke); c.drawLine(x + r * 0.15f, y - r * 0.7f, x + r * 0.25f, y - r * 1.05f, stroke)
            }
            else -> { // strawberry
                fill.color = Color.rgb(240, 70, 90)
                path.reset(); path.moveTo(x - r * 0.75f, y - r * 0.4f); path.quadTo(x, y - r * 0.75f, x + r * 0.75f, y - r * 0.4f)
                path.quadTo(x + r * 0.6f, y + r * 0.6f, x, y + r * 0.95f); path.quadTo(x - r * 0.6f, y + r * 0.6f, x - r * 0.75f, y - r * 0.4f); path.close()
                c.drawPath(path, fill)
                fill.color = Color.rgb(255, 230, 140)
                for ((sx, sy) in arrayOf(-0.3f to -0.1f, 0.25f to -0.05f, 0f to 0.25f, -0.15f to 0.5f, 0.2f to 0.45f)) c.drawCircle(x + sx * r, y + sy * r, r * 0.06f, fill)
                fill.color = Color.rgb(90, 180, 80)
                for (k in -1..1) c.drawOval(x + k * r * 0.25f - r * 0.18f, y - r * 0.75f, x + k * r * 0.25f + r * 0.18f, y - r * 0.45f, fill)
            }
        }
    }

    // ---------- Touching mushrooms: poke, hug, pet, toss ----------

    private fun voicePitch(s: Shroom) = (1.55f - s.stage() * 0.15f - (s.size - 1f) * 0.9f).coerceIn(0.6f, 1.9f)

    private fun express(s: Shroom, e: Int, secs: Float) { s.expr = e; s.exprT = secs }

    private fun addBond(s: Shroom, amount: Float) {
        val before = s.hearts()
        s.bond = min(100f, s.bond + amount)
        if (s.hearts() > before) {
            toast("💗 You and ${s.name} are closer! (${s.hearts()} hearts)" + when (s.hearts()) {
                2 -> " Double-tap it to see a spin!"; 3 -> " It'll dance on a double-tap!"; 4 -> " It waves at you now!"; else -> "" }, ACCENT)
            sound.play(Fx.PET, 1f, 1.2f)
            lab.addDiary("You and ${s.name} became closer friends (${s.hearts()} hearts).")
        }
        markDirty()
    }

    private fun shroomScreen(s: Shroom): Pair<Float, Float> {
        actPose(s)?.let { return Pair(it[0], it[1] - s.airH) }
        val (x, y) = toScreen(s.gx, s.gz)
        return Pair(x, y - actLift(s) - s.airH)
    }

    /** A quick tap: wobble, giggle, and maybe a trick if you double-tap a close friend. */
    private fun poke(s: Shroom) {
        trickOrTreat(s)
        val double = t - s.lastPoke < 0.35f
        s.lastPoke = t
        if (!double && Random.nextFloat() < 0.5f) { s.thought = thoughtFor(s); s.thoughtT = 3.4f }
        s.jiggle = 1f
        express(s, E_GIGGLE, 0.8f)
        sound.play(Fx.GIGGLE, 0.7f, voicePitch(s))
        addBond(s, 0.2f)
        if (double) {
            when {
                s.hearts() >= 4 -> { s.waveT = 1.6f; val (x, y) = shroomScreen(s); burst(x, y - shroomSize(s), ACCENT, 6, P_HEART) }
                s.hearts() >= 3 -> startAct(s, ACT_DANCE, -1).also { s.actTimer = 3f }
                s.hearts() >= 2 -> { s.spinT = 0.6f; sound.play(Fx.WHEE, 0.5f, 1.4f) }
            }
        }
    }

    private fun hug(s: Shroom) {
        val now = System.currentTimeMillis()
        express(s, E_BLISS, 2.5f)
        s.jiggle = 0.6f
        val (x, y) = shroomScreen(s)
        burst(x, y - shroomSize(s) * 0.9f, ACCENT, 8, P_HEART)
        sound.play(Fx.PURR, 0.8f, voicePitch(s) * 0.8f)
        buzz(30)
        if (now - s.hugAt > 15_000L) {
            s.hugAt = now
            s.happy = min(100f, s.happy + 8f)
            addBond(s, 2f)
        }
        if (s.want == 2) fulfillWant(s)
        if (s.act == ACT_SLEEP || s.act == ACT_NAP) { s.act = 0; s.sleeping = false; s.awakeUntil = now + 120_000L }
    }

    private fun petStroke(s: Shroom) {
        s.jiggle = max(s.jiggle, 0.35f)
        express(s, E_BLISS, 1.2f)
        s.happy = min(100f, s.happy + 2f)
        addBond(s, 0.5f)
        val (x, y) = shroomScreen(s)
        burst(x + (Random.nextFloat() - 0.5f) * 30f * dp, y - shroomSize(s), ACCENT, 1, P_HEART)
        if (t - lastPurr > 1.2f) { lastPurr = t; sound.play(Fx.PURR, 0.6f, voicePitch(s) * 0.8f) }
        if (s.want == 2) fulfillWant(s)
    }

    /** Throw a mushroom: it flies, bounces softly and squeals with joy. */
    private fun toss(s: Shroom, vx: Float, vy: Float) {
        val w = width.toFloat()
        s.fvx = vx / (w * 0.86f) * 0.55f
        s.fvz = (vy / (groundBottom() - groundTop())) * 0.35f
        s.airV = (300f * dp + min(abs(vy), 1800f * dp) * 0.2f)   // a fun hop, not a rocket launch
        s.airH = 1f
        s.bounces = 0
        s.act = 0; s.pendingAct = 0; s.moving = false
        express(s, E_GIGGLE, 2f)
        sound.play(Fx.WHEE, 0.8f, voicePitch(s))
        if (s.want == 3) fulfillWant(s)
    }

    private fun updateAir(s: Shroom, dt: Float) {
        if (!s.airborne()) return
        s.airV -= 1700f * dp * dt
        s.airH += s.airV * dt
        s.gx += s.fvx * dt; s.gz += s.fvz * dt
        if (s.gx < 0.05f || s.gx > LabState.WORLD - 0.05f) { s.fvx = -s.fvx * 0.7f; s.gx = s.gx.coerceIn(0.05f, LabState.WORLD - 0.05f) }
        if (s.gz < 0f || s.gz > 1f) { s.fvz = -s.fvz * 0.7f; s.gz = s.gz.coerceIn(0f, 1f) }
        if (s.airH <= 0f) {
            s.airH = 0f
            if (s.airV < -160f * dp && s.bounces < 4) {
                s.airV = -s.airV * 0.45f
                s.fvx *= 0.6f; s.fvz *= 0.6f
                s.bounces++
                s.jiggle = 1f
                sound.play(Fx.BOING, 0.5f, voicePitch(s))
                val (x, y) = toScreen(s.gx, s.gz)
                burst(x, y, Color.rgb(200, 240, 200), 5, P_SPARK)
            } else {
                s.airV = 0f; s.fvx = 0f; s.fvz = 0f
                s.tx = s.gx; s.tz = s.gz
                s.idle = 1.5f
                if (s.bounces >= 3) express(s, E_DIZZY, 1.6f)
            }
        }
    }

    // ---------- Wants ----------

    private fun updateWants(now: Long) {
        for (s in lab.home()) {
            if (s.sleeping) continue
            if (s.want == 0) {
                if (s.nextWantAt == 0L) s.nextWantAt = now + (60_000L + Random.nextLong(180_000L))
                if (now >= s.nextWantAt) {
                    s.want = 1 + Random.nextInt(3)
                    s.wantFood = if (Random.nextFloat() < 0.6f) Data.SPECIES[s.species].favFood else Random.nextInt(5)
                    s.wantUntil = now + 240_000L
                }
            } else if (now > s.wantUntil) {
                s.want = 0
                s.nextWantAt = now + 180_000L + Random.nextLong(300_000L)
                s.happy = max(0f, s.happy - 3f)
            }
        }
    }

    private fun fulfillWant(s: Shroom) {
        val reward = 5 + s.hearts() * 3
        s.want = 0
        s.nextWantAt = System.currentTimeMillis() + 240_000L + Random.nextLong(360_000L)
        lab.dust += reward
        s.happy = min(100f, s.happy + 10f)
        addBond(s, 5f)
        express(s, E_HEART, 2.5f)
        val (x, y) = shroomScreen(s)
        burst(x, y - shroomSize(s), ACCENT, 10, P_HEART)
        flyDust(x, y - shroomSize(s), 6)
        sound.play(Fx.GIGGLE, 0.8f, voicePitch(s))
        toast("${s.name} got just what it wanted! +$reward ✦", ACCENT)
        markDirty()
    }

    private fun wantText(s: Shroom) = when (s.want) {
        1 -> "Wants: ${Data.FOODS[s.wantFood].name}"
        2 -> "Wants: a hug (press and hold it)"
        3 -> "Wants: to play (toss it!)"
        else -> null
    }

    private fun drawWantBubble(c: Canvas, s: Shroom, x: Float, top: Float) {
        if (s.want == 0 || s.sleeping) return
        val bob = sin(t * 3f + s.id) * 3f * dp
        val bx = x + 18f * dp; val by = top - 26f * dp + bob
        fill.color = Color.WHITE
        c.drawCircle(x + 4f * dp, top - 4f * dp + bob, 3f * dp, fill)
        c.drawCircle(x + 9f * dp, top - 11f * dp + bob, 4.5f * dp, fill)
        c.drawRoundRect(bx - 16f * dp, by - 15f * dp, bx + 16f * dp, by + 15f * dp, 14f * dp, 14f * dp, fill)
        when (s.want) {
            1 -> drawFood(c, s.wantFood, bx, by, 8f * dp)
            2 -> { fill.color = ACCENT; drawHeart(c, bx, by - 3f * dp, 6f * dp) }
            else -> {
                fill.color = Color.rgb(255, 120, 90); c.drawCircle(bx, by, 9f * dp, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp
                c.drawArc(bx - 9f * dp, by - 4f * dp, bx + 9f * dp, by + 12f * dp, 200f, 140f, false, stroke)
            }
        }
    }

    // ---------- Shake the phone ----------

    private var lastShake = 0f
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (abs(m - 9.81f) > 13f && t - lastShake > 2.5f && tab == Tab.GARDEN && popup == null) {
                lastShake = t
                for (s in local()) {
                    express(s, E_DIZZY, 2.5f); s.jiggle = 1f
                    if (s.sleeping) { s.act = 0; s.sleeping = false }
                }
                sound.play(Fx.GIGGLE, 0.6f, 0.8f)
                toast("Whoa! Everyone's dizzy! 😵")
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun startShakeSensor() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(shakeListener, acc, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopShakeSensor() {
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.unregisterListener(shakeListener)
    }

    /** Everyone waves hello when you come back to the garden. */
    private fun waveHello() {
        for (s in local()) if (!s.sleeping) s.waveT = 1.5f + Random.nextFloat() * 0.8f
    }

    // ---------- Little idle animations ----------

    private fun updateIdleAnim(s: Shroom, dt: Float) {
        if (s.idleAnim > 0) {
            val before = s.idleAnimT
            s.idleAnimT -= dt
            if (s.idleAnim == 3 && before > 0.35f && s.idleAnimT <= 0.35f) {   // achoo!
                val (x, y) = shroomScreen(s)
                repeat(14) {
                    val a = Random.nextFloat() * 6.28f
                    particles.add(Particle(x + s.facing * shroomSize(s) * 0.2f, y - shroomSize(s) * 0.3f,
                        cos(a) * 70f * dp + s.facing * 60f * dp, sin(a) * 60f * dp, 0.8f, 0.8f,
                        Color.HSVToColor(floatArrayOf(Random.nextFloat() * 360f, 0.4f, 1f)), P_SPARK))
                }
                sound.play(Fx.SNEEZE, 0.6f, voicePitch(s))
                s.jiggle = 1f
            }
            if (s.idleAnimT <= 0f) s.idleAnim = 0
            return
        }
        if (s.idle > 0f && Random.nextFloat() < dt * 0.07f) {
            s.idleAnim = 1 + Random.nextInt(4)
            s.idleAnimT = when (s.idleAnim) { 1 -> 1.4f; 2 -> 1.2f; 3 -> 1.0f; else -> 1.6f }
            if (s.idleAnim == 1 && Random.nextFloat() < 0.5f) sound.play(Fx.YAWN, 0.35f, voicePitch(s))
        }
    }

    // ---------- Save backup ----------

    private fun backupSave() {
        val code = lab.exportCode()
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Shroomology backup", code))
        } catch (_: Exception) {}
        toast("Backup code copied! Send it to yourself to keep it safe 💾", MINT)
        try {
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "My Shroomology backup")
                putExtra(android.content.Intent.EXTRA_TEXT, code)
            }
            context.startActivity(android.content.Intent.createChooser(share, "Save your backup code"))
        } catch (_: Exception) {}
    }

    private fun restoreSave() {
        val act = context as? android.app.Activity ?: return
        val clip = try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) { null }
        if (clip == null || !clip.trim().startsWith("SHROOM1:")) {
            toast("Copy your backup code first, then tap RESTORE", ACCENT)
            return
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("Restore backup?")
            .setMessage("This replaces your current lab with the one from your backup code.")
            .setPositiveButton("Restore") { _, _ ->
                if (lab.importCode(clip)) act.recreate() else toast("That backup code didn't work", ACCENT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Redeem codes & feedback ----------

    private fun redeemDialog() {
        val act = context as? android.app.Activity ?: return
        val input = android.widget.EditText(act).apply {
            hint = "Enter a code"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(24))
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("Redeem a code 🎁")
            .setView(input)
            .setPositiveButton("Redeem") { _, _ ->
                val (ok, msg) = lab.redeem(input.text.toString())
                if (ok) { sound.play(Fx.DISCOVER); burst(width / 2f, height * 0.4f, DUST, 40, P_SPARK); markDirty() } else sound.play(Fx.DENIED)
                toast(msg, if (ok) DUST else ACCENT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun feedbackDialog() {
        val act = context as? android.app.Activity ?: return
        val box = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val group = android.widget.RadioGroup(act).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val idea = android.widget.RadioButton(act).apply { text = "💡 Suggestion"; id = 1; isChecked = true }
        val bug = android.widget.RadioButton(act).apply { text = "🐞 Bug"; id = 2 }
        group.addView(idea); group.addView(bug)
        val input = android.widget.EditText(act).apply {
            hint = "Tell us your idea, or what went wrong"
            minLines = 4; gravity = android.view.Gravity.TOP
            filters = arrayOf(android.text.InputFilter.LengthFilter(1500))
        }
        box.addView(group); box.addView(input)
        android.app.AlertDialog.Builder(act)
            .setTitle("Suggestions & bugs")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isEmpty()) { toast("Write a little something first"); return@setPositiveButton }
                Feedback.submit(context, if (bug.isChecked) "bug" else "suggestion", msg, versionName())
                sound.play(Fx.PET)
                toast("Thanks! Your message is on its way 💌", MINT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Sticker book ----------

    private var stickerView = false
    private var stickerPage = 0
    private var stickerDrag = -1
    private val STICKERS_PER_PAGE = 10

    private fun stickerPages() = (Data.STICKERS.size + STICKERS_PER_PAGE - 1) / STICKERS_PER_PAGE
    private fun stickerBoard() = RectF(16f * dp, topBar + 60f * dp + 26f * dp, width - 16f * dp, navTop - 64f * dp)

    /** Where a sticker sits on its page, in screen pixels. */
    private fun stickerXY(i: Int): Pair<Float, Float> {
        val b = stickerBoard()
        var nx = lab.stickerPos[i * 2]; var ny = lab.stickerPos[i * 2 + 1]
        if (nx.isNaN()) {
            val k = i % STICKERS_PER_PAGE
            nx = (if (k % 2 == 0) 0.27f else 0.73f) + ((i * 37) % 9 - 4) * 0.012f
            ny = 0.11f + (k / 2) * 0.195f + ((i * 53) % 7 - 3) * 0.008f
        }
        return Pair(b.left + nx * b.width(), b.top + ny * b.height())
    }

    private fun handleStickerTouch(e: MotionEvent, x: Float, y: Float): Boolean {
        if (!(tab == Tab.JOURNAL && journalTab == 2 && stickerView) || settingsOpen) return false
        val b = stickerBoard()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val first = stickerPage * STICKERS_PER_PAGE
                val hit = (first until min(Data.STICKERS.size, first + STICKERS_PER_PAGE)).reversed()
                    .firstOrNull { lab.stickers[it] && stickerXY(it).let { (sx, sy) -> hypot(x - sx, y - sy) < 34f * dp } }
                if (hit != null) { stickerDrag = hit; sound.play(Fx.POP, 0.5f, 1.3f); return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> if (stickerDrag >= 0) {
                lab.stickerPos[stickerDrag * 2] = ((x - b.left) / b.width()).coerceIn(0.08f, 0.92f)
                lab.stickerPos[stickerDrag * 2 + 1] = ((y - b.top) / b.height()).coerceIn(0.07f, 0.93f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stickerDrag >= 0) {
                stickerDrag = -1; sound.play(Fx.PET, 0.5f); markDirty(); return true
            }
        }
        return false
    }

    private fun drawStickers(c: Canvas, w: Float, top: Float) {
        text.textSize = 13f * dp; text.color = TEXT_DIM
        c.drawText("${lab.stickers.count { it }} of ${Data.STICKERS.size} stickers · drag them around! · page ${stickerPage + 1}/${stickerPages()}", w / 2, top + 6f * dp, text)
        text.color = Color.WHITE
        val b = stickerBoard()
        // a cozy cork board page
        fill.shader = LinearGradient(0f, b.top, 0f, b.bottom, Color.rgb(205, 160, 110), Color.rgb(180, 135, 90), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, 18f * dp, 18f * dp, fill); fill.shader = null
        val r = Random(stickerPage + 3)
        fill.color = Color.argb(40, 90, 55, 25)
        for (k in 0 until 90) c.drawCircle(b.left + r.nextFloat() * b.width(), b.top + r.nextFloat() * b.height(), (0.8f + r.nextFloat() * 1.5f) * dp, fill)
        stroke.color = Color.rgb(140, 95, 55); stroke.strokeWidth = 4f * dp; c.drawRoundRect(b, 18f * dp, 18f * dp, stroke)
        val first = stickerPage * STICKERS_PER_PAGE
        val order = (first until min(Data.STICKERS.size, first + STICKERS_PER_PAGE)).sortedBy { if (it == stickerDrag) 1 else 0 }
        for (i in order) {
            val st = Data.STICKERS[i]
            val (sx, sy) = stickerXY(i)
            val got = lab.stickers[i]
            val rad = 30f * dp * (if (i == stickerDrag) 1.15f else 1f)
            c.save(); c.rotate(((i * 47) % 17 - 8).toFloat() + (if (i == stickerDrag) sin(t * 10f) * 3f else 0f), sx, sy)
            if (got) {
                fill.color = Color.argb(70, 40, 20, 10); c.drawCircle(sx + 2f * dp, sy + 3f * dp, rad, fill)
                fill.color = Color.WHITE; c.drawCircle(sx, sy, rad, fill)
                fill.shader = RadialGradient(sx - rad * 0.3f, sy - rad * 0.3f, rad, shade(st.color, 0.35f), st.color, Shader.TileMode.CLAMP)
                c.drawCircle(sx, sy, rad - 4f * dp, fill); fill.shader = null
                fill.color = Color.argb(110, 255, 255, 255); c.drawOval(sx - rad * 0.55f, sy - rad * 0.7f, sx, sy - rad * 0.35f, fill)
                text.textSize = rad * 0.85f; c.drawText(st.icon, sx, sy + rad * 0.3f, text)
            } else {
                stroke.color = Color.argb(120, 255, 245, 230); stroke.strokeWidth = 2f * dp
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * dp, 5f * dp), 0f)
                c.drawCircle(sx, sy, rad - 2f * dp, stroke); stroke.pathEffect = null
                text.textSize = rad * 0.7f; text.color = Color.argb(140, 255, 245, 230); c.drawText("?", sx, sy + rad * 0.25f, text); text.color = Color.WHITE
            }
            c.restore()
            text.textSize = 10.5f * dp; text.color = if (got) Color.rgb(60, 35, 20) else Color.argb(170, 80, 50, 30)
            c.drawText(if (got) st.name else st.desc, sx, sy + rad + 13f * dp, text)
            text.color = Color.WHITE
        }
    }

    // ---------- Home-screen widget ----------

    /** The shroom shown on the widget: the one you picked, or your closest friend. */
    private fun widgetShroom(): Shroom? {
        val id = prefs.getLong("widgetShroom", -1L)
        return lab.garden.firstOrNull { it.id == id } ?: lab.garden.maxByOrNull { it.bond + it.xp * 0.01f }
    }

    /** Draws the widget's two animation frames and tells the widget to refresh. */
    fun updateWidget() {
        try {
            val s = widgetShroom()
            val dir = context.filesDir
            if (s == null) {
                java.io.File(dir, "widget0.png").delete(); java.io.File(dir, "widget1.png").delete()
            } else {
                for (k in 0..1) {
                    val bmp = renderWidgetFrame(s, if (k == 0) -1f else 1f)
                    java.io.FileOutputStream(java.io.File(dir, "widget$k.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                val moodText = when {
                    s.away() -> "is out exploring 🧭"
                    s.hunger < 25f -> "is hungry! 🍂"
                    s.happy < 30f -> "misses you 💭"
                    s.mood() == 0 -> "is happy 😊"
                    else -> "says hi! 👋"
                }
                context.getSharedPreferences("tinylab_widget", Context.MODE_PRIVATE).edit()
                    .putString("name", s.name).putString("mood", moodText).putLong("id", s.id).apply()
            }
            ShroomWidget.refresh(context)
        } catch (_: Exception) {}
    }

    internal fun renderWidgetFrame(s: Shroom, bob: Float): android.graphics.Bitmap {
        val px = 360
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val th = Data.THEMES[lab.theme]
        val r = RectF(0f, 0f, px.toFloat(), px.toFloat())
        fill.shader = LinearGradient(0f, 0f, 0f, px.toFloat(), th.skyTop, th.groundNear, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, px * 0.12f, px * 0.12f, fill); fill.shader = null
        fill.color = Color.argb(70, 255, 255, 255)
        for (k in 0 until 8) drawSparkle(c, px * ((k * 0.37f + 0.1f) % 1f), px * ((k * 0.23f + 0.08f) % 0.45f), px * 0.012f)
        fill.color = th.groundFar
        c.drawOval(px * 0.12f, px * 0.72f, px * 0.88f, px * 0.9f, fill)
        val size = px * 0.46f * s.size
        drawShroom(c, s.species, px / 2f, px * 0.81f, size, s.mood(), bob, false, false, shiny = s.shiny, who = s)
        if (s.pet >= 0) drawPet(c, s.pet, px * 0.8f, px * 0.84f, size * 0.38f, if (bob > 0) 1f else 0f, -1f, false)
        return bmp
    }

    // ---------- Settings ----------

    private fun settingsButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val card = settingsCard(w, h)
        val pad = 18f * dp
        val rowH = 52f * dp
        val toggles = listOf(
            Triple("music", "Music", sound.musicOn),
            Triple("sfx", "Sound effects", sound.sfxOn),
            Triple("vibrate", "Vibration", vibrateOn),
            Triple("notify", "Reminders", prefs.getBoolean("notify", true)),
        )
        for ((i, tg) in toggles.withIndex()) {
            val (key, label, on) = tg
            val y = card.top + 64f * dp + i * rowH
            list.add(Button("set_$key", RectF(card.left + pad, y, card.right - pad, y + rowH - 8f * dp), "", STYLE_CARD,
                custom = { c, rr -> drawToggleRow(c, rr, label, on) }) {
                when (key) {
                    "music" -> { sound.musicOn = !on; prefs.edit().putBoolean("music", !on).apply() }
                    "sfx" -> { sound.sfxOn = !on; prefs.edit().putBoolean("sfx", !on).apply() }
                    "vibrate" -> { vibrateOn = !on; prefs.edit().putBoolean("vibrate", !on).apply(); if (!on) buzz(40) }
                    else -> prefs.edit().putBoolean("notify", !on).apply()
                }
            })
        }
        val by = card.top + 64f * dp + toggles.size * rowH + 6f * dp
        list.add(Button("set_tut", RectF(card.left + pad, by, card.right - pad, by + 46f * dp), "▶  Replay tutorial") {
            settingsOpen = false
            tab = Tab.GARDEN; selected = -1; feeding = false; styling = false; moreOpen = false; jobbing = false; exploring = false; decorating = false
            setTut(T_WELCOME)
        })
        val half = (card.width() - pad * 2 - 8f * dp) / 2
        val ky = by + 52f * dp
        list.add(Button("set_backup", RectF(card.left + pad, ky, card.left + pad + half, ky + 44f * dp), "💾 BACK UP") { backupSave() })
        list.add(Button("set_restore", RectF(card.right - pad - half, ky, card.right - pad, ky + 44f * dp), "📥 RESTORE") { restoreSave() })
        val ky2 = ky + 52f * dp
        list.add(Button("set_redeem", RectF(card.left + pad, ky2, card.left + pad + half, ky2 + 44f * dp), "🎁 REDEEM CODE") { redeemDialog() })
        list.add(Button("set_feedback", RectF(card.right - pad - half, ky2, card.right - pad, ky2 + 44f * dp), "💬 FEEDBACK") { feedbackDialog() })
        list.add(Button("set_close", RectF(card.left + pad, card.bottom - 62f * dp, card.right - pad, card.bottom - 16f * dp), "DONE", STYLE_PRIMARY) {
            settingsOpen = false
        })
    }

    private fun settingsCard(w: Float, h: Float) = RectF(20f * dp, h * 0.11f, w - 20f * dp, h * 0.11f + 574f * dp)

    private fun drawSettings(c: Canvas, w: Float, h: Float) {
        fill.color = Color.argb(200, 10, 6, 24)
        c.drawRect(0f, 0f, w, h, fill)
        val card = settingsCard(w, h)
        panel(c, card, 26f * dp)
        text.textSize = 24f * dp
        c.drawText("Settings", w / 2, card.top + 42f * dp, text)
        // credits
        val by = card.top + 64f * dp + 6 * 52f * dp + 62f * dp
        text.textSize = 13f * dp; text.color = TEXT_DIM
        c.drawText("Shroomology ${versionName()}", w / 2, by + 26f * dp, text)
        text.textSize = 15f * dp; text.color = ACCENT
        c.drawText("Made by Omnymara", w / 2, by + 50f * dp, text)
        // a tiny mushroom next to the credit
        drawShroom(c, 1, w / 2 - text.measureText("Made by Omnymara") / 2 - 18f * dp, by + 54f * dp, 22f * dp, 0, sin(t * 2f), (t % 4f) < 0.12f, false)
        text.color = Color.WHITE
        for (b in buttons) drawButton(c, b)
    }

    private fun versionName(): String = try {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) { "" }

    private fun drawToggleRow(c: Canvas, r: RectF, label: String, on: Boolean) {
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawRoundRect(r, 14f * dp, 14f * dp, fill)
        text.textAlign = Paint.Align.LEFT; text.textSize = 16f * dp
        c.drawText(label, r.left + 16f * dp, r.centerY() + 6f * dp, text)
        text.textAlign = Paint.Align.CENTER
        // pill switch
        val sw = 52f * dp; val sh = 28f * dp
        val sx = r.right - sw - 12f * dp; val sy = r.centerY() - sh / 2
        fill.color = if (on) MINT else Color.rgb(80, 66, 120)
        c.drawRoundRect(sx, sy, sx + sw, sy + sh, sh / 2, sh / 2, fill)
        val kx = if (on) sx + sw - sh / 2 else sx + sh / 2
        fill.color = Color.argb(60, 0, 0, 0); c.drawCircle(kx, r.centerY() + 2f * dp, sh / 2 - 3f * dp, fill)
        fill.color = Color.WHITE; c.drawCircle(kx, r.centerY(), sh / 2 - 3f * dp, fill)
    }

    private fun drawGear(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY()
        fill.color = Color.argb(220, 255, 255, 255)
        for (k in 0 until 8) {
            c.save(); c.rotate(k * 45f, cx, cy)
            c.drawRoundRect(cx - 3f * dp, cy - 12f * dp, cx + 3f * dp, cy - 7f * dp, 1.5f * dp, 1.5f * dp, fill)
            c.restore()
        }
        c.drawCircle(cx, cy, 8.5f * dp, fill)
        fill.color = Color.rgb(40, 28, 72); c.drawCircle(cx, cy, 3.5f * dp, fill)
    }

    // ---------- First-play tutorial ----------

    private fun setTut(step: Int) {
        tutStep = step
        prefs.edit().putInt("tutStep", step).apply()
        tutFeedBase = lab.feeds
        if (step == T_DRAG) {
            // make sure there's something to brew with
            if (lab.ingredients[Data.WATER] < 1) lab.ingredients[Data.WATER] = 1
            if (lab.ingredients[Data.SOIL] < 1) lab.ingredients[Data.SOIL] = 1
            labMode = 0
        }
    }

    /** Moves the tutorial forward when the player has done what the current step asks. */
    private fun updateTutorial() {
        if (tutStep < 0) return
        when (tutStep) {
            T_GO_LAB -> if (tab == Tab.LAB) setTut(T_DRAG)
            T_DRAG -> if (slotsFull()) setTut(T_BREW)
            T_BREW -> if (brewing > 0f) setTut(T_STIR)
            T_STIR -> if (popup != null) setTut(T_REVEAL)
            T_REVEAL -> if (popup == null && tab == Tab.GARDEN && selected >= 0) setTut(T_FEED)
            T_FEED -> if (lab.feeds > tutFeedBase) setTut(T_DONE)
        }
    }

    private fun tutText(): String = when (tutStep) {
        T_WELCOME -> "Welcome to Shroomology! 🍄 You're the new mushroom scientist. Let's grow your very first mushroom together."
        T_GO_LAB -> "Tap the LAB down below."
        T_DRAG -> "Drag Water into the flask, then drag Soil in too."
        T_BREW -> "Now tap BREW!"
        T_STIR -> "STIR! Trace circles around the flask, following the arrow. A better stir gives a stronger mushroom and a chance at a rare shiny!"
        T_REVEAL -> "Your first mushroom! Tap SEE IT to visit it in the garden."
        T_FEED -> if (selected >= 0) "Tap FEED, then pick a food. Mushrooms grow when they're fed and happy." else "Tap one of your mushrooms."
        else -> "You're a real Shroomologist now! Grown mushrooms make spore dust ✦, can crossbreed in the Lab and go exploring. Check the Journal ⭐ for daily gifts and requests."
    }

    /** Where the spotlight goes for the current step (null = no spotlight). */
    private fun tutSpot(w: Float, h: Float): RectF? = when (tutStep) {
        T_GO_LAB -> { val tw = w / Tab.values().size; RectF(tw, navTop, tw * 2, h) }
        T_DRAG -> RectF(8f * dp, h * 0.45f + 66f * dp, w - 8f * dp, navTop - 2f * dp)
        T_BREW -> { val bw = min(w * 0.6f, 240f * dp); RectF(w / 2 - bw / 2 - 6f * dp, h * 0.45f - 6f * dp, w / 2 + bw / 2 + 6f * dp, h * 0.45f + 60f * dp) }
        T_STIR -> RectF(w / 2 - 130f * dp, flaskY() - 130f * dp, w / 2 + 130f * dp, flaskY() + 130f * dp)
        T_REVEAL -> if (popup != null) { val bw = min(w * 0.36f, 160f * dp); RectF(w / 2, h * 0.66f - 6f * dp, w / 2 + bw + 12f * dp, h * 0.66f + 58f * dp) } else null
        T_FEED -> if (selected >= 0) { val pad = 16f * dp; val bw = (w - pad * 2 - 16f * dp) / 3; val by = navTop - 62f * dp
            RectF(pad - 6f * dp, by - 60f * dp, pad + bw + 6f * dp, by - 2f * dp) } else null
        else -> null
    }

    private fun tutCardRect(w: Float, h: Float): RectF {
        text.textSize = 15f * dp
        val lines = wrap(tutText(), w - 120f * dp)
        val hasNext = tutStep == T_WELCOME || tutStep == T_DONE
        val hasSkip = tutStep != T_DONE
        val ch = 44f * dp + lines.size * 21f * dp + (if (hasNext) 52f * dp else 0f) + (if (hasSkip) 30f * dp else 0f)
        val spot = tutSpot(w, h)
        val atTop = spot != null && spot.centerY() > h * 0.45f || tutStep == T_REVEAL
        val y = when {
            tutStep == T_WELCOME || tutStep == T_DONE -> h * 0.3f
            atTop -> topBar + 10f * dp
            else -> navTop - ch - 90f * dp
        }
        return RectF(16f * dp, y, w - 16f * dp, y + ch)
    }

    private fun tutorialButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val r = tutCardRect(w, h)
        if (tutStep == T_WELCOME || tutStep == T_DONE) {
            val lift = if (tutStep != T_DONE) 30f * dp else 0f
            list.add(Button("tut_next", RectF(r.centerX() - 80f * dp, r.bottom - 52f * dp - lift, r.centerX() + 80f * dp, r.bottom - 12f * dp - lift),
                if (tutStep == T_DONE) "LET'S GO!" else "NEXT", STYLE_PRIMARY) {
                if (tutStep == T_DONE) setTut(-1)
                else setTut(if (gardenFull()) T_FEED else T_GO_LAB)
            })
        }
        if (tutStep != T_DONE) {
            list.add(Button("tut_skip", RectF(r.right - 130f * dp, r.bottom - 34f * dp, r.right - 12f * dp, r.bottom - 6f * dp), "Skip tutorial", STYLE_GHOST) {
                setTut(-1)
                toast("Tutorial skipped. You can replay it in Settings ⚙")
            })
        }
    }

    private fun drawTutorial(c: Canvas, w: Float, h: Float) {
        if (tutStep < 0 || settingsOpen || photoMode) return
        val spot = tutSpot(w, h)
        val blocking = tutStep == T_WELCOME || tutStep == T_DONE
        // dim everything except the spotlight
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        if (spot != null) path.addRoundRect(spot, 20f * dp, 20f * dp, Path.Direction.CW)
        fill.color = Color.argb(if (blocking) 170 else if (spot != null) 120 else 0, 8, 4, 20)
        c.drawPath(path, fill)
        path.fillType = Path.FillType.WINDING
        if (spot != null) {
            val pulse = 3f * dp + 3f * dp * (0.5f + 0.5f * sin(t * 5f))
            stroke.color = ACCENT; stroke.strokeWidth = 3f * dp
            c.drawRoundRect(spot.left - pulse, spot.top - pulse, spot.right + pulse, spot.bottom + pulse, 22f * dp, 22f * dp, stroke)
        }
        // the card, with Professor Spore
        val r = tutCardRect(w, h)
        panel(c, r, 20f * dp, PANEL_LIGHT)
        drawShroom(c, professor.species, r.left + 30f * dp, r.top + 64f * dp, 38f * dp, 0, sin(t * 2f), (t % 4f) < 0.12f, false, who = professor)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * dp; text.color = ACCENT
        c.drawText("PROFESSOR SPORE", r.left + 60f * dp, r.top + 26f * dp, text)
        text.textSize = 15f * dp; text.color = Color.WHITE
        val lines = wrap(tutText(), w - 120f * dp)
        for ((k, line) in lines.withIndex()) c.drawText(line, r.left + 60f * dp, r.top + 50f * dp + k * 21f * dp, text)
        text.textAlign = Paint.Align.CENTER
        for (b in buttons) if (b.id.startsWith("tut_")) drawButton(c, b)
    }

    // ---------- Shared styling ----------

    /** A rounded panel with a soft drop shadow and a light top edge. */
    private fun panel(c: Canvas, r: RectF, radius: Float, color: Int = PANEL) {
        fill.color = Color.argb(90, 0, 0, 0)
        c.drawRoundRect(r.left, r.top + 4f * dp, r.right, r.bottom + 6f * dp, radius, radius, fill)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, shade(color, 0.08f), shade(color, -0.08f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        stroke.color = Color.argb(45, 255, 255, 255); stroke.strokeWidth = 1.5f * dp
        c.drawRoundRect(r.left + 1f * dp, r.top + 1f * dp, r.right - 1f * dp, r.bottom - 1f * dp, radius, radius, stroke)
    }

    private fun loadFont(): Typeface = try {
        if (Build.VERSION.SDK_INT >= 26) {
            Typeface.Builder(context.assets, "fonts/fredoka.ttf").setFontVariationSettings("'wght' 600").build()
                ?: Typeface.createFromAsset(context.assets, "fonts/fredoka.ttf")
        } else Typeface.createFromAsset(context.assets, "fonts/fredoka.ttf")
    } catch (_: Exception) {
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ---------- Art: accessories ----------

    /**
     * Draws a worn item. capTop = top of the cap, faceY = eye line, neckY = where cap meets stem,
     * ex = half the distance between the eyes.
     */
    private fun drawAccessory(c: Canvas, a: Int, cx: Float, capTop: Float, faceY: Float, neckY: Float, s: Float, ex: Float) {
        val col = Data.ACCESSORIES[a].color
        fill.color = col
        stroke.color = col
        path.reset()
        when (a) {
            0 -> { // party hat
                path.moveTo(cx - s * 0.17f, capTop + s * 0.05f); path.lineTo(cx + s * 0.04f, capTop - s * 0.4f); path.lineTo(cx + s * 0.2f, capTop + s * 0.04f); path.close()
                c.drawPath(path, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = s * 0.03f
                c.drawLine(cx - s * 0.1f, capTop - s * 0.08f, cx + s * 0.13f, capTop - s * 0.1f, stroke)
                c.drawLine(cx - s * 0.03f, capTop - s * 0.24f, cx + s * 0.09f, capTop - s * 0.25f, stroke)
                fill.color = Color.rgb(255, 230, 120); c.drawCircle(cx + s * 0.04f, capTop - s * 0.42f, s * 0.05f, fill)
            }
            1 -> { // bow on the side of the cap
                val bx = cx + s * 0.25f; val by = capTop + s * 0.12f
                path.moveTo(bx, by); path.lineTo(bx - s * 0.15f, by - s * 0.1f); path.lineTo(bx - s * 0.15f, by + s * 0.1f); path.close()
                path.moveTo(bx, by); path.lineTo(bx + s * 0.15f, by - s * 0.1f); path.lineTo(bx + s * 0.15f, by + s * 0.1f); path.close()
                c.drawPath(path, fill)
                fill.color = shade(col, -0.25f); c.drawCircle(bx, by, s * 0.045f, fill)
            }
            2 -> { // beanie
                c.drawArc(cx - s * 0.32f, capTop - s * 0.14f, cx + s * 0.32f, capTop + s * 0.4f, 180f, 180f, true, fill)
                fill.color = shade(col, -0.25f)
                c.drawRoundRect(cx - s * 0.34f, capTop + s * 0.09f, cx + s * 0.34f, capTop + s * 0.17f, s * 0.04f, s * 0.04f, fill)
                fill.color = Color.WHITE; c.drawCircle(cx, capTop - s * 0.16f, s * 0.07f, fill)
            }
            3 -> { // flower crown
                val cols = intArrayOf(Color.rgb(255, 130, 170), Color.rgb(255, 230, 120), Color.WHITE, Color.rgb(170, 140, 255), Color.rgb(255, 150, 90))
                for (k in 0 until 5) {
                    val fx = cx + (k - 2) * s * 0.11f
                    val fy = capTop + s * 0.06f + abs(k - 2) * s * 0.03f
                    fill.color = Color.rgb(100, 180, 100); c.drawOval(fx - s * 0.06f, fy - s * 0.02f, fx + s * 0.06f, fy + s * 0.03f, fill)
                    fill.color = cols[k]; c.drawCircle(fx, fy - s * 0.02f, s * 0.045f, fill)
                    fill.color = Color.rgb(255, 200, 80); c.drawCircle(fx, fy - s * 0.02f, s * 0.018f, fill)
                }
            }
            4 -> { // top hat
                c.drawRect(cx - s * 0.14f, capTop - s * 0.32f, cx + s * 0.14f, capTop + s * 0.04f, fill)
                c.drawOval(cx - s * 0.24f, capTop - s * 0.01f, cx + s * 0.24f, capTop + s * 0.07f, fill)
                fill.color = Color.rgb(200, 50, 70); c.drawRect(cx - s * 0.14f, capTop - s * 0.06f, cx + s * 0.14f, capTop - s * 0.01f, fill)
            }
            5 -> { // cowboy hat
                c.drawOval(cx - s * 0.38f, capTop - s * 0.02f, cx + s * 0.38f, capTop + s * 0.09f, fill)
                c.drawRoundRect(cx - s * 0.17f, capTop - s * 0.22f, cx + s * 0.17f, capTop + s * 0.04f, s * 0.08f, s * 0.08f, fill)
                fill.color = shade(col, -0.3f); c.drawRect(cx - s * 0.17f, capTop - s * 0.04f, cx + s * 0.17f, capTop + s * 0.01f, fill)
            }
            6 -> { // wizard hat
                path.moveTo(cx - s * 0.2f, capTop + s * 0.04f)
                path.quadTo(cx - s * 0.02f, capTop - s * 0.3f, cx + s * 0.18f, capTop - s * 0.5f)
                path.quadTo(cx + s * 0.06f, capTop - s * 0.2f, cx + s * 0.2f, capTop + s * 0.04f); path.close()
                c.drawPath(path, fill)
                c.drawOval(cx - s * 0.3f, capTop - s * 0.01f, cx + s * 0.3f, capTop + s * 0.07f, fill)
                fill.color = Color.rgb(255, 230, 120)
                drawSparkle(c, cx - s * 0.02f, capTop - s * 0.12f, s * 0.025f)
                c.drawCircle(cx + s * 0.07f, capTop - s * 0.28f, s * 0.02f, fill)
            }
            7 -> { // tiny crown
                val cw = s * 0.3f; val cy = capTop + s * 0.03f
                path.moveTo(cx - cw / 2, cy); path.lineTo(cx - cw / 2, cy - s * 0.15f); path.lineTo(cx - cw / 4, cy - s * 0.07f)
                path.lineTo(cx, cy - s * 0.18f); path.lineTo(cx + cw / 4, cy - s * 0.07f); path.lineTo(cx + cw / 2, cy - s * 0.15f)
                path.lineTo(cx + cw / 2, cy); path.close()
                fill.shader = LinearGradient(cx - cw / 2, 0f, cx + cw / 2, 0f, Color.rgb(255, 245, 160), Color.rgb(220, 160, 30), Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
                fill.color = Color.rgb(90, 200, 255); c.drawCircle(cx, cy - s * 0.06f, s * 0.025f, fill)
            }
            8 -> { // halo
                stroke.strokeWidth = s * 0.04f
                c.drawOval(cx - s * 0.22f, capTop - s * 0.2f + sin(t * 2f) * s * 0.02f, cx + s * 0.22f, capTop - s * 0.1f + sin(t * 2f) * s * 0.02f, stroke)
            }
            9, 10 -> { // glasses / sunglasses
                val r = s * 0.075f
                stroke.strokeWidth = s * 0.022f; stroke.color = col
                if (a == 10) {
                    c.drawRoundRect(cx - ex - r * 1.1f, faceY - r * 0.8f, cx - ex + r * 1.1f, faceY + r * 0.8f, r * 0.4f, r * 0.4f, fill)
                    c.drawRoundRect(cx + ex - r * 1.1f, faceY - r * 0.8f, cx + ex + r * 1.1f, faceY + r * 0.8f, r * 0.4f, r * 0.4f, fill)
                    fill.color = Color.argb(90, 255, 255, 255); c.drawRect(cx - ex - r * 0.8f, faceY - r * 0.6f, cx - ex - r * 0.3f, faceY - r * 0.3f, fill)
                } else {
                    c.drawCircle(cx - ex, faceY, r, stroke); c.drawCircle(cx + ex, faceY, r, stroke)
                }
                c.drawLine(cx - ex + r, faceY, cx + ex - r, faceY, stroke)
            }
            11 -> { // scarf
                c.drawRoundRect(cx - s * 0.24f, neckY - s * 0.02f, cx + s * 0.24f, neckY + s * 0.09f, s * 0.05f, s * 0.05f, fill)
                c.drawRoundRect(cx + s * 0.08f, neckY + s * 0.04f, cx + s * 0.17f, neckY + s * 0.26f, s * 0.04f, s * 0.04f, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = s * 0.015f
                c.drawLine(cx + s * 0.09f, neckY + s * 0.2f, cx + s * 0.16f, neckY + s * 0.2f, stroke)
            }
            12 -> { // bow tie
                val by = neckY + s * 0.06f
                path.moveTo(cx, by); path.lineTo(cx - s * 0.12f, by - s * 0.06f); path.lineTo(cx - s * 0.12f, by + s * 0.06f); path.close()
                path.moveTo(cx, by); path.lineTo(cx + s * 0.12f, by - s * 0.06f); path.lineTo(cx + s * 0.12f, by + s * 0.06f); path.close()
                c.drawPath(path, fill)
                fill.color = shade(col, -0.3f); c.drawCircle(cx, by, s * 0.03f, fill)
            }
            in 14..21 -> drawEventAccessory(c, a, cx, capTop, faceY, neckY, s, ex)
            Data.ACORN_CAP -> {
                fill.color = Color.rgb(130, 85, 45); c.drawArc(cx - s * 0.22f, capTop - s * 0.14f, cx + s * 0.22f, capTop + s * 0.12f, 180f, 180f, true, fill)
                fill.color = Color.rgb(165, 115, 65)
                for (k in -2..2) c.drawCircle(cx + k * s * 0.08f, capTop - s * 0.02f, s * 0.04f, fill)
                stroke.color = Color.rgb(100, 65, 35); stroke.strokeWidth = s * 0.035f; c.drawLine(cx, capTop - s * 0.14f, cx + s * 0.05f, capTop - s * 0.23f, stroke)
            }
            Data.MONOCLE -> {
                stroke.color = Color.rgb(230, 200, 90); stroke.strokeWidth = s * 0.022f
                c.drawCircle(cx + ex, faceY, s * 0.075f, stroke)
                c.drawLine(cx + ex + s * 0.05f, faceY + s * 0.06f, cx + ex + s * 0.1f, faceY + s * 0.2f, stroke)
                fill.color = Color.argb(60, 200, 230, 255); c.drawCircle(cx + ex, faceY, s * 0.07f, fill)
            }
            Data.SPROUT_CROWN -> {
                fill.color = Color.rgb(120, 220, 120)
                c.drawRoundRect(cx - s * 0.2f, capTop - s * 0.03f, cx + s * 0.2f, capTop + s * 0.04f, s * 0.03f, s * 0.03f, fill)
                for (k in -1..1) {
                    val lx = cx + k * s * 0.13f
                    stroke.color = Color.rgb(80, 170, 80); stroke.strokeWidth = s * 0.025f; c.drawLine(lx, capTop, lx, capTop - s * 0.1f, stroke)
                    fill.color = Color.rgb(140, 230, 120)
                    c.drawOval(lx - s * 0.08f, capTop - s * 0.16f, lx, capTop - s * 0.09f, fill); c.drawOval(lx, capTop - s * 0.18f, lx + s * 0.08f, capTop - s * 0.11f, fill)
                }
                fill.color = Color.rgb(255, 200, 220); c.drawCircle(cx, capTop - s * 0.02f, s * 0.035f, fill)
            }
            else -> { // headphones
                stroke.strokeWidth = s * 0.045f; stroke.color = Color.rgb(60, 60, 70)
                c.drawArc(cx - s * 0.42f, capTop - s * 0.06f, cx + s * 0.42f, capTop + s * 0.6f, 200f, 140f, false, stroke)
                c.drawRoundRect(cx - s * 0.48f, capTop + s * 0.2f, cx - s * 0.34f, capTop + s * 0.42f, s * 0.05f, s * 0.05f, fill)
                c.drawRoundRect(cx + s * 0.34f, capTop + s * 0.2f, cx + s * 0.48f, capTop + s * 0.42f, s * 0.05f, s * 0.05f, fill)
            }
        }
    }

    /** A worn item shown on its own (shop rows, style cards). */
    private fun drawAccessoryIcon(c: Canvas, a: Int, x: Float, y: Float, s: Float) {
        when (Data.ACCESSORIES[a].slot) {
            0 -> drawAccessory(c, a, x, y + s * 0.15f, y + s * 0.4f, y + s * 0.6f, s * 1.3f, s * 0.15f)
            1 -> drawAccessory(c, a, x, y - s * 0.6f, y, y + s * 0.3f, s * 1.6f, s * 0.17f)
            else -> drawAccessory(c, a, x, y - s * 0.6f, y - s * 0.3f, y - s * 0.05f, s * 1.6f, s * 0.15f)
        }
    }

    // ---------- Expeditions ----------

    private fun timeLeft(ms: Long): String {
        val m = (ms / 60000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m + 1}m"
    }

    // ---------- Art: icons ----------

    /** Ingredients are glossy orbs with a white symbol on them. */
    private val ingPath = Path()

    /** Ingredients are little illustrated objects: a droplet, a flame, a honey jar... (about r*1.3 in radius). */
    private fun drawIngredient(c: Canvas, i: Int, x: Float, y: Float, r: Float) {
        val u = r * 1.25f   // the size everything below is measured in
        val p = ingPath
        fun grad(x0: Float, y0: Float, rad: Float, light: Int, mid: Int, dark: Int) {
            fill.shader = RadialGradient(x0, y0, max(1f, rad), intArrayOf(light, mid, dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        fun lin(y0: Float, y1: Float, top: Int, bottom: Int) { fill.shader = LinearGradient(0f, y0, 0f, y1, top, bottom, Shader.TileMode.CLAMP) }
        fun done() { fill.shader = null }
        fun outline(col: Int, wdt: Float = u * 0.07f) { stroke.color = col; stroke.strokeWidth = wdt; c.drawPath(p, stroke) }
        fun shine(cx: Float, cy: Float, rw: Float, rh: Float, a: Int = 170) { fill.color = Color.argb(a, 255, 255, 255); c.drawOval(cx - rw, cy - rh, cx + rw, cy + rh, fill) }
        // soft shadow
        fill.color = Color.argb(45, 0, 0, 0)
        c.drawOval(x - u * 0.62f, y + u * 0.78f, x + u * 0.62f, y + u * 0.98f, fill)
        p.reset()
        when (i) {
            Data.WATER -> {
                p.moveTo(x, y - u * 0.95f)
                p.cubicTo(x + u * 0.25f, y - u * 0.5f, x + u * 0.72f, y - u * 0.05f, x + u * 0.68f, y + u * 0.32f)
                p.cubicTo(x + u * 0.62f, y + u * 0.78f, x - u * 0.62f, y + u * 0.78f, x - u * 0.68f, y + u * 0.32f)
                p.cubicTo(x - u * 0.72f, y - u * 0.05f, x - u * 0.25f, y - u * 0.5f, x, y - u * 0.95f); p.close()
                grad(x - u * 0.2f, y, u, Color.rgb(190, 235, 255), Color.rgb(90, 175, 255), Color.rgb(40, 110, 220)); c.drawPath(p, fill); done()
                outline(Color.rgb(35, 95, 190))
                shine(x - u * 0.28f, y + u * 0.05f, u * 0.1f, u * 0.22f)
                fill.color = Color.argb(200, 255, 255, 255); c.drawCircle(x - u * 0.2f, y - u * 0.3f, u * 0.06f, fill)
            }
            Data.SOIL -> {
                p.moveTo(x - u * 0.8f, y + u * 0.62f)
                p.cubicTo(x - u * 0.75f, y - u * 0.1f, x - u * 0.3f, y - u * 0.25f, x, y - u * 0.22f)
                p.cubicTo(x + u * 0.35f, y - u * 0.25f, x + u * 0.78f, y - u * 0.05f, x + u * 0.8f, y + u * 0.62f); p.close()
                lin(y - u * 0.25f, y + u * 0.62f, Color.rgb(160, 110, 70), Color.rgb(100, 65, 40)); c.drawPath(p, fill); done()
                outline(Color.rgb(80, 50, 30))
                fill.color = Color.rgb(85, 55, 35)
                for ((dx, dy) in arrayOf(-0.4f to 0.25f, 0.1f to 0.4f, 0.45f to 0.2f, -0.1f to 0.05f)) c.drawCircle(x + dx * u, y + dy * u, u * 0.06f, fill)
                fill.color = Color.rgb(190, 150, 110); c.drawCircle(x + u * 0.3f, y + u * 0.42f, u * 0.05f, fill)
                // a little sprout
                stroke.color = Color.rgb(70, 150, 70); stroke.strokeWidth = u * 0.08f
                c.drawLine(x, y - u * 0.2f, x, y - u * 0.62f, stroke)
                fill.color = Color.rgb(120, 205, 100)
                c.save(); c.rotate(-30f, x, y - u * 0.6f); c.drawOval(x - u * 0.36f, y - u * 0.72f, x, y - u * 0.52f, fill); c.restore()
                c.save(); c.rotate(30f, x, y - u * 0.6f); c.drawOval(x, y - u * 0.72f, x + u * 0.36f, y - u * 0.52f, fill); c.restore()
            }
            Data.EMBER -> {
                val f = sin(t * 9f + x) * u * 0.04f
                p.moveTo(x, y + u * 0.78f)
                p.cubicTo(x - u * 0.62f, y + u * 0.78f, x - u * 0.78f, y + u * 0.1f, x - u * 0.45f, y - u * 0.3f)
                p.quadTo(x - u * 0.38f, y + u * 0.02f, x - u * 0.2f, y + u * 0.05f)
                p.quadTo(x - u * 0.35f, y - u * 0.55f, x + f, y - u * 0.98f)
                p.quadTo(x + u * 0.1f, y - u * 0.55f, x + u * 0.35f, y - u * 0.42f)
                p.quadTo(x + u * 0.32f, y - u * 0.2f, x + u * 0.42f, y - u * 0.05f)
                p.cubicTo(x + u * 0.85f, y + u * 0.3f, x + u * 0.6f, y + u * 0.78f, x, y + u * 0.78f); p.close()
                lin(y - u, y + u * 0.8f, Color.rgb(255, 120, 60), Color.rgb(220, 50, 40)); c.drawPath(p, fill); done()
                outline(Color.rgb(170, 35, 30))
                p.reset()
                p.moveTo(x, y + u * 0.68f)
                p.cubicTo(x - u * 0.36f, y + u * 0.68f, x - u * 0.38f, y + u * 0.2f, x - u * 0.1f, y - u * 0.15f - f)
                p.quadTo(x + u * 0.05f, y + u * 0.1f, x + u * 0.18f, y)
                p.cubicTo(x + u * 0.42f, y + u * 0.3f, x + u * 0.32f, y + u * 0.68f, x, y + u * 0.68f); p.close()
                lin(y - u * 0.15f, y + u * 0.7f, Color.rgb(255, 245, 160), Color.rgb(255, 200, 70)); c.drawPath(p, fill); done()
            }
            Data.FROST -> {
                stroke.strokeCap = Paint.Cap.ROUND
                for (pass in 0..1) {
                    stroke.color = if (pass == 0) Color.rgb(70, 140, 210) else Color.rgb(205, 240, 255)
                    stroke.strokeWidth = if (pass == 0) u * 0.24f else u * 0.13f
                    for (k in 0 until 6) {
                        val a = k * Math.PI / 3 - Math.PI / 2
                        val ex = x + (cos(a) * u * 0.82f).toFloat(); val ey = y + (sin(a) * u * 0.82f).toFloat()
                        c.drawLine(x, y, ex, ey, stroke)
                        val mx = x + (cos(a) * u * 0.52f).toFloat(); val my = y + (sin(a) * u * 0.52f).toFloat()
                        for (side in intArrayOf(-1, 1)) {
                            val b2 = a + side * 0.75
                            c.drawLine(mx, my, mx + (cos(b2) * u * 0.25f).toFloat(), my + (sin(b2) * u * 0.25f).toFloat(), stroke)
                        }
                    }
                }
                fill.color = Color.WHITE; c.drawCircle(x, y, u * 0.16f, fill)
            }
            Data.IRON -> {
                for (k in 0 until 6) {
                    val a = k * Math.PI / 3 + Math.PI / 6
                    val px = x + (cos(a) * u * 0.82f).toFloat(); val py = y + (sin(a) * u * 0.82f).toFloat()
                    if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
                grad(x - u * 0.3f, y - u * 0.35f, u * 1.1f, Color.rgb(235, 238, 245), Color.rgb(160, 165, 180), Color.rgb(95, 100, 115)); c.drawPath(p, fill); done()
                outline(Color.rgb(70, 75, 90))
                fill.color = Color.rgb(60, 62, 75); c.drawCircle(x, y, u * 0.34f, fill)
                fill.color = Color.rgb(40, 30, 60); c.drawCircle(x, y, u * 0.26f, fill)
                stroke.color = Color.argb(150, 255, 255, 255); stroke.strokeWidth = u * 0.06f
                c.drawArc(x - u * 0.34f, y - u * 0.34f, x + u * 0.34f, y + u * 0.34f, 200f, 90f, false, stroke)
                shine(x - u * 0.38f, y - u * 0.42f, u * 0.14f, u * 0.07f, 150)
            }
            Data.SULFUR -> {
                fun chunk(cx: Float, cy: Float, s2: Float, tilt: Float) {
                    c.save(); c.rotate(tilt, cx, cy)
                    p.reset(); p.moveTo(cx - s2 * 0.5f, cy + s2 * 0.5f); p.lineTo(cx - s2 * 0.55f, cy - s2 * 0.1f); p.lineTo(cx - s2 * 0.1f, cy - s2 * 0.6f)
                    p.lineTo(cx + s2 * 0.5f, cy - s2 * 0.3f); p.lineTo(cx + s2 * 0.55f, cy + s2 * 0.45f); p.close()
                    lin(cy - s2 * 0.6f, cy + s2 * 0.5f, Color.rgb(255, 245, 140), Color.rgb(215, 190, 40)); c.drawPath(p, fill); done()
                    outline(Color.rgb(160, 135, 20), u * 0.06f)
                    fill.color = Color.argb(130, 255, 255, 220)
                    p.reset(); p.moveTo(cx - s2 * 0.55f, cy - s2 * 0.1f); p.lineTo(cx - s2 * 0.1f, cy - s2 * 0.6f); p.lineTo(cx, cy - s2 * 0.05f); p.close(); c.drawPath(p, fill)
                    c.restore()
                }
                chunk(x - u * 0.35f, y + u * 0.3f, u * 0.75f, -10f)
                chunk(x + u * 0.38f, y + u * 0.32f, u * 0.65f, 12f)
                chunk(x, y - u * 0.25f, u * 0.8f, 4f)
            }
            Data.CRYSTAL -> {
                p.moveTo(x - u * 0.8f, y - u * 0.25f); p.lineTo(x - u * 0.45f, y - u * 0.72f); p.lineTo(x + u * 0.45f, y - u * 0.72f)
                p.lineTo(x + u * 0.8f, y - u * 0.25f); p.lineTo(x, y + u * 0.88f); p.close()
                lin(y - u * 0.72f, y + u * 0.88f, Color.rgb(225, 180, 255), Color.rgb(130, 60, 210)); c.drawPath(p, fill); done()
                outline(Color.rgb(95, 40, 160))
                stroke.color = Color.argb(150, 255, 255, 255); stroke.strokeWidth = u * 0.045f
                c.drawLine(x - u * 0.8f, y - u * 0.25f, x + u * 0.8f, y - u * 0.25f, stroke)
                c.drawLine(x - u * 0.2f, y - u * 0.72f, x - u * 0.35f, y - u * 0.25f, stroke); c.drawLine(x + u * 0.2f, y - u * 0.72f, x + u * 0.35f, y - u * 0.25f, stroke)
                c.drawLine(x - u * 0.35f, y - u * 0.25f, x, y + u * 0.88f, stroke); c.drawLine(x + u * 0.35f, y - u * 0.25f, x, y + u * 0.88f, stroke)
                fill.color = Color.argb(170, 255, 255, 255)
                p.reset(); p.moveTo(x - u * 0.45f, y - u * 0.72f); p.lineTo(x - u * 0.2f, y - u * 0.72f); p.lineTo(x - u * 0.35f, y - u * 0.25f); p.lineTo(x - u * 0.8f, y - u * 0.25f); p.close(); c.drawPath(p, fill)
                fill.color = Color.WHITE; drawSparkle(c, x + u * 0.55f, y - u * 0.75f, u * 0.13f)
            }
            Data.MOONDEW -> {
                p.addCircle(x - u * 0.12f, y - u * 0.05f, u * 0.72f, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + u * 0.22f, y - u * 0.25f, u * 0.62f, Path.Direction.CW) }
                p.op(cut, Path.Op.DIFFERENCE)
                grad(x - u * 0.5f, y - u * 0.2f, u, Color.rgb(255, 255, 220), Color.rgb(250, 230, 150), Color.rgb(220, 190, 90)); c.drawPath(p, fill); done()
                outline(Color.rgb(190, 150, 60))
                fill.color = Color.rgb(45, 30, 60)
                c.drawCircle(x - u * 0.5f, y - u * 0.05f, u * 0.05f, fill)
                stroke.color = Color.rgb(45, 30, 60); stroke.strokeWidth = u * 0.04f
                c.drawArc(x - u * 0.5f, y + u * 0.08f, x - u * 0.34f, y + u * 0.2f, 0f, 160f, false, stroke)
                // the dew drop
                val dx = x + u * 0.5f; val dy = y + u * 0.35f
                p.reset(); p.moveTo(dx, dy - u * 0.38f); p.quadTo(dx + u * 0.3f, dy + u * 0.05f, dx, dy + u * 0.28f); p.quadTo(dx - u * 0.3f, dy + u * 0.05f, dx, dy - u * 0.38f); p.close()
                grad(dx - u * 0.08f, dy, u * 0.4f, Color.rgb(220, 255, 245), Color.rgb(110, 240, 210), Color.rgb(40, 170, 150)); c.drawPath(p, fill); done()
                outline(Color.rgb(30, 140, 120), u * 0.05f)
                fill.color = Color.WHITE; drawSparkle(c, x + u * 0.35f, y - u * 0.65f, u * 0.1f)
            }
            Data.GOLD -> {
                grad(x - u * 0.3f, y - u * 0.35f, u, Color.rgb(255, 250, 200), Color.rgb(255, 205, 60), Color.rgb(200, 140, 20))
                c.drawCircle(x, y, u * 0.82f, fill); done()
                stroke.color = Color.rgb(170, 115, 15); stroke.strokeWidth = u * 0.07f; c.drawCircle(x, y, u * 0.82f, stroke)
                stroke.color = Color.argb(160, 200, 140, 20); stroke.strokeWidth = u * 0.05f; c.drawCircle(x, y, u * 0.62f, stroke)
                for (k in 0 until 10) {
                    val a = -Math.PI / 2 + k * Math.PI / 5
                    val q = if (k % 2 == 0) u * 0.4f else u * 0.17f
                    val px = x + (cos(a) * q).toFloat(); val py = y + (sin(a) * q).toFloat()
                    if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close(); fill.color = Color.rgb(230, 165, 30); c.drawPath(p, fill)
                shine(x - u * 0.35f, y - u * 0.45f, u * 0.18f, u * 0.08f, 190)
            }
            Data.PETAL -> {
                for (k in 0 until 5) {
                    c.save(); c.rotate(k * 72f, x, y)
                    p.reset(); p.moveTo(x, y)
                    p.cubicTo(x - u * 0.45f, y - u * 0.3f, x - u * 0.35f, y - u * 0.88f, x, y - u * 0.82f)
                    p.cubicTo(x + u * 0.35f, y - u * 0.88f, x + u * 0.45f, y - u * 0.3f, x, y); p.close()
                    lin(y - u * 0.85f, y, Color.rgb(255, 215, 235), Color.rgb(250, 120, 175)); c.drawPath(p, fill); done()
                    outline(Color.rgb(215, 85, 140), u * 0.05f)
                    c.restore()
                }
                grad(x - u * 0.06f, y - u * 0.06f, u * 0.25f, Color.rgb(255, 250, 190), Color.rgb(255, 210, 70), Color.rgb(230, 160, 40)); c.drawCircle(x, y, u * 0.22f, fill); done()
            }
            Data.HONEY -> {
                // jar
                p.addRoundRect(x - u * 0.62f, y - u * 0.42f, x + u * 0.62f, y + u * 0.8f, u * 0.28f, u * 0.28f, Path.Direction.CW)
                lin(y - u * 0.42f, y + u * 0.8f, Color.rgb(255, 210, 90), Color.rgb(225, 130, 20)); c.drawPath(p, fill); done()
                outline(Color.rgb(170, 95, 15))
                shine(x - u * 0.38f, y + u * 0.05f, u * 0.08f, u * 0.25f, 150)
                // cloth lid tied with string
                fill.color = Color.rgb(240, 120, 140)
                c.drawRoundRect(x - u * 0.72f, y - u * 0.68f, x + u * 0.72f, y - u * 0.36f, u * 0.14f, u * 0.14f, fill)
                fill.color = Color.rgb(255, 240, 245)
                for (k in -2..2) c.drawCircle(x + k * u * 0.27f, y - u * 0.52f, u * 0.05f, fill)
                stroke.color = Color.rgb(150, 100, 60); stroke.strokeWidth = u * 0.06f
                c.drawLine(x - u * 0.6f, y - u * 0.36f, x + u * 0.6f, y - u * 0.36f, stroke)
                // a drip and a little label heart
                fill.color = Color.rgb(255, 190, 40)
                c.drawRoundRect(x + u * 0.25f, y - u * 0.4f, x + u * 0.42f, y - u * 0.05f, u * 0.08f, u * 0.08f, fill)
                c.drawCircle(x + u * 0.335f, y - u * 0.05f, u * 0.1f, fill)
                fill.color = Color.rgb(255, 245, 225); c.drawRoundRect(x - u * 0.36f, y + u * 0.12f, x + u * 0.36f, y + u * 0.55f, u * 0.08f, u * 0.08f, fill)
                fill.color = Color.rgb(240, 120, 140); drawHeart(c, x, y + u * 0.3f, u * 0.11f)
            }
            Data.SPARK -> {
                fill.shader = RadialGradient(x, y, u, Color.argb(120, 120, 230, 255), Color.argb(0, 120, 230, 255), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, u, fill); done()
                p.moveTo(x + u * 0.18f, y - u * 0.95f); p.lineTo(x - u * 0.55f, y + u * 0.12f); p.lineTo(x - u * 0.04f, y + u * 0.12f)
                p.lineTo(x - u * 0.24f, y + u * 0.95f); p.lineTo(x + u * 0.58f, y - u * 0.2f); p.lineTo(x + u * 0.05f, y - u * 0.2f); p.close()
                lin(y - u, y + u, Color.rgb(255, 255, 190), Color.rgb(255, 210, 50)); c.drawPath(p, fill); done()
                outline(Color.rgb(60, 150, 220))
                fill.color = Color.WHITE; drawSparkle(c, x - u * 0.6f, y - u * 0.55f, u * 0.12f); drawSparkle(c, x + u * 0.62f, y + u * 0.5f, u * 0.09f)
            }
            Data.PUMPKIN -> {
                for ((k, ox) in floatArrayOf(-0.38f, 0.38f, 0f).withIndex()) {
                    val rw = if (k == 2) u * 0.45f else u * 0.4f
                    grad(x + ox * u - u * 0.1f, y - u * 0.1f, u * 0.7f, Color.rgb(255, 190, 90), Color.rgb(250, 135, 35), Color.rgb(200, 85, 20))
                    c.drawOval(x + ox * u - rw, y - u * 0.5f, x + ox * u + rw, y + u * 0.72f, fill); done()
                    stroke.color = Color.rgb(180, 75, 15); stroke.strokeWidth = u * 0.05f
                    c.drawOval(x + ox * u - rw, y - u * 0.5f, x + ox * u + rw, y + u * 0.72f, stroke)
                }
                fill.color = Color.rgb(100, 140, 50); c.drawRoundRect(x - u * 0.08f, y - u * 0.82f, x + u * 0.1f, y - u * 0.42f, u * 0.05f, u * 0.05f, fill)
                fill.color = Color.rgb(120, 190, 70)
                c.save(); c.rotate(-25f, x + u * 0.1f, y - u * 0.62f); c.drawOval(x + u * 0.1f, y - u * 0.72f, x + u * 0.5f, y - u * 0.52f, fill); c.restore()
                shine(x - u * 0.2f, y - u * 0.15f, u * 0.08f, u * 0.18f, 120)
            }
            Data.ECTO -> {
                val wob = sin(t * 3f + x) * u * 0.04f
                p.moveTo(x - u * 0.78f, y + u * 0.62f)
                p.cubicTo(x - u * 0.85f, y - u * 0.2f, x - u * 0.4f, y - u * 0.85f - wob, x, y - u * 0.82f)
                p.cubicTo(x + u * 0.45f, y - u * 0.85f + wob, x + u * 0.85f, y - u * 0.2f, x + u * 0.78f, y + u * 0.62f)
                p.quadTo(x + u * 0.55f, y + u * 0.45f, x + u * 0.38f, y + u * 0.72f)
                p.quadTo(x + u * 0.2f, y + u * 0.5f, x, y + u * 0.68f)
                p.quadTo(x - u * 0.25f, y + u * 0.48f, x - u * 0.42f, y + u * 0.74f)
                p.quadTo(x - u * 0.6f, y + u * 0.5f, x - u * 0.78f, y + u * 0.62f); p.close()
                grad(x - u * 0.2f, y - u * 0.3f, u, Color.rgb(220, 255, 210), Color.rgb(140, 245, 140), Color.rgb(60, 180, 90)); c.drawPath(p, fill); done()
                outline(Color.rgb(50, 150, 75))
                fill.color = Color.rgb(40, 60, 50)
                c.drawOval(x - u * 0.32f, y - u * 0.25f, x - u * 0.14f, y + u * 0.02f, fill); c.drawOval(x + u * 0.14f, y - u * 0.25f, x + u * 0.32f, y + u * 0.02f, fill)
                fill.color = Color.WHITE; c.drawCircle(x - u * 0.2f, y - u * 0.18f, u * 0.04f, fill); c.drawCircle(x + u * 0.26f, y - u * 0.18f, u * 0.04f, fill)
                shine(x - u * 0.45f, y - u * 0.45f, u * 0.12f, u * 0.07f, 180)
            }
            Data.PEPPERMINT -> {
                // wrapper ends
                fill.color = Color.rgb(250, 250, 255)
                for (side in intArrayOf(-1, 1)) {
                    p.reset(); p.moveTo(x + side * u * 0.55f, y); p.lineTo(x + side * u * 0.98f, y - u * 0.32f); p.lineTo(x + side * u * 0.98f, y + u * 0.32f); p.close()
                    c.drawPath(p, fill); outline(Color.rgb(200, 200, 215), u * 0.04f)
                }
                fill.color = Color.WHITE; c.drawCircle(x, y, u * 0.62f, fill)
                fill.color = Color.rgb(230, 50, 65)
                for (k in 0 until 6) c.drawArc(x - u * 0.62f, y - u * 0.62f, x + u * 0.62f, y + u * 0.62f, k * 60f + t * 30f, 30f, true, fill)
                fill.color = Color.WHITE; c.drawCircle(x, y, u * 0.18f, fill)
                stroke.color = Color.rgb(190, 40, 55); stroke.strokeWidth = u * 0.06f; c.drawCircle(x, y, u * 0.62f, stroke)
                shine(x - u * 0.25f, y - u * 0.35f, u * 0.15f, u * 0.07f, 180)
            }
            Data.GINGER -> {
                val body = Color.rgb(200, 125, 60)
                fill.color = body
                c.drawCircle(x, y - u * 0.5f, u * 0.34f, fill)
                p.moveTo(x - u * 0.28f, y - u * 0.22f); p.lineTo(x + u * 0.28f, y - u * 0.22f)
                p.lineTo(x + u * 0.8f, y - u * 0.1f); p.lineTo(x + u * 0.78f, y + u * 0.12f); p.lineTo(x + u * 0.3f, y + u * 0.12f)
                p.lineTo(x + u * 0.52f, y + u * 0.82f); p.lineTo(x + u * 0.2f, y + u * 0.86f); p.lineTo(x, y + u * 0.42f)
                p.lineTo(x - u * 0.2f, y + u * 0.86f); p.lineTo(x - u * 0.52f, y + u * 0.82f); p.lineTo(x - u * 0.3f, y + u * 0.12f)
                p.lineTo(x - u * 0.78f, y + u * 0.12f); p.lineTo(x - u * 0.8f, y - u * 0.1f); p.close()
                stroke.strokeJoin = Paint.Join.ROUND
                stroke.color = body; stroke.strokeWidth = u * 0.22f; c.drawPath(p, stroke)
                c.drawPath(p, fill)
                stroke.strokeJoin = Paint.Join.MITER
                // icing
                stroke.color = Color.WHITE; stroke.strokeWidth = u * 0.06f
                c.drawArc(x - u * 0.18f, y - u * 0.55f, x + u * 0.18f, y - u * 0.3f, 20f, 140f, false, stroke)
                for (side in intArrayOf(-1, 1)) c.drawLine(x + side * u * 0.62f, y - u * 0.08f, x + side * u * 0.62f, y + u * 0.08f, stroke)
                fill.color = Color.rgb(45, 30, 30); c.drawCircle(x - u * 0.12f, y - u * 0.56f, u * 0.05f, fill); c.drawCircle(x + u * 0.12f, y - u * 0.56f, u * 0.05f, fill)
                fill.color = Color.rgb(230, 60, 80); c.drawCircle(x, y - u * 0.02f, u * 0.08f, fill)
                fill.color = Color.rgb(90, 190, 120); c.drawCircle(x, y + u * 0.22f, u * 0.08f, fill)
            }
            Data.CLOVER -> {
                stroke.color = Color.rgb(60, 140, 60); stroke.strokeWidth = u * 0.1f
                c.drawLine(x, y + u * 0.1f, x + u * 0.25f, y + u * 0.88f, stroke)
                for (k in 0 until 4) {
                    c.save(); c.rotate(k * 90f + 45f, x, y)
                    p.reset(); p.moveTo(x, y)
                    p.cubicTo(x - u * 0.55f, y - u * 0.25f, x - u * 0.5f, y - u * 0.82f, x - u * 0.12f, y - u * 0.7f)
                    p.quadTo(x, y - u * 0.62f, x + u * 0.12f, y - u * 0.7f)
                    p.cubicTo(x + u * 0.5f, y - u * 0.82f, x + u * 0.55f, y - u * 0.25f, x, y); p.close()
                    lin(y - u * 0.8f, y, Color.rgb(140, 230, 120), Color.rgb(60, 165, 70)); c.drawPath(p, fill); done()
                    outline(Color.rgb(40, 120, 50), u * 0.05f)
                    stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = u * 0.04f
                    c.drawLine(x, y - u * 0.12f, x, y - u * 0.55f, stroke)
                    c.restore()
                }
            }
            else -> { // rainbow drop
                p.moveTo(x, y - u * 0.95f)
                p.cubicTo(x + u * 0.25f, y - u * 0.5f, x + u * 0.72f, y - u * 0.05f, x + u * 0.68f, y + u * 0.32f)
                p.cubicTo(x + u * 0.62f, y + u * 0.78f, x - u * 0.62f, y + u * 0.78f, x - u * 0.68f, y + u * 0.32f)
                p.cubicTo(x - u * 0.72f, y - u * 0.05f, x - u * 0.25f, y - u * 0.5f, x, y - u * 0.95f); p.close()
                c.save(); c.clipPath(p)
                val cols = intArrayOf(Color.rgb(255, 120, 130), Color.rgb(255, 180, 90), Color.rgb(255, 235, 120), Color.rgb(130, 220, 140), Color.rgb(120, 180, 255), Color.rgb(180, 140, 255))
                val bh = u * 1.8f / cols.size
                for ((k, cc) in cols.withIndex()) { fill.color = cc; c.drawRect(x - u, y - u * 0.95f + k * bh, x + u, y - u * 0.95f + (k + 1) * bh, fill) }
                c.restore()
                outline(Color.rgb(120, 110, 200))
                shine(x - u * 0.28f, y + u * 0.05f, u * 0.1f, u * 0.22f)
            }
        }
        p.reset()
    }

    private val decoClip = Path()

    /** Extra touches on special species: pumpkin ribs, witch hats, candy stripes, clovers... */
    private fun drawCapDeco(c: Canvas, deco: Int, shape: Int, cx: Float, capTop: Float, capBottom0: Float, baseY: Float, s: Float, capC: Int) {
        val capBottom = if (shape == 1) baseY - s * 0.02f else capBottom0
        val ch = capBottom - capTop
        val halfW = when (shape) { 0 -> 0.55f; 1 -> 0.44f; 2 -> 0.4f; 3 -> 0.6f; else -> 0.25f } * s
        // the outline of the cap, for clipping stripes
        decoClip.reset()
        when (shape) {
            1 -> decoClip.addCircle(cx, baseY - s * 0.46f, s * 0.44f, Path.Direction.CW)
            4 -> decoClip.addRoundRect(cx - s * 0.25f, capTop, cx + s * 0.25f, capBottom0 + s * 0.03f, s * 0.25f, s * 0.25f, Path.Direction.CW)
            else -> decoClip.set(path)
        }
        fun clipped(block: () -> Unit) { c.save(); c.clipPath(decoClip); block(); c.restore() }
        fun pumpkinStem() {
            fill.color = Color.rgb(90, 130, 50)
            c.drawRoundRect(cx - s * 0.035f, capTop - s * 0.12f, cx + s * 0.035f, capTop + s * 0.03f, s * 0.02f, s * 0.02f, fill)
            stroke.color = Color.rgb(110, 170, 70); stroke.strokeWidth = s * 0.025f
            c.drawArc(cx, capTop - s * 0.16f, cx + s * 0.16f, capTop - s * 0.02f, 180f, 200f, false, stroke)
        }
        fun ribs() = clipped {
            stroke.color = shade(capC, -0.3f); stroke.strokeWidth = s * 0.025f
            for (k in 1..2) c.drawOval(cx - k * halfW * 0.36f, capTop, cx + k * halfW * 0.36f, capBottom + ch * 0.8f, stroke)
        }
        when (deco) {
            Data.D_PUMPKIN -> { ribs(); pumpkinStem() }
            Data.D_RIBS -> ribs()
            Data.D_JACK -> {
                pumpkinStem()
                val glow = Color.rgb(255, 230, 110 + (50 * sin(t * 7f)).toInt())
                fill.color = glow
                val ey = capTop + ch * 0.48f
                path.reset()
                for (side in intArrayOf(-1, 1)) {
                    val ex = cx + side * halfW * 0.38f
                    path.moveTo(ex - s * 0.06f, ey + s * 0.04f); path.lineTo(ex, ey - s * 0.06f); path.lineTo(ex + s * 0.06f, ey + s * 0.04f); path.close()
                }
                c.drawPath(path, fill)
                path.reset()
                val my = capTop + ch * 0.74f
                path.moveTo(cx - halfW * 0.5f, my - s * 0.03f)
                for (k in 0..4) path.lineTo(cx - halfW * 0.5f + (k + 0.5f) * halfW * 0.2f, my + if (k % 2 == 0) s * 0.04f else -s * 0.01f)
                path.lineTo(cx + halfW * 0.5f, my - s * 0.03f)
                path.lineTo(cx + halfW * 0.4f, my + s * 0.06f); path.lineTo(cx - halfW * 0.4f, my + s * 0.06f); path.close()
                c.drawPath(path, fill)
            }
            Data.D_GHOST -> for (k in 0 until 2) {
                val a = t * 1.3f + k * 3.14f
                val gx = cx + cos(a) * s * 0.7f; val gy = capTop + s * 0.1f + sin(a * 1.7f) * s * 0.12f
                val r = s * 0.1f
                fill.color = Color.argb(200, 255, 255, 255)
                path.reset()
                path.moveTo(gx - r, gy + r); path.lineTo(gx - r, gy)
                path.cubicTo(gx - r, gy - r * 1.4f, gx + r, gy - r * 1.4f, gx + r, gy)
                path.lineTo(gx + r, gy + r); path.lineTo(gx + r * 0.5f, gy + r * 0.7f); path.lineTo(gx, gy + r); path.lineTo(gx - r * 0.5f, gy + r * 0.7f)
                path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(50, 40, 70)
                c.drawCircle(gx - r * 0.35f, gy - r * 0.1f, r * 0.15f, fill); c.drawCircle(gx + r * 0.35f, gy - r * 0.1f, r * 0.15f, fill)
            }
            Data.D_WITCH -> {
                val by = capTop + s * 0.05f
                fill.color = Color.rgb(45, 30, 60)
                c.drawOval(cx - s * 0.26f, by - s * 0.05f, cx + s * 0.26f, by + s * 0.04f, fill)
                path.reset(); path.moveTo(cx - s * 0.15f, by - s * 0.02f)
                path.quadTo(cx - s * 0.05f, by - s * 0.3f, cx + s * 0.14f, by - s * 0.42f)
                path.quadTo(cx + s * 0.06f, by - s * 0.2f, cx + s * 0.15f, by - s * 0.02f); path.close()
                c.drawPath(path, fill)
                fill.color = Color.rgb(255, 150, 60)
                c.drawRect(cx - s * 0.13f, by - s * 0.09f, cx + s * 0.13f, by - s * 0.04f, fill)
            }
            Data.D_BAT -> {
                fill.color = Color.rgb(60, 40, 75)
                val flap = sin(t * 8f) * s * 0.05f
                for (side in intArrayOf(-1, 1)) {
                    val wx = cx + side * halfW * 0.9f; val wy = capTop + ch * 0.4f
                    path.reset()
                    path.moveTo(wx, wy - s * 0.04f)
                    path.quadTo(wx + side * s * 0.15f, wy - s * 0.2f - flap, wx + side * s * 0.3f, wy - s * 0.1f - flap)
                    path.quadTo(wx + side * s * 0.24f, wy - s * 0.02f, wx + side * s * 0.2f, wy + s * 0.03f)
                    path.quadTo(wx + side * s * 0.13f, wy - s * 0.02f, wx + side * s * 0.1f, wy + s * 0.05f)
                    path.quadTo(wx + side * s * 0.05f, wy, wx, wy + s * 0.04f)
                    path.close(); c.drawPath(path, fill)
                }
            }
            Data.D_CANDY -> clipped {
                stroke.color = Color.rgb(230, 50, 60); stroke.strokeWidth = s * 0.08f
                for (k in -6..6) c.drawLine(cx + k * s * 0.2f - s * 0.4f, capTop + ch + s * 0.1f, cx + k * s * 0.2f + s * 0.4f, capTop - s * 0.1f, stroke)
            }
            Data.D_ICING -> clipped {
                fill.color = Color.rgb(255, 250, 245)
                c.drawRect(cx - halfW * 1.1f, capBottom - ch * 0.12f, cx + halfW * 1.1f, capBottom + s * 0.1f, fill)
                for (k in 0..7) c.drawCircle(cx - halfW + k * halfW * 2f / 7f, capBottom - ch * 0.12f, s * 0.055f, fill)
                fill.color = Color.rgb(255, 250, 245)
                c.drawOval(cx - halfW * 0.5f, capTop - s * 0.03f, cx + halfW * 0.5f, capTop + ch * 0.25f, fill)
            }
            Data.D_SNOW -> {
                clipped {
                    fill.color = Color.argb(240, 255, 255, 255)
                    c.drawOval(cx - halfW * 0.85f, capTop - s * 0.06f, cx + halfW * 0.85f, capTop + ch * 0.3f, fill)
                }
                for (k in 0 until 5) {
                    val f = (t * 0.25f + k * 0.21f) % 1f
                    fill.color = Color.argb((230 * (1f - f)).toInt(), 255, 255, 255)
                    c.drawCircle(cx + (k - 2) * s * 0.28f + sin(t + k) * s * 0.05f, capTop - s * 0.25f + f * s * 1.1f, s * 0.025f, fill)
                }
            }
            Data.D_HOLLY -> {
                val hx = cx + halfW * 0.35f; val hy = capTop + s * 0.08f
                fill.color = Color.rgb(50, 150, 70)
                c.drawOval(hx - s * 0.17f, hy - s * 0.05f, hx, hy + s * 0.04f, fill)
                c.drawOval(hx, hy - s * 0.05f, hx + s * 0.17f, hy + s * 0.04f, fill)
                fill.color = Color.rgb(220, 40, 50)
                c.drawCircle(hx - s * 0.03f, hy - s * 0.04f, s * 0.035f, fill)
                c.drawCircle(hx + s * 0.03f, hy - s * 0.04f, s * 0.035f, fill)
                c.drawCircle(hx, hy - s * 0.08f, s * 0.035f, fill)
            }
            Data.D_SPROUT -> {
                stroke.color = Color.rgb(70, 150, 60); stroke.strokeWidth = s * 0.03f
                c.drawLine(cx, capTop + s * 0.02f, cx, capTop - s * 0.1f, stroke)
                fill.color = Color.rgb(110, 200, 90)
                c.drawOval(cx - s * 0.16f, capTop - s * 0.17f, cx, capTop - s * 0.08f, fill)
                c.drawOval(cx, capTop - s * 0.2f, cx + s * 0.16f, capTop - s * 0.11f, fill)
            }
            Data.D_CLOVER4 -> {
                stroke.color = Color.rgb(50, 130, 60); stroke.strokeWidth = s * 0.03f
                c.drawLine(cx, capTop + s * 0.02f, cx + s * 0.03f, capTop - s * 0.08f, stroke)
                c.save(); c.rotate(sin(t * 2f) * 10f, cx, capTop)
                fourLeafClover(c, cx + s * 0.03f, capTop - s * 0.14f, s * 0.17f, Color.rgb(90, 200, 100))
                c.restore()
            }
            Data.D_RAINBOW -> clipped {
                val cols = intArrayOf(Color.rgb(255, 110, 130), Color.rgb(255, 180, 80), Color.rgb(255, 235, 110), Color.rgb(120, 220, 140), Color.rgb(110, 170, 255))
                val bh = (ch + s * 0.1f) / cols.size
                for ((k, cc) in cols.withIndex()) {
                    fill.color = Color.argb(200, Color.red(cc), Color.green(cc), Color.blue(cc))
                    c.drawRect(cx - s, capTop - s * 0.05f + k * bh, cx + s, capTop - s * 0.05f + (k + 1) * bh, fill)
                }
            }
            Data.D_FLOWER -> {
                val fx = cx - halfW * 0.45f; val fy = capTop + ch * 0.18f + s * 0.02f
                fill.color = Color.WHITE
                for (k in 0 until 5) {
                    val a = k * 1.2566f + t * 0.5f
                    c.drawCircle(fx + cos(a) * s * 0.05f, fy + sin(a) * s * 0.05f, s * 0.042f, fill)
                }
                fill.color = Color.rgb(255, 210, 80); c.drawCircle(fx, fy, s * 0.032f, fill)
            }
            Data.D_BEE -> {
                clipped {
                    fill.color = Color.rgb(60, 40, 40)
                    for (f in floatArrayOf(0.22f, 0.5f)) c.drawRect(cx - s, capTop + ch * f, cx + s, capTop + ch * f + ch * 0.13f, fill)
                }
                fill.color = Color.argb(170, 255, 255, 255)
                val flap = abs(sin(t * 20f))
                c.drawOval(cx - s * 0.2f, capTop - s * 0.08f - flap * s * 0.04f, cx - s * 0.02f, capTop + s * 0.02f, fill)
                c.drawOval(cx + s * 0.02f, capTop - s * 0.08f - flap * s * 0.04f, cx + s * 0.2f, capTop + s * 0.02f, fill)
            }
            Data.D_BOLT -> {
                fill.color = Color.rgb(255, 240, 120)
                lightningBolt(c, cx + halfW * 0.25f, capTop + ch * 0.5f, min(ch * 0.32f, s * 0.14f))
            }
            Data.D_HEART -> {
                fill.color = Color.rgb(255, 235, 245)
                for ((px, py, pr) in arrayOf(Triple(-0.3f, 0.45f, 0.06f), Triple(0.1f, 0.22f, 0.05f), Triple(0.32f, 0.58f, 0.045f)))
                    drawHeart(c, cx + px * halfW * 2f, capTop + py * ch, s * pr)
            }
        }
    }


    private fun lightningBolt(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x + r * 0.2f, y - r); path.lineTo(x - r * 0.55f, y + r * 0.12f); path.lineTo(x - r * 0.02f, y + r * 0.12f)
        path.lineTo(x - r * 0.25f, y + r); path.lineTo(x + r * 0.55f, y - r * 0.15f); path.lineTo(x + r * 0.02f, y - r * 0.15f)
        path.close(); c.drawPath(path, fill)
    }

    private fun fourLeafClover(c: Canvas, x: Float, y: Float, r: Float, color: Int, leaves: Int = 4) {
        fill.color = color
        for (k in 0 until leaves) {
            c.save(); c.rotate(k * 360f / leaves + 45f, x, y)
            c.drawCircle(x - r * 0.22f, y - r * 0.42f, r * 0.27f, fill)
            c.drawCircle(x + r * 0.22f, y - r * 0.42f, r * 0.27f, fill)
            path.reset(); path.moveTo(x - r * 0.46f, y - r * 0.36f); path.lineTo(x, y); path.lineTo(x + r * 0.46f, y - r * 0.36f); path.close()
            c.drawPath(path, fill)
            c.restore()
        }
    }

    private fun drawFood(c: Canvas, f: Int, x: Float, y: Float, r: Float) {
        val col = Data.FOODS[f].color
        fill.color = col; fill.alpha = 45
        c.drawCircle(x, y, r * 1.35f, fill)
        fill.alpha = 255
        path.reset()
        if (Data.FOODS[f].cooked) { drawCookedFood(c, f, x, y, r); return }
        if (f >= 5) { drawEventFood(c, f, x, y, r); return }
        when (f) {
            0 -> {
                path.moveTo(x, y - r); path.quadTo(x + r * 0.9f, y + r * 0.1f, x, y + r * 0.8f)
                path.quadTo(x - r * 0.9f, y + r * 0.1f, x, y - r); path.close(); c.drawPath(path, fill)
            }
            1 -> {
                c.drawArc(x - r, y - r * 0.5f, x + r, y + r, 180f, 180f, true, fill)
                fill.color = Color.rgb(120, 210, 110)
                c.drawOval(x - r * 0.1f, y - r * 0.9f, x + r * 0.55f, y - r * 0.45f, fill)
            }
            2 -> {
                c.drawRoundRect(x - r * 0.7f, y - r * 0.7f, x + r * 0.7f, y + r * 0.7f, r * 0.2f, r * 0.2f, fill)
                fill.color = Color.rgb(220, 215, 205)
                c.drawRect(x - r * 0.7f, y + r * 0.2f, x + r * 0.7f, y + r * 0.7f, fill)
            }
            3 -> {
                c.drawCircle(x - r * 0.4f, y + r * 0.3f, r * 0.42f, fill)
                fill.color = Color.rgb(190, 120, 255); c.drawCircle(x + r * 0.4f, y + r * 0.3f, r * 0.38f, fill)
                fill.color = Color.rgb(90, 170, 255); c.drawCircle(x, y - r * 0.35f, r * 0.4f, fill)
            }
            else -> {
                path.moveTo(x - r, y + r * 0.6f)
                path.cubicTo(x - r, y - r * 1.1f, x + r, y - r * 1.1f, x + r, y + r * 0.6f)
                path.close(); c.drawPath(path, fill)
                fill.color = Color.argb(140, 255, 255, 255)
                c.drawOval(x - r * 0.55f, y - r * 0.5f, x - r * 0.15f, y - r * 0.15f, fill)
            }
        }
    }
}
