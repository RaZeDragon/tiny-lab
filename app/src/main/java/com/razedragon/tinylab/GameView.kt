package com.razedragon.tinylab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import com.razedragon.tinylab.Sound.Fx
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shroomology: brew ingredients into cute mushroom monsters, raise them in your garden,
 * and crossbreed grown-ups to discover rare species. Everything is drawn with Canvas.
 */
class GameView(context: Context) : View(context) {

    private enum class Tab { GARDEN, LAB, DEX, SHOP }

    private class Button(
        val id: String, val rect: RectF, val label: String, val style: Int = STYLE_NORMAL,
        val enabled: Boolean = true, val custom: ((Canvas, RectF) -> Unit)? = null, val action: () -> Unit,
    )
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
                           val maxLife: Float, val color: Int, val type: Int, var tx: Float = 0f, var ty: Float = 0f)
    private class Toast(val text: String, val color: Int)
    private class Popup(val species: Int, val isNew: Boolean, val shroomId: Long, val crossed: Boolean)

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

        private val BG_TOP = Color.rgb(36, 24, 66)
        private val BG = Color.rgb(26, 18, 48)
        private val PANEL = Color.rgb(48, 34, 86)
        private val PANEL_LIGHT = Color.rgb(66, 48, 112)
        private val ACCENT = Color.rgb(255, 120, 170)
        private val DUST = Color.rgb(255, 214, 110)
        private val MINT = Color.rgb(130, 255, 200)
        private val TEXT_DIM = Color.argb(170, 255, 255, 255)

        private const val CROSS_COST = 15
        private const val REST_MS = 20 * 60 * 1000L
    }

    private val dp = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("tinylab_settings", Context.MODE_PRIVATE)
    private val lab = LabState(context)
    private val sound = Sound(context)

    private var tab = Tab.GARDEN
    private var lastFrame = 0L
    private var t = 0f                 // seconds since launch, for animation
    private var tickAcc = 0f
    private var saveAcc = 0f
    private var dirty = false

    // Garden
    private var selected: Long = -1L
    private var feeding = false
    private var confirmRelease = false

    // Lab
    private var labMode = 0             // 0 brew, 1 crossbreed
    private val slots = intArrayOf(-1, -1)
    private val picks = ArrayList<Long>()
    private var brewing = 0f
    private var popup: Popup? = null

    // Dex / shop
    private var dexPick = -1
    private var shopTab = 0

    // Settings: 0 = all sound, 1 = no music, 2 = muted
    private var soundMode = prefs.getInt("soundMode", 0)

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
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()

    init {
        keepScreenOn = false
        applySoundMode()
        lab.tick()
    }

    private fun applySoundMode() {
        sound.sfxOn = soundMode < 2
        sound.musicOn = soundMode == 0
    }

    private fun shroom(id: Long) = lab.garden.firstOrNull { it.id == id }

    // ---------- Layout helpers ----------

    private val topBar get() = 64f * dp
    private val navH get() = 78f * dp
    private val navTop get() = height - navH

    // ---------- Lifecycle ----------

    fun onAppPause() {
        lab.tick(); lab.save()
        sound.onAppPause()
    }

    fun onAppResume() {
        lab.tick()
        sound.onAppResume()
    }

    fun onAppDestroy() {
        lab.save()
        sound.release()
    }

    fun onBack(): Boolean {
        when {
            popup != null -> popup = null
            feeding -> feeding = false
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
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, 60))
        else v.vibrate(ms)
    }

    // ---------- Game actions ----------

    private fun gardenFull() = lab.garden.size >= lab.gardenSize

    private fun reserved(i: Int) = slots.count { it == i }

    private fun startBrew() {
        if (slots[0] < 0 || slots[1] < 0) return
        if (gardenFull()) { sound.play(Fx.DENIED); toast("Your garden is full! Release a mushroom first", ACCENT); return }
        brewing = 1.6f
        sound.play(Fx.BUBBLE)
    }

    private fun finishBrew() {
        val a = slots[0]; val b = slots[1]
        lab.ingredients[a]--; lab.ingredients[b]--
        slots[0] = -1; slots[1] = -1
        val sp = Data.brew(a, b)
        hatch(sp, crossed = false)
        lab.brewed++
        if (lab.tutorial < 2) lab.tutorial = 2
    }

    private fun startCross() {
        if (picks.size < 2) return
        if (gardenFull()) { sound.play(Fx.DENIED); toast("Your garden is full! Release a mushroom first", ACCENT); return }
        if (lab.dust < CROSS_COST) { sound.play(Fx.DENIED); toast("Crossbreeding costs $CROSS_COST ✦ spore dust", ACCENT); return }
        brewing = 1.6f
        sound.play(Fx.BUBBLE)
    }

    private fun finishCross() {
        val a = shroom(picks[0]); val b = shroom(picks[1])
        picks.clear()
        if (a == null || b == null) return
        lab.dust -= CROSS_COST
        val now = System.currentTimeMillis()
        a.restUntil = now + REST_MS; b.restUntil = now + REST_MS
        var sp = Data.cross(a.species, b.species)
        if (sp < 0) sp = if (Random.nextBoolean()) a.species else b.species
        hatch(sp, crossed = true)
    }

    private fun hatch(sp: Int, crossed: Boolean) {
        val isNew = !lab.discovered[sp]
        lab.discovered[sp] = true
        val s = Shroom(lab.newId(), sp)
        lab.garden.add(s)
        popup = Popup(sp, isNew, s.id, crossed)
        sound.play(if (isNew) Fx.DISCOVER else Fx.BREW_DONE)
        buzz(if (isNew) 60 else 25)
        burst(width / 2f, height * 0.42f, if (isNew) DUST else MINT, if (isNew) 50 else 25, P_SPARK)
        markDirty()
    }

    private fun feed(s: Shroom, f: Int) {
        val food = Data.FOODS[f]
        if (lab.foods[f] <= 0) { sound.play(Fx.DENIED); toast("Out of ${food.name}. Buy more in the Shop", ACCENT); return }
        if (s.hunger >= 95f) { sound.play(Fx.DENIED); toast("${Data.SPECIES[s.species].name} is too full!"); return }
        val fav = Data.SPECIES[s.species].favFood == f
        val before = s.stage()
        lab.foods[f]--
        s.hunger = min(100f, s.hunger + food.hunger)
        s.xp += food.xp * if (fav) 2f else 1f
        s.happy = min(100f, s.happy + food.happy + if (fav) 15f else 0f)
        sound.play(Fx.EAT)
        val (x, y) = shroomPos(lab.garden.indexOf(s))
        burst(x, y - 30f * dp, food.color, 10, P_SPARK)
        if (fav) {
            toast("Yum! That's its favorite food!", ACCENT)
            burst(x, y - 40f * dp, ACCENT, 6, P_HEART)
        }
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
        val reward = 10 + (s.xp / 5f).toInt() * Data.TIER_MUL[s.tier()]
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
        if (lab.dust < cost) { sound.play(Fx.DENIED); toast("Not enough spore dust ✦", ACCENT); return }
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
        val list = ArrayList<Button>()

        // Popup takes over the screen
        popup?.let { p ->
            val bw = min(w * 0.36f, 160f * dp)
            val y = h * 0.66f
            list.add(Button("p_more", RectF(w / 2 - bw - 6f * dp, y, w / 2 - 6f * dp, y + 52f * dp),
                if (p.crossed) "BREED MORE" else "BREW MORE") { popup = null })
            list.add(Button("p_see", RectF(w / 2 + 6f * dp, y, w / 2 + bw + 6f * dp, y + 52f * dp), "SEE IT", STYLE_PRIMARY) {
                popup = null; tab = Tab.GARDEN; selected = p.shroomId; feeding = false
                if (lab.tutorial == 2) lab.tutorial = 3
            })
            return list
        }

        // Bottom navigation
        val tabs = Tab.values()
        val tw = w / tabs.size
        for ((i, tb) in tabs.withIndex()) {
            list.add(Button("nav$i", RectF(i * tw, navTop, (i + 1) * tw, h), "", STYLE_CARD,
                custom = { c, r -> drawNavItem(c, r, tb) }) {
                if (tab != tb) { tab = tb; selected = -1; feeding = false; confirmRelease = false; dexPick = -1 }
            })
        }

        // Sound toggle in the top bar
        list.add(Button("sound", RectF(w - 52f * dp, 14f * dp, w - 12f * dp, 54f * dp), "", STYLE_CARD,
            custom = { c, r -> drawSpeaker(c, r) }) {
            soundMode = (soundMode + 1) % 3
            prefs.edit().putInt("soundMode", soundMode).apply()
            applySoundMode()
            toast(arrayOf("Sound + music on", "Music off", "All sound off")[soundMode])
        })

        when (tab) {
            Tab.GARDEN -> gardenButtons(list, w, h)
            Tab.LAB -> labButtons(list, w, h)
            Tab.DEX -> dexButtons(list, w, h)
            Tab.SHOP -> shopButtons(list, w, h)
        }
        return list
    }

    private fun panelTop() = height * 0.64f

    private fun gardenButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val totalDust = lab.garden.sumOf { it.dust.toInt() }
        if (totalDust >= 1 && selected < 0) {
            list.add(Button("collect", RectF(w - 170f * dp, topBar + 8f * dp, w - 14f * dp, topBar + 48f * dp),
                "Collect $totalDust ✦", STYLE_PRIMARY) {
                for ((i, s) in lab.garden.withIndex()) collect(s, i)
            })
        }

        val s = shroom(selected) ?: return
        val top = panelTop()
        val pad = 16f * dp
        val by = navTop - 64f * dp
        if (feeding) {
            val n = Data.FOODS.size
            val cw = (w - pad * 2) / n
            for (f in 0 until n) {
                val r = RectF(pad + f * cw + 3f * dp, top + 56f * dp, pad + (f + 1) * cw - 3f * dp, by - 10f * dp)
                list.add(Button("food$f", r, "", STYLE_CARD, lab.foods[f] > 0, custom = { c, rr -> drawFoodCard(c, rr, f, s) }) {
                    feed(s, f)
                })
            }
            list.add(Button("fback", RectF(pad, by, w - pad, by + 50f * dp), "DONE") { feeding = false })
        } else {
            val bw = (w - pad * 2 - 16f * dp) / 3
            list.add(Button("feed", RectF(pad, by, pad + bw, by + 50f * dp), "FEED", STYLE_PRIMARY) {
                feeding = true; confirmRelease = false
            })
            list.add(Button("pet", RectF(pad + bw + 8f * dp, by, pad + bw * 2 + 8f * dp, by + 50f * dp), "PET") {
                pet(s); confirmRelease = false
            })
            list.add(Button("release", RectF(pad + bw * 2 + 16f * dp, by, w - pad, by + 50f * dp),
                if (confirmRelease) "SURE?" else "RELEASE", STYLE_DANGER) {
                if (confirmRelease) release(s) else confirmRelease = true
            })
        }
    }

    private fun flaskY() = height * 0.29f

    private fun labButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 16f * dp
        val tabW = (w - pad * 2) / 2
        val ty = topBar + 6f * dp
        list.add(Button("m0", RectF(pad, ty, pad + tabW - 4f * dp, ty + 42f * dp), "BREW",
            if (labMode == 0) STYLE_TAB_ON else STYLE_NORMAL) { if (brewing <= 0f) labMode = 0 })
        list.add(Button("m1", RectF(pad + tabW + 4f * dp, ty, w - pad, ty + 42f * dp), "CROSSBREED",
            if (labMode == 1) STYLE_TAB_ON else STYLE_NORMAL) { if (brewing <= 0f) labMode = 1 })

        val fy = flaskY()
        val slotR = 34f * dp
        for (k in 0..1) {
            val sx = w / 2 + (if (k == 0) -1 else 1) * min(w * 0.33f, 130f * dp)
            val r = RectF(sx - slotR, fy - slotR, sx + slotR, fy + slotR)
            list.add(Button("slot$k", r, "", STYLE_CARD, custom = { c, rr -> drawSlot(c, rr, k) }) {
                if (brewing > 0f) return@Button
                if (labMode == 0) slots[k] = -1 else if (picks.size > k) picks.removeAt(k)
            })
        }

        val by = h * 0.45f
        val bw = min(w * 0.6f, 240f * dp)
        if (labMode == 0) {
            val ready = slots[0] >= 0 && slots[1] >= 0 && brewing <= 0f
            list.add(Button("brew", RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + 54f * dp),
                if (brewing > 0f) "BREWING..." else "BREW", STYLE_PRIMARY, ready) { startBrew() })

            val gy = by + 70f * dp
            val cols = 3
            val cw = (w - pad * 2) / cols
            val ch = min(96f * dp, (navTop - gy - 8f * dp) / 3)
            for (i in Data.INGREDIENTS.indices) {
                val r = RectF(pad + (i % cols) * cw + 4f * dp, gy + (i / cols) * ch + 4f * dp,
                    pad + (i % cols + 1) * cw - 4f * dp, gy + (i / cols + 1) * ch - 4f * dp)
                val left = lab.ingredients[i] - reserved(i)
                list.add(Button("ing$i", r, "", STYLE_CARD, left > 0, custom = { c, rr -> drawIngredientCard(c, rr, i, left) }) {
                    if (brewing > 0f) return@Button
                    if (left <= 0) { sound.play(Fx.DENIED); toast("Out of ${Data.INGREDIENTS[i].name}. Buy more in the Shop", ACCENT); return@Button }
                    val empty = slots.indexOf(-1)
                    if (empty < 0) { toast("Both slots are full. Tap a slot to empty it"); return@Button }
                    slots[empty] = i
                    sound.play(Fx.POP, 0.8f, 0.9f + empty * 0.2f)
                })
            }
        } else {
            val ready = picks.size == 2 && brewing <= 0f
            list.add(Button("cross", RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + 54f * dp),
                if (brewing > 0f) "BREEDING..." else "CROSSBREED  $CROSS_COST ✦", STYLE_PRIMARY, ready) { startCross() })

            val gy = by + 92f * dp
            val cols = 4
            val cw = (w - pad * 2) / cols
            val ch = min(120f * dp, (navTop - gy - 8f * dp) / 2)
            val now = System.currentTimeMillis()
            for ((i, s) in lab.garden.withIndex()) {
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

    private fun dexTop() = topBar + 44f * dp
    private fun dexBottom() = height * 0.74f

    private fun dexButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 12f * dp
        val cols = 4
        val rows = (Data.SPECIES.size + cols - 1) / cols
        val cw = (w - pad * 2) / cols
        val ch = (dexBottom() - dexTop()) / rows
        for (i in Data.SPECIES.indices) {
            val r = RectF(pad + (i % cols) * cw + 3f * dp, dexTop() + (i / cols) * ch + 3f * dp,
                pad + (i % cols + 1) * cw - 3f * dp, dexTop() + (i / cols + 1) * ch - 3f * dp)
            list.add(Button("dex$i", r, "", if (dexPick == i) STYLE_PICKED else STYLE_CARD,
                custom = { c, rr -> drawDexCell(c, rr, i) }) { dexPick = i })
        }
    }

    private fun shopButtons(list: ArrayList<Button>, w: Float, h: Float) {
        val pad = 16f * dp
        val tabW = (w - pad * 2) / 2
        val ty = topBar + 6f * dp
        list.add(Button("s0", RectF(pad, ty, pad + tabW - 4f * dp, ty + 42f * dp), "INGREDIENTS",
            if (shopTab == 0) STYLE_TAB_ON else STYLE_NORMAL) { shopTab = 0 })
        list.add(Button("s1", RectF(pad + tabW + 4f * dp, ty, w - pad, ty + 42f * dp), "FOOD",
            if (shopTab == 1) STYLE_TAB_ON else STYLE_NORMAL) { shopTab = 1 })

        val n = if (shopTab == 0) Data.INGREDIENTS.size else Data.FOODS.size
        val top = ty + 56f * dp
        val rowH = min(66f * dp, (navTop - top - 8f * dp) / n)
        for (i in 0 until n) {
            val y = top + i * rowH
            val cost = if (shopTab == 0) Data.INGREDIENTS[i].cost else Data.FOODS[i].cost
            val b5 = RectF(w - pad - 64f * dp, y + 6f * dp, w - pad, y + rowH - 6f * dp)
            val b1 = RectF(b5.left - 8f * dp - 84f * dp, y + 6f * dp, b5.left - 8f * dp, y + rowH - 6f * dp)
            val k = shopTab
            list.add(Button("buy1_$i", b1, "$cost ✦", STYLE_PRIMARY, lab.dust >= cost) { buy(k, i, 1) })
            list.add(Button("buy5_$i", b5, "×5", STYLE_NORMAL, lab.dust >= cost * 5) { buy(k, i, 5) })
        }
    }

    // ---------- Input ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedId = buttons.lastOrNull { it.rect.contains(x, y) }?.id
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedId
                pressedId = null
                val b = buttons.lastOrNull { it.id == id && it.rect.contains(x, y) }
                if (b != null) {
                    sound.play(Fx.CLICK, 0.6f)
                    b.action()
                } else if (id == null && popup == null && tab == Tab.GARDEN) {
                    tapGarden(x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedId = null
        }
        return true
    }

    private fun tapGarden(x: Float, y: Float) {
        if (selected >= 0 && y >= panelTop()) return
        for ((i, s) in lab.garden.withIndex()) {
            val (sx, sy) = shroomPos(i)
            val size = shroomSize(s)
            if (hypot(x - sx, y - (sy - size * 0.45f)) < max(size * 0.7f, 40f * dp)) {
                collect(s, i)
                if (selected != s.id) { selected = s.id; feeding = false; confirmRelease = false }
                sound.play(Fx.POP, 0.7f, 0.9f + Random.nextFloat() * 0.3f)
                blinkAt[s.id] = t
                if (lab.tutorial == 2) lab.tutorial = 3
                return
            }
        }
        selected = -1; feeding = false; confirmRelease = false
    }

    // ---------- Loop ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1_000_000_000f, 0.05f)
        lastFrame = now
        update(dt)
        buttons = buildButtons()
        render(canvas)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        t += dt
        tickAcc += dt
        if (tickAcc >= 1f) { tickAcc = 0f; lab.tick(); markDirty() }
        saveAcc += dt
        if (saveAcc >= 5f) { saveAcc = 0f; if (dirty) { lab.save(); dirty = false } }

        if (toasts.isNotEmpty()) {
            toastTime -= dt
            if (toastTime <= 0f) { toasts.removeFirst(); toastTime = 2.4f }
        }

        if (brewing > 0f) {
            brewing -= dt
            if (Random.nextFloat() < 0.5f) {
                particles.add(Particle(width / 2f + (Random.nextFloat() - 0.5f) * 60f * dp, flaskY(),
                    (Random.nextFloat() - 0.5f) * 30f * dp, -(60f + Random.nextFloat() * 80f) * dp,
                    1f, 1f, liquidColor(), P_BUBBLE))
            }
            if (brewing <= 0f) { if (labMode == 0) finishBrew() else finishCross() }
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

        // Random blinking
        if (Random.nextFloat() < dt * 1.2f && lab.garden.isNotEmpty()) blinkAt[lab.garden.random().id] = t
    }

    // ---------- Rendering ----------

    private fun render(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(BG)
        // soft lab glow at the top
        fill.color = BG_TOP
        c.drawRect(0f, 0f, w, h * 0.5f, fill)
        fill.color = Color.argb(40, 255, 120, 200)
        c.drawCircle(w * 0.8f, h * 0.12f, w * 0.5f, fill)
        fill.color = Color.argb(30, 120, 200, 255)
        c.drawCircle(w * 0.1f, h * 0.35f, w * 0.45f, fill)

        when (tab) {
            Tab.GARDEN -> drawGarden(c, w, h)
            Tab.LAB -> drawLab(c, w, h)
            Tab.DEX -> drawDex(c, w, h)
            Tab.SHOP -> drawShop(c, w, h)
        }

        drawTopBar(c, w)
        // nav background
        fill.color = Color.rgb(20, 14, 38)
        c.drawRect(0f, navTop, w, h, fill)
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawRect(0f, navTop, w, navTop + 1f * dp, fill)

        for (b in buttons) drawButton(c, b)
        drawParticles(c)
        drawTutorial(c, w, h)
        popup?.let { drawPopup(c, w, h, it) ; for (b in buttons) drawButton(c, b) }
        drawToast(c, w, h)
    }

    private fun drawTopBar(c: Canvas, w: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 24f * dp
        text.color = Color.WHITE
        val title = when (tab) { Tab.GARDEN -> "GARDEN"; Tab.LAB -> "LAB"; Tab.DEX -> "SHROOMDEX"; Tab.SHOP -> "SHOP" }
        c.drawText(title, 18f * dp, 44f * dp, text)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 20f * dp
        text.color = DUST
        c.drawText("${lab.dust} ✦", w - 62f * dp, 42f * dp, text)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
    }

    // ----- Garden -----

    private fun shroomPos(i: Int): Pair<Float, Float> {
        val w = width.toFloat(); val h = height.toFloat()
        val col = i % 4; val row = i / 4
        val stagger = if (row == 1) w / 8f * 0.0f else 0f
        return Pair(w * (0.125f + col * 0.25f) + stagger, if (row == 0) h * 0.36f else h * 0.56f)
    }

    private fun shroomSize(s: Shroom): Float {
        val base = min(width / 4f * 0.78f, 86f * dp)
        return base * floatArrayOf(0.45f, 0.65f, 0.85f, 1f)[s.stage()]
    }

    private fun drawGarden(c: Canvas, w: Float, h: Float) {
        // soil beds
        for (row in 0..1) {
            val y = if (row == 0) h * 0.36f else h * 0.56f
            fill.color = Color.rgb(58, 40, 52)
            c.drawRoundRect(10f * dp, y - 6f * dp, w - 10f * dp, y + 22f * dp, 14f * dp, 14f * dp, fill)
            fill.color = Color.rgb(78, 56, 64)
            c.drawRoundRect(10f * dp, y - 6f * dp, w - 10f * dp, y + 4f * dp, 8f * dp, 8f * dp, fill)
            // little grass tufts
            stroke.color = Color.rgb(110, 200, 130); stroke.strokeWidth = 2f * dp
            for (k in 0 until 9) {
                val gx = 24f * dp + k * (w - 48f * dp) / 8f
                c.drawLine(gx, y - 4f * dp, gx - 3f * dp, y - 11f * dp, stroke)
                c.drawLine(gx + 3f * dp, y - 4f * dp, gx + 5f * dp, y - 10f * dp, stroke)
            }
        }

        if (lab.garden.isEmpty()) {
            text.textSize = 17f * dp; text.color = TEXT_DIM
            c.drawText("Your garden is empty", w / 2, h * 0.22f, text)
            text.textSize = 14f * dp
            c.drawText("Brew a mushroom in the Lab to get started", w / 2, h * 0.22f + 24f * dp, text)
            text.color = Color.WHITE
        }

        text.textSize = 12f * dp; text.color = TEXT_DIM
        c.drawText("${lab.garden.size} / ${lab.gardenSize} mushrooms", w / 2, h * 0.62f + 6f * dp, text)
        text.color = Color.WHITE

        for ((i, s) in lab.garden.withIndex()) {
            val (x, y) = shroomPos(i)
            val size = shroomSize(s)
            if (s.id == selected) {
                fill.color = Color.argb(60, 255, 255, 255)
                c.drawOval(x - size * 0.7f, y - size * 0.12f, x + size * 0.7f, y + size * 0.14f, fill)
            }
            val bob = sin(t * 2.2f + i * 1.3f)
            drawShroom(c, s.species, x, y, size, s.mood(), bob, isBlinking(s.id), false)
            // hunger warning
            if (s.mood() == 2) {
                text.textSize = 14f * dp
                c.drawText("🍽", x + size * 0.45f, y - size * 1.0f, text)
            }
            // spore dust waiting
            if (s.dust >= 1f) {
                val dy = y - size * 1.15f - 16f * dp + sin(t * 3f + i) * 3f * dp
                fill.color = Color.argb(70, 255, 214, 110)
                c.drawCircle(x, dy, 15f * dp, fill)
                fill.color = DUST
                c.drawCircle(x, dy, 10f * dp, fill)
                text.textSize = 10f * dp; text.color = BG
                c.drawText("${s.dust.toInt()}", x, dy + 3.5f * dp, text)
                text.color = Color.WHITE
            }
        }

        shroom(selected)?.let { drawInfoPanel(c, w, it) }
    }

    private fun isBlinking(id: Long): Boolean {
        val b = blinkAt[id] ?: return false
        return t - b < 0.15f
    }

    private fun drawInfoPanel(c: Canvas, w: Float, s: Shroom) {
        val top = panelTop()
        val r = RectF(8f * dp, top, w - 8f * dp, navTop - 6f * dp)
        fill.color = PANEL
        c.drawRoundRect(r, 22f * dp, 22f * dp, fill)
        val sp = Data.SPECIES[s.species]
        val pad = 24f * dp

        text.textAlign = Paint.Align.LEFT
        text.textSize = 20f * dp
        c.drawText(sp.name, pad, top + 32f * dp, text)
        text.textSize = 13f * dp; text.color = TEXT_DIM
        val tierName = arrayOf("", "Common", "Hybrid", "Legendary")[sp.tier]
        c.drawText("${Data.STAGE_NAMES[s.stage()]} · $tierName · loves ${Data.FOODS[sp.favFood].name}", pad, top + 50f * dp, text)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER

        if (feeding) return

        val stage = s.stage()
        val barX = pad + 70f * dp
        val barW = w - barX - pad
        var y = top + 74f * dp
        if (stage < Data.STAGE_XP.size - 1) {
            val lo = Data.STAGE_XP[stage]; val hi = Data.STAGE_XP[stage + 1]
            bar(c, "Growth", barX, y, barW, (s.xp - lo) / (hi - lo), MINT)
        } else bar(c, "Growth", barX, y, barW, 1f, DUST)
        y += 26f * dp
        bar(c, "Food", barX, y, barW, s.hunger / 100f, if (s.hunger < 25f) ACCENT else Color.rgb(255, 170, 90))
        y += 26f * dp
        bar(c, "Happy", barX, y, barW, s.happy / 100f, Color.rgb(255, 140, 200))
        y += 22f * dp
        text.textSize = 12f * dp; text.color = TEXT_DIM
        val moodText = when (s.mood()) {
            0 -> "Feeling great! Growing fast."
            1 -> "Doing fine."
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

    private fun drawLab(c: Canvas, w: Float, h: Float) {
        val fx = w / 2
        val fy = flaskY()
        val shakeX = if (brewing > 0f) sin(t * 50f) * 3f * dp else 0f
        val r = 62f * dp

        // "+" signs between slots and flask
        text.textSize = 26f * dp; text.color = TEXT_DIM
        val off = min(w * 0.33f, 130f * dp)
        c.drawText("+", fx - off / 2 - 14f * dp, fy + 9f * dp, text)
        c.drawText("+", fx + off / 2 + 14f * dp, fy + 9f * dp, text)
        text.color = Color.WHITE

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
        c.restore()

        text.textSize = 13f * dp; text.color = TEXT_DIM
        val hint = if (labMode == 0) {
            if (slots[0] >= 0 && slots[1] >= 0) "${Data.INGREDIENTS[slots[0]].name} + ${Data.INGREDIENTS[slots[1]].name}"
            else "Pick two ingredients"
        } else "Pick two Grown mushrooms"
        c.drawText(hint, fx, fy + r + 24f * dp, text)
        text.color = Color.WHITE

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
            if (s != null) drawShroom(c, s.species, r.centerX(), r.centerY() + r.height() * 0.32f, r.width() * 0.5f, 0, 0f, false, false)
            else { text.textSize = 22f * dp; text.color = TEXT_DIM; c.drawText("?", r.centerX(), r.centerY() + 8f * dp, text); text.color = Color.WHITE }
        }
    }

    private fun drawIngredientCard(c: Canvas, r: RectF, i: Int, left: Int) {
        drawIngredient(c, i, r.centerX(), r.top + r.height() * 0.36f, min(r.width(), r.height()) * 0.2f)
        text.textSize = 13f * dp
        c.drawText(Data.INGREDIENTS[i].name, r.centerX(), r.top + r.height() * 0.72f, text)
        text.textSize = 12f * dp
        text.color = if (left > 0) TEXT_DIM else ACCENT
        c.drawText("×$left", r.centerX(), r.top + r.height() * 0.9f, text)
        text.color = Color.WHITE
    }

    private fun drawPickCard(c: Canvas, r: RectF, s: Shroom, now: Long) {
        drawShroom(c, s.species, r.centerX(), r.top + r.height() * 0.62f, r.width() * 0.42f, s.mood(), 0f, false, false)
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
        c.drawText("${lab.discovered.count { it }} of ${Data.SPECIES.size} species discovered", w / 2, topBar + 26f * dp, text)
        text.color = Color.WHITE

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
            text.color = when (sp.tier) { 3 -> DUST; 2 -> Color.rgb(140, 200, 255); else -> TEXT_DIM }
            c.drawText(arrayOf("", "Common", "Hybrid", "Legendary")[sp.tier], pad, top + 52f * dp, text)
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
            text.color = Color.WHITE
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawDexCell(c: Canvas, r: RectF, i: Int) {
        val known = lab.discovered[i]
        val sp = Data.SPECIES[i]
        if (sp.tier >= 2) {
            stroke.color = if (sp.tier == 3) DUST else Color.rgb(140, 200, 255)
            stroke.alpha = 150; stroke.strokeWidth = 1.5f * dp
            c.drawRoundRect(r, 14f * dp, 14f * dp, stroke)
            stroke.alpha = 255
        }
        drawShroom(c, i, r.centerX(), r.top + r.height() * 0.7f, min(r.width() * 0.5f, r.height() * 0.5f), 0,
            0f, false, !known)
        text.textSize = 9.5f * dp
        text.color = if (known) Color.WHITE else TEXT_DIM
        c.drawText(if (known) sp.name.split(" ").first() else "???", r.centerX(), r.bottom - 6f * dp, text)
        text.color = Color.WHITE
    }

    // ----- Shop -----

    private fun drawShop(c: Canvas, w: Float, h: Float) {
        val pad = 16f * dp
        val n = if (shopTab == 0) Data.INGREDIENTS.size else Data.FOODS.size
        val top = topBar + 62f * dp
        val rowH = min(66f * dp, (navTop - top - 8f * dp) / n)
        for (i in 0 until n) {
            val y = top + i * rowH
            fill.color = if (i % 2 == 0) PANEL else Color.rgb(40, 28, 74)
            c.drawRoundRect(pad - 6f * dp, y + 2f * dp, w - pad + 6f * dp, y + rowH - 2f * dp, 14f * dp, 14f * dp, fill)
            val cy = y + rowH / 2
            if (shopTab == 0) drawIngredient(c, i, pad + 20f * dp, cy, 16f * dp) else drawFood(c, i, pad + 20f * dp, cy, 16f * dp)
            text.textAlign = Paint.Align.LEFT
            text.textSize = 15f * dp
            val name = if (shopTab == 0) Data.INGREDIENTS[i].name else Data.FOODS[i].name
            c.drawText(name, pad + 46f * dp, cy - 2f * dp, text)
            text.textSize = 11f * dp; text.color = TEXT_DIM
            val sub = if (shopTab == 0) "You have ${lab.ingredients[i]}"
            else Data.FOODS[i].let { "Have ${lab.foods[i]} · +${it.hunger} food, +${it.xp} growth" }
            c.drawText(sub, pad + 46f * dp, cy + 15f * dp, text)
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
        }
    }

    // ----- Popup / tutorial / toast -----

    private fun drawPopup(c: Canvas, w: Float, h: Float, p: Popup) {
        fill.color = Color.argb(190, 10, 6, 24)
        c.drawRect(0f, 0f, w, h, fill)
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
        text.textSize = 15f * dp
        text.color = if (p.isNew) DUST else MINT
        c.drawText(if (p.isNew) "✦ NEW DISCOVERY! ✦" else if (p.crossed) "A new sprout!" else "It's alive!", w / 2, card.top + 40f * dp, text)
        text.color = Color.WHITE
        drawShroom(c, p.species, w / 2, h * 0.52f, min(w * 0.38f, 150f * dp), 0, sin(t * 2.5f), (t % 3f) < 0.15f, false)
        text.textSize = 26f * dp
        c.drawText(sp.name, w / 2, h * 0.59f, text)
        text.textSize = 13f * dp; text.color = TEXT_DIM
        c.drawText("${arrayOf("", "Common", "Hybrid", "Legendary")[sp.tier]} · Loves ${Data.FOODS[sp.favFood].name}", w / 2, h * 0.59f + 24f * dp, text)
        text.color = Color.WHITE
    }

    private fun drawTutorial(c: Canvas, w: Float, h: Float) {
        if (popup != null) return
        val msg = when {
            lab.tutorial == 0 && tab != Tab.LAB -> "Welcome to Shroomology! Tap LAB to brew your first mushroom"
            lab.tutorial <= 1 && tab == Tab.LAB && labMode == 0 -> { if (lab.tutorial == 0) lab.tutorial = 1; "Pick two ingredients, then tap BREW. Try Water + Soil!" }
            lab.tutorial == 2 && tab == Tab.GARDEN -> "Tap your new mushroom to check on it"
            lab.tutorial == 3 && tab == Tab.GARDEN && selected >= 0 -> "Feed it to help it grow. Grown mushrooms make spore dust ✦ and can crossbreed!"
            else -> return
        }
        // speech bubble near the top of the content area
        text.textSize = 14f * dp
        val maxW = w - 40f * dp
        val lines = wrap(msg, maxW - 28f * dp)
        val bh = 18f * dp + lines.size * 19f * dp
        val y = if (tab == Tab.LAB) h * 0.12f + 56f * dp else topBar + 52f * dp
        val r = RectF(20f * dp, y, w - 20f * dp, y + bh)
        val bob = sin(t * 3f) * 2f * dp
        r.offset(0f, bob)
        fill.color = ACCENT
        c.drawRoundRect(r, 16f * dp, 16f * dp, fill)
        text.color = Color.WHITE
        for ((k, line) in lines.withIndex()) c.drawText(line, w / 2, r.top + 24f * dp + k * 19f * dp, text)
        // arrow down to the LAB tab
        if (lab.tutorial == 0 && tab != Tab.LAB) {
            val ax = w / 8f * 3f
            val ay = navTop - 16f * dp + sin(t * 5f) * 6f * dp
            fill.color = ACCENT
            path.reset(); path.moveTo(ax - 14f * dp, ay - 16f * dp); path.lineTo(ax + 14f * dp, ay - 16f * dp); path.lineTo(ax, ay); path.close()
            c.drawPath(path, fill)
        }
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
        if (pressed) r.inset(3f * dp, 3f * dp)
        val radius = 16f * dp
        when (b.style) {
            STYLE_PRIMARY -> { fill.color = if (b.enabled) ACCENT else Color.rgb(90, 60, 100); text.color = Color.WHITE }
            STYLE_DANGER -> { fill.color = Color.rgb(90, 30, 50); text.color = Color.rgb(255, 160, 170) }
            STYLE_TAB_ON -> { fill.color = Color.WHITE; text.color = BG }
            STYLE_PICKED -> { fill.color = PANEL_LIGHT; text.color = Color.WHITE }
            STYLE_CARD -> { fill.color = Color.TRANSPARENT; text.color = Color.WHITE }
            else -> { fill.color = PANEL; text.color = Color.WHITE }
        }
        val isNav = b.id.startsWith("nav") || b.id == "sound"
        if (b.style == STYLE_CARD && !isNav) fill.color = if (b.enabled) PANEL else Color.rgb(36, 26, 64)
        if (fill.color != Color.TRANSPARENT) c.drawRoundRect(r, radius, radius, fill)
        if (b.style == STYLE_PICKED) {
            stroke.color = MINT; stroke.strokeWidth = 2.5f * dp
            c.drawRoundRect(r, radius, radius, stroke)
        }
        if (pressed && isNav) {
            fill.color = Color.argb(30, 255, 255, 255); c.drawRoundRect(r, radius, radius, fill)
        }
        val custom = b.custom
        if (custom != null) {
            val tc = text.color
            text.color = Color.WHITE
            custom(c, r)
            text.color = tc
        } else {
            text.textSize = if (b.label.length > 12) 14f * dp else 17f * dp
            if (!b.enabled) text.alpha = 140
            val ty = r.centerY() - (text.descent() + text.ascent()) / 2
            c.drawText(b.label, r.centerX(), ty, text)
            text.alpha = 255
        }
        text.color = Color.WHITE
    }

    private fun drawNavItem(c: Canvas, r: RectF, tb: Tab) {
        val on = tab == tb
        val cx = r.centerX(); val cy = r.top + 30f * dp
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
            Tab.SHOP -> {
                c.drawRoundRect(cx - 11f * dp, cy - 5f * dp, cx + 11f * dp, cy + 11f * dp, 4f * dp, 4f * dp, stroke)
                c.drawArc(cx - 6f * dp, cy - 13f * dp, cx + 6f * dp, cy - 1f * dp, 180f, 180f, false, stroke)
            }
        }
        text.textSize = 11f * dp
        text.color = col
        c.drawText(when (tb) { Tab.GARDEN -> "GARDEN"; Tab.LAB -> "LAB"; Tab.DEX -> "SHROOMDEX"; Tab.SHOP -> "SHOP" },
            cx, r.top + 60f * dp, text)
        text.color = Color.WHITE
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

    /**
     * Draws a cute mushroom standing at (cx, baseY).
     * mood: 0 happy, 1 okay, 2 hungry, 3 sad. bob: -1..1 squash-and-stretch.
     */
    private fun drawShroom(c: Canvas, species: Int, cx: Float, baseY: Float, size: Float, mood: Int,
                           bob: Float, blink: Boolean, silhouette: Boolean) {
        val sp = Data.SPECIES[species]
        val s = size
        val dark = Color.rgb(44, 34, 78)
        fun col(v: Int) = if (silhouette) dark else v

        // shadow
        fill.color = Color.argb(60, 0, 0, 0)
        c.drawOval(cx - s * 0.45f, baseY - s * 0.06f, cx + s * 0.45f, baseY + s * 0.08f, fill)

        c.save()
        c.scale(1f - 0.03f * bob, 1f + 0.04f * bob, cx, baseY)

        if (sp.glow && !silhouette) {
            fill.color = sp.cap
            fill.alpha = (50 + 25 * sin(t * 3f)).toInt()
            c.drawCircle(cx, baseY - s * 0.7f, s * 0.85f, fill)
            fill.alpha = 255
        }

        val stemH = when (sp.shape) { 1 -> s * 0.16f; 4 -> s * 0.36f; else -> s * 0.5f }
        val stemW = s * 0.42f
        val capBottom = baseY - stemH + s * 0.08f
        var capTop: Float

        // stem
        fill.color = col(sp.stem)
        c.drawRoundRect(cx - stemW / 2, baseY - stemH - s * 0.04f, cx + stemW / 2, baseY, stemW * 0.4f, stemW * 0.4f, fill)

        // cap
        fill.color = col(sp.cap)
        path.reset()
        when (sp.shape) {
            1 -> { // puffball
                val r = s * 0.44f
                capTop = baseY - s * 0.86f
                c.drawCircle(cx, baseY - r - s * 0.02f, r, fill)
                if (sp.spots != 0 && !silhouette) {
                    fill.color = sp.spots
                    for ((px, py) in arrayOf(-0.4f to -0.55f, 0.25f to -0.7f, 0.42f to -0.3f)) {
                        c.drawCircle(cx + px * r, baseY - r + py * r, r * 0.13f, fill)
                    }
                }
            }
            2 -> { // morel cone
                val cw = s * 0.8f; val ch = s * 0.95f
                capTop = capBottom - ch
                path.moveTo(cx - cw / 2, capBottom)
                path.quadTo(cx - cw * 0.58f, capBottom - ch * 0.7f, cx, capBottom - ch)
                path.quadTo(cx + cw * 0.58f, capBottom - ch * 0.7f, cx + cw / 2, capBottom)
                path.quadTo(cx, capBottom + ch * 0.08f, cx - cw / 2, capBottom)
                path.close()
                c.drawPath(path, fill)
                if (sp.spots != 0 && !silhouette) {
                    fill.color = sp.spots
                    for (row in 0 until 4) for (k in -1..1) {
                        val yy = capBottom - ch * (0.16f + row * 0.19f)
                        val spread = cw * 0.36f * (1f - row * 0.2f)
                        c.drawOval(cx + k * spread * 0.8f - s * 0.05f + (row % 2) * s * 0.04f, yy - s * 0.035f,
                            cx + k * spread * 0.8f + s * 0.05f + (row % 2) * s * 0.04f, yy + s * 0.035f, fill)
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
                c.drawPath(path, fill)
                if (!silhouette) {
                    fill.color = if (sp.spots != 0) sp.spots else Color.argb(60, 255, 255, 255)
                    c.drawOval(cx - s * 0.56f, capTop - s * 0.04f, cx + s * 0.56f, capTop + s * 0.12f, fill)
                }
            }
            4 -> { // tall horn
                capTop = capBottom - s * 0.9f
                c.drawRoundRect(cx - s * 0.25f, capTop, cx + s * 0.25f, capBottom + s * 0.03f, s * 0.25f, s * 0.25f, fill)
                if (sp.spots != 0 && !silhouette) {
                    stroke.color = sp.spots; stroke.strokeWidth = s * 0.04f
                    for (k in 1..3) c.drawLine(cx - s * 0.18f, capTop + k * s * 0.2f, cx + s * 0.18f, capTop + k * s * 0.2f + s * 0.03f, stroke)
                }
            }
            else -> { // dome
                val cw = s * 1.1f; val ch = s * 0.6f
                capTop = capBottom - ch
                path.moveTo(cx - cw / 2, capBottom)
                path.cubicTo(cx - cw / 2, capBottom - ch * 1.3f, cx + cw / 2, capBottom - ch * 1.3f, cx + cw / 2, capBottom)
                path.quadTo(cx, capBottom + ch * 0.2f, cx - cw / 2, capBottom)
                path.close()
                c.drawPath(path, fill)
                if (sp.spots != 0 && !silhouette) {
                    fill.color = sp.spots
                    for ((px, py, pr) in arrayOf(Triple(-0.26f, -0.5f, 0.08f), Triple(0.12f, -0.78f, 0.07f),
                            Triple(0.32f, -0.36f, 0.06f), Triple(-0.06f, -0.3f, 0.05f))) {
                        c.drawCircle(cx + px * cw, capBottom + py * ch, s * pr, fill)
                    }
                }
                // shine
                if (!silhouette) {
                    fill.color = Color.argb(70, 255, 255, 255)
                    c.drawOval(cx - cw * 0.34f, capBottom - ch * 0.86f, cx - cw * 0.12f, capBottom - ch * 0.62f, fill)
                }
            }
        }

        if (sp.crown && !silhouette) {
            fill.color = Color.rgb(255, 210, 70)
            val cy = capTop + s * 0.02f
            val cw = s * 0.32f
            path.reset()
            path.moveTo(cx - cw / 2, cy)
            path.lineTo(cx - cw / 2, cy - s * 0.16f); path.lineTo(cx - cw / 4, cy - s * 0.07f)
            path.lineTo(cx, cy - s * 0.2f); path.lineTo(cx + cw / 4, cy - s * 0.07f)
            path.lineTo(cx + cw / 2, cy - s * 0.16f); path.lineTo(cx + cw / 2, cy); path.close()
            c.drawPath(path, fill)
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

        // face
        if (!silhouette) {
            val faceY = if (sp.shape == 1) baseY - s * 0.36f else baseY - stemH * 0.5f
            val ex = s * 0.11f
            val er = s * 0.045f
            fill.color = Color.rgb(50, 30, 60)
            stroke.color = Color.rgb(50, 30, 60); stroke.strokeWidth = max(1.5f * dp, s * 0.025f)
            if (blink) {
                c.drawLine(cx - ex - er, faceY, cx - ex + er, faceY, stroke)
                c.drawLine(cx + ex - er, faceY, cx + ex + er, faceY, stroke)
            } else {
                c.drawCircle(cx - ex, faceY, er, fill)
                c.drawCircle(cx + ex, faceY, er, fill)
                fill.color = Color.WHITE
                c.drawCircle(cx - ex + er * 0.35f, faceY - er * 0.35f, er * 0.35f, fill)
                c.drawCircle(cx + ex + er * 0.35f, faceY - er * 0.35f, er * 0.35f, fill)
            }
            // blush
            fill.color = Color.argb(140, 255, 130, 160)
            c.drawCircle(cx - ex * 1.75f, faceY + s * 0.055f, s * 0.04f, fill)
            c.drawCircle(cx + ex * 1.75f, faceY + s * 0.055f, s * 0.04f, fill)
            // mouth
            val my = faceY + s * 0.06f
            val mw = s * 0.06f
            when (mood) {
                0 -> c.drawArc(cx - mw, my - mw * 0.8f, cx + mw, my + mw * 0.8f, 20f, 140f, false, stroke)
                2 -> { fill.color = Color.rgb(50, 30, 60); c.drawCircle(cx, my + mw * 0.3f, mw * 0.45f, fill) }
                3 -> c.drawArc(cx - mw, my + mw * 0.2f, cx + mw, my + mw * 1.6f, 200f, 140f, false, stroke)
                else -> c.drawLine(cx - mw * 0.6f, my + mw * 0.3f, cx + mw * 0.6f, my + mw * 0.3f, stroke)
            }
        }
        c.restore()
    }

    // ---------- Art: icons ----------

    private fun drawIngredient(c: Canvas, i: Int, x: Float, y: Float, r: Float) {
        val col = Data.INGREDIENTS[i].color
        fill.color = col; fill.alpha = 45
        c.drawCircle(x, y, r * 1.35f, fill)
        fill.alpha = 255
        stroke.color = col; stroke.strokeWidth = r * 0.16f
        path.reset()
        when (i) {
            Data.WATER -> {
                path.moveTo(x, y - r); path.quadTo(x + r * 0.9f, y + r * 0.1f, x, y + r * 0.8f)
                path.quadTo(x - r * 0.9f, y + r * 0.1f, x, y - r); path.close(); c.drawPath(path, fill)
            }
            Data.SOIL -> {
                c.drawArc(x - r, y - r * 0.6f, x + r, y + r * 1.0f, 180f, 180f, true, fill)
                fill.color = Color.rgb(100, 65, 40)
                c.drawCircle(x - r * 0.35f, y, r * 0.12f, fill); c.drawCircle(x + r * 0.3f, y - r * 0.15f, r * 0.1f, fill)
            }
            Data.EMBER -> {
                path.moveTo(x, y - r); path.quadTo(x + r * 0.9f, y, x + r * 0.5f, y + r * 0.7f)
                path.quadTo(x, y + r * 1.0f, x - r * 0.5f, y + r * 0.7f); path.quadTo(x - r * 0.9f, y, x, y - r)
                path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(255, 220, 90); c.drawCircle(x, y + r * 0.35f, r * 0.3f, fill)
            }
            Data.FROST -> for (k in 0 until 3) {
                val a = k * Math.PI / 3
                val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
                c.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
            }
            Data.IRON -> {
                for (k in 0 until 6) {
                    val a = k * Math.PI / 3
                    val px = x + (cos(a) * r).toFloat(); val py = y + (sin(a) * r).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, fill)
                fill.color = Color.rgb(200, 205, 215); c.drawCircle(x, y, r * 0.35f, fill)
            }
            Data.SULFUR -> {
                c.drawCircle(x - r * 0.3f, y + r * 0.2f, r * 0.5f, fill)
                c.drawCircle(x + r * 0.35f, y + r * 0.1f, r * 0.45f, fill)
                c.drawCircle(x, y - r * 0.35f, r * 0.42f, fill)
            }
            Data.CRYSTAL -> {
                path.moveTo(x, y - r); path.lineTo(x + r * 0.65f, y - r * 0.2f); path.lineTo(x, y + r)
                path.lineTo(x - r * 0.65f, y - r * 0.2f); path.close(); c.drawPath(path, fill)
                fill.color = Color.argb(120, 255, 255, 255)
                path.reset(); path.moveTo(x, y - r); path.lineTo(x + r * 0.65f, y - r * 0.2f); path.lineTo(x, y - r * 0.2f); path.close()
                c.drawPath(path, fill)
            }
            Data.MOONDEW -> {
                c.drawCircle(x, y, r * 0.85f, fill)
                fill.color = PANEL
                c.drawCircle(x + r * 0.4f, y - r * 0.3f, r * 0.7f, fill)
            }
            else -> { // gold nugget star
                for (k in 0 until 10) {
                    val a = -Math.PI / 2 + k * Math.PI / 5
                    val rr = if (k % 2 == 0) r else r * 0.45f
                    val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, fill)
            }
        }
    }

    private fun drawFood(c: Canvas, f: Int, x: Float, y: Float, r: Float) {
        val col = Data.FOODS[f].color
        fill.color = col; fill.alpha = 45
        c.drawCircle(x, y, r * 1.35f, fill)
        fill.alpha = 255
        path.reset()
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
