package com.razedragon.tinylab

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Runs the real game on the build machine (no emulator) and saves screenshots to app/build/qa-shots,
 * plus checks the brewing rules. Sized like a Galaxy Note20 (1080x2400, 420 dpi).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private val out = File("build/qa-shots").apply { mkdirs() }
    private val W = 1080
    private val H = 2400

    private fun launch(event: Int, hold: Boolean = false): GameView {
        Data.forceEvent = event
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra("qa", true).putExtra("event", event).putExtra("holdSplash", hold)
        val act = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val game = act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as GameView
        game.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        game.layout(0, 0, W, H)
        return game
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ready(event: Int): GameView {
        val g = launch(event)
        g.testSkipIntro()
        g.testFrame(0.5f)
        return g
    }

    // ---------------------------------------------------------------- screenshots

    @Test fun splashScreens() {
        for ((ev, nm) in listOf(0 to "normal", 1 to "spooky", 2 to "frosty", 3 to "spring")) {
            val g = launch(ev, hold = true)
            g.testFrame(2.2f)
            save(g.testRender(), "splash_$nm")
        }
    }

    @Test fun labPerEvent() {
        for ((ev, nm) in listOf(0 to "normal", 1 to "spooky", 2 to "frosty", 3 to "spring")) {
            val g = ready(ev)
            assertTrue(g.testTap("nav1"))
            g.testProfSay("Mushrooms adore their favorite food ❤")
            g.testFrame(0.3f)
            save(g.testRender(), "lab_$nm")
        }
    }

    @Test fun chanceMeterAndFizzle() {
        val g = ready(1)
        val lab = g.testLab()
        lab.upgrades[Data.UP_FLASK] = 1
        for (i in Data.INGREDIENTS.indices) lab.ingredients[i] = 5
        g.testTap("nav1")
        g.testFrame(0.2f)
        assertTrue(g.testTap("ing${Data.WATER}")); assertTrue(g.testTap("ing${Data.EMBER}")); assertTrue(g.testTap("ing${Data.IRON}"))
        g.testFrame(0.2f)
        save(g.testRender(), "lab_chance_meter")
        g.testForceFail = true
        assertTrue(g.testTap("brew"))
        g.testFrame(2.0f)
        save(g.testRender(), "lab_brewing_meter")
        g.testFrame(4.0f)
        g.testFrame(1.6f)
        save(g.testRender(), "popup_fizzle")
        // a fizzled Storm Cap (water + ember + iron) must give what those ingredients make, not something random
        val grown = lab.garden.last().species
        assertEquals(Data.brew3Common(Data.WATER, Data.EMBER, Data.IRON), grown)
    }

    @Test fun shroomdexAndShop() {
        val g = ready(1)
        g.testTap("nav2"); g.testFrame(0.2f); save(g.testRender(), "dex_common")
        g.testTap("dt1"); g.testFrame(0.2f); save(g.testRender(), "dex_rare_bred")
        g.testTap("dt2"); g.testFrame(0.2f); g.testTap("dex58"); g.testFrame(0.2f); save(g.testRender(), "dex_event")
        g.testTap("nav4"); g.testFrame(0.2f); save(g.testRender(), "shop_ingredients")
        g.testTap("s8"); g.testFrame(0.2f); save(g.testRender(), "shop_event")
        g.testTap("s7"); g.testFrame(0.2f); save(g.testRender(), "shop_packs")
        g.testTap("s6"); g.testFrame(0.2f); save(g.testRender(), "shop_pets")
    }

    @Test fun everySpeciesAndIngredient() {
        val g = ready(0)
        val dp = g.resources.displayMetrics.density
        val cols = 5
        val cw = W / cols
        val ch = (82 * dp).toInt()
        val rows = (Data.SPECIES.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(W, rows * ch + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(40, 28, 72))
        for (i in Data.SPECIES.indices) {
            val x = (i % cols) * cw + cw / 2f
            val y = (i / cols) * ch + ch - 22 * dp
            g.testDrawSpecies(c, i, x, y, 48 * dp)
        }
        save(bmp, "all_species")

        val ib = Bitmap.createBitmap(W, 3 * (90 * dp).toInt(), Bitmap.Config.ARGB_8888)
        val ic = Canvas(ib)
        ic.drawColor(Color.rgb(40, 28, 72))
        for (i in Data.INGREDIENTS.indices) {
            g.testDrawIngredient(ic, i, (i % 6) * (W / 6f) + W / 12f, (i / 6) * 90 * dp + 36 * dp, 16 * dp)
        }
        save(ib, "all_ingredients")
    }

    /** A lived-in garden: a family, best friends, pets, a band, bedtime, and the cave. */
    @Test fun newGardenFeatures() {
        val g = ready(1)
        val lab = g.testLab()
        lab.garden.clear()
        for (p in Data.PETS.indices) lab.petOwned[p] = 1
        val mom = lab.newShroom(1, 200f, false).apply { name = "Mossy"; gx = 0.3f; gz = 0.4f; family[0] = "Pip|3"; family[1] = "Dot|5" }
        val dad = lab.newShroom(2, 200f, false).apply { name = "Fizz"; gx = 0.7f; gz = 0.45f; family[0] = "Bean|7" }
        lab.garden.add(mom); lab.garden.add(dad)
        val kid = lab.newShroom(13, 60f, false, mom, dad).apply { gx = 0.5f; gz = 0.7f; pet = 0 }
        lab.garden.add(kid)
        mom.pet = 2; dad.pet = 4
        mom.bestie = dad.id; dad.bestie = mom.id
        val glow1 = lab.newShroom(6, 200f, false).apply { area = 1; gx = 0.35f; gz = 0.5f; pet = 5 }
        val glow2 = lab.newShroom(31, 200f, false).apply { area = 1; gx = 0.65f; gz = 0.6f; pet = 6 }
        lab.garden.add(glow1); lab.garden.add(glow2)
        lab.upgrades[Data.UP_CAVE] = 1
        g.testFrame(1.0f)
        save(g.testRender(), "garden_friends_pets")

        g.testSelect(kid.id); g.testFrame(0.4f)
        assertTrue(g.testTap("more")); g.testFrame(0.2f); save(g.testRender(), "panel_more")
        assertTrue(g.testTap("family")); g.testFrame(0.3f); save(g.testRender(), "family_tree")
        assertTrue(g.testTap("fam_close"))
        g.testSelect(mom.id); g.testFrame(0.3f)
        assertTrue(g.testTap("style")); g.testFrame(0.2f); save(g.testRender(), "panel_style_pets")
        g.testTap("sdone")
        assertTrue(g.testTap("more")); assertTrue(g.testTap("job")); g.testFrame(0.2f); save(g.testRender(), "panel_jobs")
        assertTrue(g.testTap("job1"))
        assertEquals(Data.JOB_STIR, mom.job)
        g.testTap("jcancel"); g.testSelect(-1)
        g.testFrame(0.3f)

        for (sh in lab.garden) { sh.act = 0; sh.sleeping = false; sh.awakeUntil = Long.MAX_VALUE }
        lab.tuckedUntil = 0L
        g.testFrame(0.1f)
        assertTrue(g.testTap("jam"))
        g.testFrame(8f)
        save(g.testRender(), "garden_jam")
        g.testFrame(12f)

        g.testBedtime(); g.testFrame(1f)
        save(g.testRender(), "garden_bedtime")

        assertTrue(g.testTap("area")); g.testFrame(1.5f)
        save(g.testRender(), "cave")

        save(g.testUpdateWidgetFrame(mom), "widget_frame")
    }

    @Test fun journalAndHideAndSeek() {
        val g = ready(1)
        val lab = g.testLab()
        for ((a, b) in listOf(0 to 1, 2 to 5, 3 to 0, 9 to 10, 11 to 4, 6 to 7, 1 to 1)) lab.noteBrew(a, b, Data.brew(a, b))
        lab.fizzles[34] = 2; lab.fizzles[35] = 1
        lab.garden.add(lab.newShroom(1, 100f, false)); lab.garden.add(lab.newShroom(2, 100f, false))
        g.testTap("nav3"); g.testFrame(0.3f); save(g.testRender(), "journal_today_quest")
        g.testTap("j5"); g.testFrame(0.3f); save(g.testRender(), "journal_notes")
        g.testTap("j4"); g.testFrame(0.3f); save(g.testRender(), "journal_games")
        assertTrue(g.testTap("play2")); g.testFrame(1.5f)
        save(g.testRender(), "hide_and_seek")
        for (i in 0..4) g.testTap("hs$i")
        g.testFrame(0.3f); save(g.testRender(), "hide_and_seek_peek")
    }

    @Test fun settingsWithBackup() {
        val g = ready(0)
        assertTrue(g.testTap("gear")); g.testFrame(0.3f)
        save(g.testRender(), "settings")
        val lab = g.testLab()
        lab.garden.add(lab.newShroom(4, 50f, false).apply { name = "Backup" })
        val code = lab.exportCode()
        assertTrue(code.startsWith("SHROOM1:"))
        val other = LabState(RuntimeEnvironment.getApplication())
        assertTrue(other.importCode(code))
        val back = LabState(RuntimeEnvironment.getApplication())
        assertTrue(back.garden.any { it.name == "Backup" })
        assertTrue(!other.importCode("nope"))
    }

    private fun reset() { Data.forceSeason = -1; LabState.forceRain = false; Data.forceMerchant = false }

    @Test fun seasonsWeatherDreams() {
        val names = arrayOf("spring", "summer", "autumn", "winter")
        for (season in 0..3) {
            reset(); Data.forceSeason = season
            val g = ready(0)
            val lab = g.testLab(); lab.garden.clear()
            val a = lab.newShroom(1, 200f, false).apply { gx = 0.3f; gz = 0.5f }
            val b = lab.newShroom(30, 200f, false).apply { gx = 0.6f; gz = 0.6f }
            lab.garden.add(a); lab.garden.add(b)
            if (season == 3) g.testSnowman(0.7f, 0.4f)
            g.testThink(a)
            g.testFrame(1.5f)
            save(g.testRender(), "season_${names[season]}")
        }
        reset(); LabState.forceRain = true
        val g = ready(0)
        val lab = g.testLab(); lab.garden.clear()
        for (k in 0..2) lab.garden.add(lab.newShroom(listOf(1, 3, 42)[k], 200f, false).apply { gx = 0.2f + k * 0.3f; gz = 0.5f })
        g.testFrame(1f); save(g.testRender(), "rain_puddles")
        LabState.forceRain = false
        g.testBedtime(); g.testFrame(5f); save(g.testRender(), "dreams")
        reset()
    }

    @Test fun growCookAndItems() {
        reset()
        val g = ready(0)
        val lab = g.testLab()
        for (i in lab.seeds.indices) lab.seeds[i] = 2
        lab.plant(0, 0); lab.plant(1, 3); lab.plant(2, 5); lab.water(1)
        lab.plotAt[0] -= 3 * 3_600_000L
        for (i in lab.ingredients.indices) lab.ingredients[i] = 3
        g.testTap("nav0"); g.testCam(1.5f); g.testFrame(0.5f); save(g.testRender(), "garden_patch")
        assertTrue(g.testTap("plot0"))
        assertTrue(lab.harvested == 1)
        assertTrue(g.testTap("plot3")); g.testFrame(0.4f); save(g.testRender(), "garden_patch_seeds")
        g.testTap("seed_cancel")
        g.testTap("nav1")
        assertTrue(g.testTap("m3")); g.testFrame(0.2f); save(g.testRender(), "lab_cook")
        assertTrue(g.testTap("cook0"))
        assertEquals(1, lab.foods[8])
        // new items in the garden
        lab.garden.clear()
        lab.placed.clear()
        lab.placed.add(Placed(Data.COZY_BED, 0.25f, 0.4f)); lab.placed.add(Placed(Data.STAR_FOUNTAIN, 0.72f, 0.35f))
        val s = lab.newShroom(2, 200f, false).apply { gx = 0.5f; gz = 0.7f; wear[0] = Data.ACORN_CAP; wear[1] = Data.MONOCLE; pet = Data.MINI_DRAGON }
        val s2 = lab.newShroom(6, 200f, false).apply { gx = 0.3f; gz = 0.75f; wear[0] = Data.SPROUT_CROWN }
        lab.garden.add(s); lab.garden.add(s2)
        for (f in 8..12) lab.foods[f] = 1
        g.testTap("nav0"); g.testCam(0.5f); g.testFrame(1f); save(g.testRender(), "garden_new_items")
        g.testSelect(s.id); g.testFrame(0.3f); assertTrue(g.testTap("feed")); g.testFrame(0.2f); save(g.testRender(), "panel_feed")
    }

    @Test fun widerGardenAndOffers() {
        reset()
        val g = ready(0)
        val lab = g.testLab(); lab.garden.clear()
        for (k in 0 until 8) lab.garden.add(lab.newShroom(listOf(1, 2, 3, 6, 13, 30, 42, 45)[k], 200f, false).apply { gx = 0.15f + k * 0.23f; gz = 0.2f + (k % 3) * 0.3f })
        g.testCam(0.5f); g.testFrame(2f); save(g.testRender(), "garden_left")
        g.testCam(1.5f); g.testFrame(0.5f); save(g.testRender(), "garden_right")
        assertTrue(g.testButtonIds().contains("pan_l"))
        g.testOffer(Data.PACKS.indexOfFirst { it.id == "starter" }); g.testFrame(0.3f); save(g.testRender(), "offer_popup")
        assertTrue(g.testTap("offer_peek")); g.testFrame(0.5f); save(g.testRender(), "preview_starter_pack")
        assertTrue(g.testTap("pv_close"))
        assertTrue(g.testTap("offer_close"))
        g.testOfferSide(Data.PACKS.indexOfFirst { it.id == "spores_medium" }); g.testFrame(0.3f)
        assertTrue(g.testButtonIds().any { it.startsWith("offerside") })
        save(g.testRender(), "garden_offer_buttons")
        // previews from the shop
        lab.dust = 5000
        g.testTap("nav4")
        g.testTap("s3"); assertTrue(g.testTap("prev_3_2")); g.testFrame(0.5f); save(g.testRender(), "preview_theme")
        g.testTap("pv_close")
        g.testTap("s2"); assertTrue(g.testTap("prev_2_6")); g.testFrame(0.5f); save(g.testRender(), "preview_decor")
        g.testTap("pv_close")
        g.testTap("s4"); assertTrue(g.testTap("prev_4_6")); g.testFrame(0.5f); save(g.testRender(), "preview_hat")
        assertTrue(g.testTap("pv_buy"))
        assertEquals(1, lab.accOwned[6])
        g.testTap("s6"); assertTrue(g.testTap("prev_6_3")); g.testFrame(0.5f); save(g.testRender(), "preview_pet")
        g.testTap("pv_close")
        reset()
    }

    /** Every busy screen, to check nothing overlaps or gets cut off. */
    @Test fun uiAudit() {
        reset(); LabState.forceRain = true
        val g = ready(1)
        val lab = g.testLab()
        lab.dust = 739
        lab.garden.clear()
        for (k in 0 until 5) lab.garden.add(lab.newShroom(listOf(1, 59, 30, 42, 6)[k], 200f, false).apply { gx = 0.2f + k * 0.15f; gz = 0.3f + (k % 2) * 0.3f })
        g.testFrame(1f); save(g.testRender(), "audit_rain")
        g.testTap("nav4")
        for (k in 0..8) {
            g.testTap("s$k"); g.testFrame(0.2f); save(g.testRender(), "audit_shop_$k")
        }
        g.testTap("s2"); g.testShopScroll(10000f); g.testFrame(0.2f); save(g.testRender(), "audit_shop_2_bottom")
        g.testTap("nav3")
        for (j in 0..5) { g.testTap("j$j"); g.testFrame(0.2f); save(g.testRender(), "audit_journal_$j") }
        g.testTap("j4"); g.testTap("play1"); g.testFrame(0.3f); g.testEndMini(); g.testFrame(0.3f); save(g.testRender(), "audit_mini_end")
        assertTrue(g.testTap("mg_done"))
        g.testTap("nav2"); g.testTap("dex1"); g.testFrame(0.2f); save(g.testRender(), "audit_dex_detail")
        g.testTap("nav0"); g.testSelect(lab.garden[0].id); g.testFrame(0.4f); save(g.testRender(), "audit_info")
        g.testTap("explore"); g.testFrame(0.2f); save(g.testRender(), "audit_explore")
        reset()
    }

    @Test fun tutorialGuidesWelcomeBack() {
        reset()
        // first play: the feed step spotlights the real buttons
        val g = launch(0)
        g.testFrame(3.2f)
        val lab = g.testLab()
        val s = lab.newShroom(1, 10f, false).apply { gx = 0.5f; gz = 0.5f }
        lab.garden.add(s); lab.brewed = 1
        g.testSelect(s.id)
        g.testTutorial(6)
        g.testFrame(0.4f); save(g.testRender(), "tut_feed_button")
        assertTrue(g.testTap("feed")); g.testFrame(0.4f); save(g.testRender(), "tut_feed_food")
        // tab guides
        val g2 = ready(0)
        for (tb in listOf("GARDEN", "LAB", "DEX", "JOURNAL", "SHOP")) { g2.testHelp(tb); g2.testFrame(0.2f); save(g2.testRender(), "help_" + tb.lowercase()); assertTrue(g2.testTap("help_ok")) }
        // welcome back after time away: capped at 250
        val l2 = g2.testLab()
        for (k in 0 until 6) l2.garden.add(l2.newShroom(listOf(1, 2, 3, 6, 13, 30)[k], 200f, false))
        l2.tick(System.currentTimeMillis() + 5 * 3_600_000L)
        assertEquals(LabState.OFFLINE_CAP, l2.pendingOffline)
        g2.testFrame(0.3f); save(g2.testRender(), "welcome_back")
        val before = l2.dust
        assertTrue(g2.testTap("wb_claim"))
        assertEquals(before + LabState.OFFLINE_CAP, l2.dust)
        reset()
    }

    @Test fun merchantStickersCodes() {
        reset(); Data.forceMerchant = true
        val g = ready(0)
        val lab = g.testLab()
        lab.dust = 2000
        g.testFrame(0.5f); save(g.testRender(), "garden_merchant")
        assertTrue(g.testTap("merchant")); g.testFrame(0.3f); save(g.testRender(), "merchant")
        assertTrue(g.testTap("offer0"))
        assertEquals(1, lab.merchantBuys)
        g.testTap("m_close")
        // stickers
        lab.brewed = 1; lab.usedBedtime = true; lab.jammed = true; lab.crosses = 1
        assertTrue(lab.checkStickers().size >= 4)
        g.testTap("nav3"); g.testTap("j2"); g.testFrame(0.3f); save(g.testRender(), "journal_awards")
        assertTrue(g.testTap("st_open")); g.testFrame(0.3f); save(g.testRender(), "sticker_book")
        // codes
        val before = lab.dust
        assertTrue(lab.redeem(" welcome ").first)
        assertEquals(before + 500, lab.dust)
        assertEquals(1, lab.decorOwned[Data.COZY_BED])
        assertTrue(lab.accOwned[2] >= 1)
        assertTrue(!lab.redeem("WELCOME").first)
        assertTrue(!lab.redeem("NOTACODE").first)
        // packs grant their goodies
        val starter = Data.PACKS.first { it.id == "starter" }
        lab.grantPack(starter)
        assertTrue(lab.packOwned(starter))
        assertEquals(1, lab.decorOwned[Data.STAR_FOUNTAIN])
        reset()
    }

    // ---------------------------------------------------------------- rules

    @Test fun everyPairGrowsSomethingThatFits() {
        Data.forceEvent = 0
        val base = Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event == 0 }
        for (a in base) for (b in base) {
            val sp = Data.brew(a, b)
            assertTrue("$a+$b", sp in Data.SPECIES.indices)
            assertEquals("year-round ingredients never make event shrooms ($a+$b)", 0, Data.SPECIES[sp].event)
        }
        for (e in Data.INGREDIENTS.indices.filter { Data.INGREDIENTS[it].event > 0 }) for (b in Data.INGREDIENTS.indices) {
            val ev = Data.INGREDIENTS[e].event
            if (Data.INGREDIENTS[b].event != 0 && Data.INGREDIENTS[b].event != ev) continue
            assertEquals("${Data.INGREDIENTS[e].name} + ${Data.INGREDIENTS[b].name}", ev, Data.SPECIES[Data.brew(e, b)].event)
        }
    }

    @Test fun eventHybridsOnlyDuringTheirEvent() {
        Data.forceEvent = 1
        assertEquals(63, Data.cross(58, 59))
        Data.forceEvent = 0
        assertEquals(-1, Data.cross(58, 59))
        assertTrue(Data.offSeason(58))
        Data.forceEvent = 2
        assertEquals(69, Data.cross(64, 65))
    }

    @Test fun eventIngredientsFadeAfterTheEvent() {
        Data.forceEvent = 1
        val lab = LabState(RuntimeEnvironment.getApplication())
        lab.ingredients[Data.PUMPKIN] = 4
        lab.ingredients[Data.WATER] = 4
        lab.expireEventIngredients()
        assertEquals(4, lab.ingredients[Data.PUMPKIN])
        Data.forceEvent = 0
        lab.expireEventIngredients()
        assertEquals(0, lab.ingredients[Data.PUMPKIN])
        assertEquals(4, lab.ingredients[Data.WATER])
    }

    @Test fun threeIngredientFallbackIsNotRandom() {
        Data.forceEvent = 0
        for (a in 0..11) for (b in 0..11) for (c in 0..11) {
            val first = Data.brew3Common(a, b, c)
            repeat(3) { assertEquals(first, Data.brew3Common(a, b, c)) }
            assertTrue(first in listOf(Data.brew(a, b), Data.brew(a, c), Data.brew(b, c)))
        }
    }
}
