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
        g.testTap("s7"); g.testFrame(0.2f); save(g.testRender(), "shop_event")
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
