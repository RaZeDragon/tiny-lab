package com.razedragon.tinylab

import android.graphics.Color

/** Things you brew with. `icon` picks how the little picture is drawn. */
class Ingredient(val name: String, val color: Int, val cost: Int, val event: Int = 0)

/** Garden decorations you can buy and place. */
class Decor(val name: String, val cost: Int, val glow: Boolean = false, val event: Int = 0)

/** Garden looks. */
class Theme(val name: String, val cost: Int, val skyTop: Int, val skyBottom: Int, val groundFar: Int,
            val groundNear: Int, val forest1: Int, val forest2: Int, val event: Int = 0)

/** Wearable: slot 0 = on the cap, 1 = on the face, 2 = around the stem. */
class Accessory(val name: String, val cost: Int, val slot: Int, val color: Int, val event: Int = 0)

/** A lab upgrade with one or more levels. */
class Upgrade(val name: String, val desc: String, val costs: IntArray)

/** An expedition destination. */
class Zone(val name: String, val hours: Float, val loot: IntArray, val color: Int, val minStage: Int)

class Achievement(val name: String, val desc: String, val reward: Int)

/** Things you feed your mushrooms. */
class Food(val name: String, val color: Int, val cost: Int, val hunger: Int, val xp: Int, val happy: Int, val event: Int = 0)

/**
 * A mushroom species.
 * shape: 0 = dome cap, 1 = puffball, 2 = morel cone, 3 = funnel (chanterelle), 4 = tall horn
 */
class Species(
    val name: String,
    val cap: Int,
    val stem: Int,
    val spots: Int,          // 0 = no spots
    val shape: Int,
    val tier: Int,           // 1 = brewed, 2 = hybrid, 3 = legendary
    val favFood: Int,
    val hint: String,
    val glow: Boolean = false,
    val crown: Boolean = false,
    val event: Int = 0,      // only obtainable during this seasonal event (0 = always)
    val deco: Int = 0,       // extra decoration drawn on the cap (see Data.D_*)
)

object Data {
    // ---------- Ingredients ----------
    const val WATER = 0
    const val SOIL = 1
    const val EMBER = 2
    const val FROST = 3
    const val IRON = 4
    const val SULFUR = 5
    const val CRYSTAL = 6
    const val MOONDEW = 7
    const val GOLD = 8
    const val PETAL = 9
    const val HONEY = 10
    const val SPARK = 11
    // Event-only ingredients: sold during their event and fade away when it ends
    const val PUMPKIN = 12
    const val ECTO = 13
    const val PEPPERMINT = 14
    const val GINGER = 15
    const val CLOVER = 16
    const val RAINBOW = 17

    val INGREDIENTS = arrayOf(
        Ingredient("Water", Color.rgb(90, 170, 255), 3),
        Ingredient("Soil", Color.rgb(150, 100, 60), 3),
        Ingredient("Ember", Color.rgb(255, 110, 50), 8),
        Ingredient("Frost", Color.rgb(170, 230, 255), 8),
        Ingredient("Iron", Color.rgb(150, 155, 170), 10),
        Ingredient("Sulfur", Color.rgb(230, 220, 70), 8),
        Ingredient("Crystal", Color.rgb(190, 120, 255), 15),
        Ingredient("Moondew", Color.rgb(110, 255, 220), 20),
        Ingredient("Gold", Color.rgb(255, 205, 60), 60),
        Ingredient("Petal", Color.rgb(255, 140, 190), 10),
        Ingredient("Honey", Color.rgb(255, 175, 40), 12),
        Ingredient("Spark", Color.rgb(70, 215, 255), 14),
        Ingredient("Pumpkin Seed", Color.rgb(255, 135, 35), 10, event = 1),
        Ingredient("Ecto Goo", Color.rgb(140, 245, 140), 14, event = 1),
        Ingredient("Peppermint", Color.rgb(235, 60, 75), 10, event = 2),
        Ingredient("Gingerbread", Color.rgb(185, 115, 55), 12, event = 2),
        Ingredient("Clover", Color.rgb(70, 190, 85), 10, event = 3),
        Ingredient("Rainbow Drop", Color.rgb(150, 190, 255), 14, event = 3),
    )

    /** Ingredients you can use right now (event ones only while their event is on). */
    fun ingredientsNow(): List<Int> {
        val ev = currentEvent()
        return INGREDIENTS.indices.filter { INGREDIENTS[it].event == 0 || INGREDIENTS[it].event == ev }
    }

    // Cap decorations for special species
    const val D_PUMPKIN = 1; const val D_GHOST = 2; const val D_JACK = 3; const val D_WITCH = 4
    const val D_BAT = 5; const val D_CANDY = 6; const val D_ICING = 7; const val D_SNOW = 8
    const val D_HOLLY = 9; const val D_SPROUT = 10; const val D_RAINBOW = 11; const val D_FLOWER = 12
    const val D_BEE = 13; const val D_CLOVER4 = 14; const val D_BOLT = 15; const val D_HEART = 16; const val D_RIBS = 17

    // ---------- Food ----------
    val FOODS = arrayOf(
        Food("Water Drop", Color.rgb(90, 170, 255), 2, 15, 4, 0),
        Food("Compost", Color.rgb(120, 85, 55), 5, 30, 10, 2),
        Food("Sugar Cube", Color.rgb(250, 245, 235), 10, 20, 20, 12),
        Food("Mineral Mix", Color.rgb(160, 165, 180), 15, 35, 30, 4),
        Food("Moon Jelly", Color.rgb(200, 120, 255), 40, 50, 80, 20),
        // Event treats
        Food("Candy Corn", Color.rgb(255, 170, 60), 12, 15, 15, 30, event = 1),
        Food("Hot Cocoa", Color.rgb(140, 90, 60), 15, 30, 20, 25, event = 2),
        Food("Strawberry", Color.rgb(240, 70, 90), 12, 25, 18, 22, event = 3),
    )

    // ---------- Species ----------
    val SPECIES = arrayOf(
        /* 0 */ Species("Common Shroom", Color.rgb(230, 215, 190), Color.rgb(250, 240, 225), 0, 0, 1, 1,
            "Plain dirt, twice"),
        /* 1 */ Species("Button Cap", Color.rgb(200, 150, 100), Color.rgb(255, 240, 220), Color.rgb(240, 210, 170), 0, 1, 1,
            "Something wet + something earthy"),
        /* 2 */ Species("Ember Cap", Color.rgb(255, 90, 60), Color.rgb(255, 230, 200), Color.rgb(255, 210, 80), 0, 1, 2,
            "Something hot + something smelly"),
        /* 3 */ Species("Frost Puff", Color.rgb(170, 225, 255), Color.rgb(230, 245, 255), Color.WHITE, 1, 1, 0,
            "Something cold + something wet"),
        /* 4 */ Species("Iron Bolete", Color.rgb(130, 135, 150), Color.rgb(220, 220, 225), 0, 0, 1, 3,
            "Something metal + something earthy"),
        /* 5 */ Species("Crystal Morel", Color.rgb(170, 110, 240), Color.rgb(240, 225, 255), Color.rgb(120, 70, 190), 2, 1, 3,
            "Something shiny + something earthy"),
        /* 6 */ Species("Glow Cap", Color.rgb(80, 230, 200), Color.rgb(220, 255, 245), Color.rgb(200, 255, 240), 0, 1, 4,
            "Something from the moon + something wet", glow = true),
        /* 7 */ Species("Stink Horn", Color.rgb(190, 210, 70), Color.rgb(250, 250, 210), Color.rgb(120, 140, 40), 4, 1, 1,
            "Something smelly + something earthy"),
        /* 8 */ Species("Rust Shroom", Color.rgb(200, 100, 50), Color.rgb(245, 220, 200), Color.rgb(150, 70, 35), 3, 1, 3,
            "Something metal + something wet"),
        /* 9 */ Species("Gem Cap", Color.rgb(255, 120, 190), Color.rgb(255, 235, 245), Color.WHITE, 0, 1, 2,
            "Something shiny + something from the moon"),
        /* 10 */ Species("Magma Morel", Color.rgb(170, 40, 40), Color.rgb(90, 60, 60), Color.rgb(255, 140, 40), 2, 1, 3,
            "Something hot + something metal", glow = true),
        /* 11 */ Species("Snow Shroom", Color.rgb(245, 250, 255), Color.rgb(225, 235, 245), Color.rgb(190, 215, 240), 1, 1, 0,
            "Something cold + something shiny"),
        /* 12 */ Species("Golden Chanterelle", Color.rgb(255, 200, 60), Color.rgb(255, 235, 170), 0, 3, 1, 4,
            "Something precious + something earthy"),

        // Hybrids: crossbreed two grown-up mushrooms
        /* 13 */ Species("Steam Puff", Color.rgb(235, 235, 245), Color.rgb(255, 210, 200), Color.rgb(255, 150, 130), 1, 2, 2,
            "Crossbreed a fiery cap with a frosty puff"),
        /* 14 */ Species("Prism Shroom", Color.rgb(150, 210, 255), Color.rgb(255, 240, 255), Color.rgb(255, 150, 220), 2, 2, 4,
            "Crossbreed a glowing cap with a gem cap", glow = true),
        /* 15 */ Species("Forge King", Color.rgb(90, 90, 105), Color.rgb(170, 160, 160), Color.rgb(255, 120, 40), 0, 2, 3,
            "Crossbreed iron with magma"),
        /* 16 */ Species("Royal Cap", Color.rgb(150, 60, 200), Color.rgb(255, 240, 210), Color.rgb(255, 210, 70), 0, 2, 4,
            "Crossbreed a button cap with something golden", crown = true),
        /* 17 */ Species("Aurora Cap", Color.rgb(120, 255, 180), Color.rgb(230, 240, 255), Color.rgb(180, 140, 255), 3, 2, 4,
            "Crossbreed snow with glow", glow = true),
        /* 18 */ Species("Bog Lurker", Color.rgb(90, 130, 60), Color.rgb(180, 170, 130), Color.rgb(160, 110, 60), 4, 2, 1,
            "Crossbreed something stinky with something rusty"),
        /* 19 */ Species("Diamond Morel", Color.rgb(220, 245, 255), Color.rgb(240, 240, 255), Color.rgb(140, 200, 255), 2, 2, 3,
            "Crossbreed a crystal morel with snow"),

        // Legendaries: crossbreed two hybrids
        /* 20 */ Species("Starlight Shroom", Color.rgb(40, 40, 110), Color.rgb(220, 220, 255), Color.rgb(255, 240, 150), 0, 3, 4,
            "Crossbreed prism with aurora", glow = true),
        /* 21 */ Species("Emperor Shroom", Color.rgb(200, 30, 60), Color.rgb(255, 230, 180), Color.rgb(255, 215, 80), 0, 3, 4,
            "Crossbreed a forge king with royalty", crown = true),

        // More common species (every ingredient combo grows something real)
        /* 22 */ Species("Dewdrop Puff", Color.rgb(140, 200, 255), Color.rgb(225, 240, 255), Color.rgb(210, 235, 255), 1, 1, 0,
            "Water, and more water"),
        /* 23 */ Species("Mist Cap", Color.rgb(205, 205, 225), Color.rgb(240, 240, 250), Color.rgb(170, 175, 200), 0, 1, 0,
            "Something hot meets something wet or cold"),
        /* 24 */ Species("Swamp Funnel", Color.rgb(110, 160, 80), Color.rgb(200, 210, 160), Color.rgb(70, 110, 50), 3, 1, 1,
            "Something smelly + something wet or cold"),
        /* 25 */ Species("Bubble Cap", Color.rgb(255, 170, 210), Color.rgb(255, 235, 245), Color.rgb(200, 230, 255), 1, 1, 2,
            "Something shiny + something wet"),
        /* 26 */ Species("Charred Bolete", Color.rgb(110, 70, 50), Color.rgb(220, 200, 180), Color.rgb(255, 130, 50), 0, 1, 1,
            "Something hot + something earthy"),
        /* 27 */ Species("Tundra Funnel", Color.rgb(180, 210, 230), Color.rgb(235, 240, 245), Color.rgb(120, 160, 190), 3, 1, 0,
            "Something cold + something earthy"),
        /* 28 */ Species("Moss Cap", Color.rgb(90, 190, 110), Color.rgb(220, 240, 210), Color.rgb(160, 230, 150), 0, 1, 1,
            "Something from the moon + something earthy"),
        /* 29 */ Species("Fire Puff", Color.rgb(255, 130, 50), Color.rgb(255, 220, 180), Color.rgb(255, 220, 80), 1, 1, 2,
            "Something hot, twice"),
        /* 30 */ Species("Ruby Cap", Color.rgb(220, 30, 70), Color.rgb(255, 230, 230), Color.WHITE, 0, 1, 2,
            "Something shiny + something hot or smelly"),
        /* 31 */ Species("Firefly Cap", Color.rgb(250, 220, 80), Color.rgb(255, 245, 210), Color.rgb(255, 255, 200), 0, 1, 4,
            "Something from the moon + something hot or smelly", glow = true),
        /* 32 */ Species("Sun Cap", Color.rgb(255, 170, 40), Color.rgb(255, 235, 190), Color.rgb(255, 240, 150), 3, 1, 4,
            "Something precious + something bright", glow = true),
        /* 33 */ Species("Icicle Horn", Color.rgb(160, 225, 255), Color.rgb(235, 248, 255), Color.WHITE, 4, 1, 0,
            "Something cold, twice"),

        // Rare: three-ingredient brews (needs the Big Flask upgrade)
        /* 34 */ Species("Storm Cap", Color.rgb(50, 70, 140), Color.rgb(210, 220, 240), Color.rgb(255, 230, 80), 0, 4, 3,
            "Water, fire and metal in one flask", glow = true),
        /* 35 */ Species("Rainbow Puff", Color.rgb(255, 190, 230), Color.rgb(255, 245, 250), Color.rgb(150, 230, 255), 1, 4, 4,
            "Water, crystal and moondew together", glow = true),
        /* 36 */ Species("Ancient Morel", Color.rgb(170, 130, 70), Color.rgb(230, 210, 170), Color.rgb(255, 210, 90), 2, 4, 3,
            "Earth, metal and gold together"),
        /* 37 */ Species("Lava Horn", Color.rgb(255, 90, 30), Color.rgb(120, 60, 50), Color.rgb(255, 220, 90), 4, 4, 2,
            "Two embers and something smelly", glow = true),
        /* 38 */ Species("Frostfire Funnel", Color.rgb(140, 110, 255), Color.rgb(230, 225, 255), Color.rgb(255, 140, 120), 3, 4, 2,
            "Fire, frost and crystal together"),
        /* 39 */ Species("Midnight Cap", Color.rgb(25, 30, 80), Color.rgb(200, 205, 240), Color.rgb(255, 250, 200), 0, 4, 4,
            "Two moondews and a crystal", glow = true),

        // New ingredients: petal, honey and spark
        /* 40 */ Species("Petal Cap", Color.rgb(255, 150, 200), Color.rgb(255, 240, 245), Color.WHITE, 0, 1, 2,
            "Something flowery + something wet or earthy", deco = D_FLOWER),
        /* 41 */ Species("Rose Morel", Color.rgb(230, 60, 110), Color.rgb(255, 230, 235), Color.rgb(180, 30, 80), 2, 1, 2,
            "Something flowery + something hot or shiny"),
        /* 42 */ Species("Honey Cap", Color.rgb(255, 185, 50), Color.rgb(255, 240, 210), Color.rgb(255, 230, 140), 0, 1, 2,
            "Something sweet + something wet or earthy"),
        /* 43 */ Species("Amber Puff", Color.rgb(230, 140, 40), Color.rgb(255, 225, 180), Color.rgb(255, 210, 120), 1, 1, 3,
            "Something sweet + something hot, metal or shiny", glow = true),
        /* 44 */ Species("Bee Funnel", Color.rgb(255, 210, 60), Color.rgb(255, 245, 220), Color.rgb(255, 235, 150), 3, 1, 2,
            "Something sweet + something flowery", deco = D_BEE),
        /* 45 */ Species("Spark Cap", Color.rgb(80, 220, 255), Color.rgb(230, 250, 255), Color.rgb(255, 255, 160), 0, 1, 4,
            "Something electric + something wet or earthy", glow = true, deco = D_BOLT),
        /* 46 */ Species("Zap Horn", Color.rgb(120, 150, 255), Color.rgb(225, 230, 255), Color.rgb(255, 240, 120), 4, 1, 3,
            "Something electric + something metal or precious", glow = true),
        /* 47 */ Species("Thunder Puff", Color.rgb(110, 120, 170), Color.rgb(225, 225, 240), Color.rgb(255, 240, 120), 1, 1, 0,
            "Something electric + something cold"),
        /* 48 */ Species("Lilac Puff", Color.rgb(190, 150, 255), Color.rgb(245, 235, 255), Color.WHITE, 1, 1, 4,
            "Something flowery + something cold or from the moon"),
        /* 49 */ Species("Sundae Puff", Color.rgb(255, 235, 215), Color.rgb(240, 190, 150), Color.rgb(255, 120, 160), 1, 1, 2,
            "Something sweet + something cold or from the moon"),
        /* 50 */ Species("Neon Morel", Color.rgb(60, 255, 180), Color.rgb(220, 255, 240), Color.rgb(255, 80, 220), 2, 1, 4,
            "Something electric + something shiny or from the moon", glow = true),
        /* 51 */ Species("Ash Cap", Color.rgb(90, 85, 95), Color.rgb(210, 200, 200), Color.rgb(255, 120, 60), 0, 1, 3,
            "Something electric + something hot or smelly"),
        /* 52 */ Species("Fairy Ring Cap", Color.rgb(255, 170, 230), Color.rgb(255, 245, 250), Color.rgb(200, 255, 240), 0, 4, 4,
            "Petals, honey and moondew together", glow = true, deco = D_FLOWER),
        /* 53 */ Species("Tesla Horn", Color.rgb(70, 90, 220), Color.rgb(210, 220, 255), Color.rgb(120, 255, 255), 4, 4, 3,
            "Two sparks and some iron", glow = true, deco = D_BOLT),
        /* 54 */ Species("Sweetheart Shroom", Color.rgb(255, 100, 150), Color.rgb(255, 235, 240), Color.rgb(255, 200, 220), 0, 2, 2,
            "Crossbreed a petal cap with a honey cap", deco = D_HEART),
        /* 55 */ Species("Lightning Morel", Color.rgb(255, 235, 90), Color.rgb(240, 240, 255), Color.rgb(80, 200, 255), 2, 2, 3,
            "Crossbreed a spark cap with snow", glow = true),
        /* 56 */ Species("Garden Sprite", Color.rgb(130, 220, 120), Color.rgb(240, 255, 230), Color.rgb(255, 220, 90), 3, 2, 1,
            "Crossbreed a bee funnel with moss", glow = true, deco = D_FLOWER),
        /* 57 */ Species("Eternal Bloom", Color.rgb(255, 130, 200), Color.rgb(255, 245, 230), Color.rgb(255, 240, 150), 0, 3, 4,
            "Crossbreed a sweetheart with a garden sprite", glow = true, crown = true, deco = D_FLOWER),

        // Shroomoween only
        /* 58 */ Species("Pumpkin Cap", Color.rgb(255, 140, 30), Color.rgb(240, 230, 200), 0, 0, 1, 5,
            "Pumpkin Seed + almost anything", event = 1, deco = D_PUMPKIN),
        /* 59 */ Species("Ghost Puff", Color.rgb(245, 245, 255), Color.rgb(230, 230, 245), 0, 1, 1, 5,
            "Ecto Goo + almost anything", glow = true, event = 1, deco = D_GHOST),
        /* 60 */ Species("Jack Cap", Color.rgb(255, 120, 20), Color.rgb(110, 85, 70), 0, 0, 1, 5,
            "Pumpkin Seed + something hot or electric", glow = true, event = 1, deco = D_JACK),
        /* 61 */ Species("Witch Morel", Color.rgb(120, 60, 170), Color.rgb(200, 230, 160), Color.rgb(70, 30, 110), 2, 1, 5,
            "Ecto Goo + something shiny or from the moon", event = 1, deco = D_WITCH),
        /* 62 */ Species("Bat Cap", Color.rgb(80, 50, 105), Color.rgb(215, 195, 225), Color.rgb(255, 150, 60), 0, 1, 5,
            "Pumpkin Seed + Ecto Goo", event = 1, deco = D_BAT),
        /* 63 */ Species("Pumpkin King", Color.rgb(255, 150, 40), Color.rgb(130, 95, 150), 0, 0, 2, 5,
            "Crossbreed a pumpkin cap with a ghost puff", glow = true, crown = true, event = 1, deco = D_RIBS),

        // Frosty Festival only
        /* 64 */ Species("Candy Cane Horn", Color.rgb(250, 250, 250), Color.rgb(255, 240, 240), 0, 4, 1, 6,
            "Peppermint + almost anything", event = 2, deco = D_CANDY),
        /* 65 */ Species("Gingerbread Cap", Color.rgb(190, 115, 55), Color.rgb(230, 180, 120), Color.rgb(240, 60, 80), 0, 1, 6,
            "Gingerbread + almost anything", event = 2, deco = D_ICING),
        /* 66 */ Species("Cocoa Funnel", Color.rgb(130, 80, 50), Color.rgb(240, 225, 210), Color.rgb(255, 250, 245), 3, 1, 6,
            "Gingerbread + something hot or sweet", event = 2),
        /* 67 */ Species("Snowglobe Puff", Color.rgb(170, 220, 255), Color.rgb(230, 240, 255), Color.WHITE, 1, 1, 6,
            "Peppermint + something cold, shiny or from the moon", glow = true, event = 2, deco = D_SNOW),
        /* 68 */ Species("Sugarplum Puff", Color.rgb(170, 90, 200), Color.rgb(245, 230, 250), Color.WHITE, 1, 1, 6,
            "Peppermint + Gingerbread", event = 2, deco = D_HOLLY),
        /* 69 */ Species("Frost Monarch", Color.rgb(200, 235, 255), Color.rgb(240, 245, 255), Color.rgb(240, 60, 80), 0, 2, 6,
            "Crossbreed a candy cane horn with a gingerbread cap", glow = true, crown = true, event = 2, deco = D_SNOW),

        // Spring Bloom only
        /* 70 */ Species("Clover Cap", Color.rgb(90, 200, 100), Color.rgb(240, 250, 230), Color.rgb(200, 255, 190), 0, 1, 7,
            "Clover + almost anything", event = 3, deco = D_SPROUT),
        /* 71 */ Species("Rainbow Funnel", Color.rgb(255, 120, 160), Color.rgb(255, 250, 255), Color.rgb(140, 220, 255), 3, 1, 7,
            "Rainbow Drop + almost anything", glow = true, event = 3, deco = D_RAINBOW),
        /* 72 */ Species("Blossom Cap", Color.rgb(255, 190, 215), Color.rgb(255, 245, 250), Color.WHITE, 0, 1, 7,
            "Clover or Rainbow Drop + something flowery", event = 3, deco = D_FLOWER),
        /* 73 */ Species("Bumble Cap", Color.rgb(255, 200, 40), Color.rgb(255, 245, 220), 0, 1, 1, 7,
            "Clover or Rainbow Drop + something sweet", event = 3, deco = D_BEE),
        /* 74 */ Species("Lucky Shroom", Color.rgb(60, 190, 90), Color.rgb(255, 245, 200), Color.rgb(255, 215, 80), 0, 1, 7,
            "Clover + Rainbow Drop", glow = true, event = 3, deco = D_CLOVER4),
        /* 75 */ Species("Bloom Queen", Color.rgb(255, 150, 200), Color.rgb(255, 250, 240), Color.rgb(255, 240, 150), 0, 2, 7,
            "Crossbreed a blossom cap with a lucky shroom", glow = true, crown = true, event = 3, deco = D_FLOWER),
    )

    /** Event species start/end dates, for the Shroomdex. */
    val EVENT_STARTS = arrayOf("", "Oct 10", "Dec 1", "Mar 20")
    val EVENT_COLORS = intArrayOf(0, Color.rgb(255, 150, 50), Color.rgb(150, 210, 255), Color.rgb(255, 160, 210))

    /** Species you can find any time of year. */
    val BASE_SPECIES: List<Int> = SPECIES.indices.filter { SPECIES[it].event == 0 }

    val TIER_NAMES = arrayOf("", "Common", "Hybrid", "Legendary", "Rare")
    private val TIER_RANK = intArrayOf(0, 0, 2, 3, 1)

    /** Shroomdex order: commons, rares, hybrids, then legendaries. */
    val DEX_ORDER: List<Int> = SPECIES.indices.sortedWith(compareBy({ TIER_RANK[SPECIES[it].tier] }, { it }))

    private fun key(a: Int, b: Int) = if (a <= b) "$a,$b" else "$b,$a"

    private val BREWS = hashMapOf(
        key(SOIL, WATER) to 1,
        key(EMBER, SULFUR) to 2,
        key(FROST, WATER) to 3,
        key(IRON, SOIL) to 4,
        key(CRYSTAL, SOIL) to 5,
        key(MOONDEW, WATER) to 6,
        key(SULFUR, SOIL) to 7,
        key(IRON, WATER) to 8,
        key(CRYSTAL, MOONDEW) to 9,
        key(EMBER, IRON) to 10,
        key(FROST, CRYSTAL) to 11,
        key(GOLD, SOIL) to 12,

        // Everything else grows something too
        key(SOIL, SOIL) to 0,
        key(WATER, WATER) to 22,
        key(WATER, EMBER) to 23,
        key(EMBER, FROST) to 23,
        key(WATER, SULFUR) to 24,
        key(FROST, SULFUR) to 24,
        key(WATER, CRYSTAL) to 25,
        key(SOIL, EMBER) to 26,
        key(SOIL, FROST) to 27,
        key(SOIL, MOONDEW) to 28,
        key(EMBER, EMBER) to 29,
        key(EMBER, CRYSTAL) to 30,
        key(SULFUR, CRYSTAL) to 30,
        key(EMBER, MOONDEW) to 31,
        key(SULFUR, MOONDEW) to 31,
        key(GOLD, EMBER) to 32,
        key(GOLD, MOONDEW) to 32,
        key(FROST, FROST) to 33,
        key(FROST, IRON) to 4,
        key(IRON, IRON) to 4,
        key(IRON, MOONDEW) to 4,
        key(FROST, MOONDEW) to 6,
        key(MOONDEW, MOONDEW) to 6,
        key(FROST, GOLD) to 11,
        key(IRON, SULFUR) to 8,
        key(IRON, CRYSTAL) to 5,
        key(CRYSTAL, CRYSTAL) to 5,
        key(SULFUR, SULFUR) to 7,
        key(CRYSTAL, GOLD) to 9,
        key(WATER, GOLD) to 12,
        key(IRON, GOLD) to 12,
        key(SULFUR, GOLD) to 12,
        key(GOLD, GOLD) to 12,

        // Petal
        key(PETAL, WATER) to 40, key(PETAL, SOIL) to 40, key(PETAL, IRON) to 40, key(PETAL, PETAL) to 40,
        key(PETAL, EMBER) to 41, key(PETAL, CRYSTAL) to 41,
        key(PETAL, FROST) to 48, key(PETAL, MOONDEW) to 48, key(PETAL, GOLD) to 48,
        key(PETAL, SULFUR) to 7, key(PETAL, HONEY) to 44, key(PETAL, SPARK) to 50,
        // Honey
        key(HONEY, WATER) to 42, key(HONEY, SOIL) to 42, key(HONEY, SULFUR) to 42, key(HONEY, HONEY) to 42,
        key(HONEY, EMBER) to 43, key(HONEY, IRON) to 43, key(HONEY, CRYSTAL) to 43, key(HONEY, GOLD) to 43,
        key(HONEY, FROST) to 49, key(HONEY, MOONDEW) to 49, key(HONEY, SPARK) to 45,
        // Spark
        key(SPARK, WATER) to 45, key(SPARK, SOIL) to 45,
        key(SPARK, IRON) to 46, key(SPARK, GOLD) to 46, key(SPARK, SPARK) to 46,
        key(SPARK, FROST) to 47,
        key(SPARK, CRYSTAL) to 50, key(SPARK, MOONDEW) to 50,
        key(SPARK, EMBER) to 51, key(SPARK, SULFUR) to 51,

        // Shroomoween
        key(PUMPKIN, EMBER) to 60, key(PUMPKIN, SPARK) to 60, key(PUMPKIN, ECTO) to 62,
        key(ECTO, CRYSTAL) to 61, key(ECTO, MOONDEW) to 61,
        // Frosty Festival
        key(GINGER, EMBER) to 66, key(GINGER, HONEY) to 66,
        key(PEPPERMINT, FROST) to 67, key(PEPPERMINT, CRYSTAL) to 67, key(PEPPERMINT, MOONDEW) to 67,
        key(PEPPERMINT, GINGER) to 68,
        // Spring Bloom
        key(CLOVER, PETAL) to 72, key(RAINBOW, PETAL) to 72,
        key(CLOVER, HONEY) to 73, key(RAINBOW, HONEY) to 73,
        key(CLOVER, RAINBOW) to 74,
    )

    /** What an event ingredient grows with anything that isn't a special recipe. */
    private val EVENT_DEFAULT = mapOf(PUMPKIN to 58, ECTO to 59, PEPPERMINT to 64, GINGER to 65, CLOVER to 70, RAINBOW to 71)

    private val CROSSES = hashMapOf(
        key(2, 3) to 13,
        key(6, 9) to 14,
        key(4, 10) to 15,
        key(1, 12) to 16,
        key(11, 6) to 17,
        key(7, 8) to 18,
        key(5, 11) to 19,
        key(14, 17) to 20,
        key(15, 16) to 21,
        key(40, 42) to 54,
        key(45, 11) to 55,
        key(44, 28) to 56,
        key(54, 56) to 57,
        key(58, 59) to 63,
        key(64, 65) to 69,
        key(72, 74) to 75,
    )

    /** Which species two ingredients make. Every pair has a recipe. */
    fun brew(a: Int, b: Int): Int = BREWS[key(a, b)] ?: EVENT_DEFAULT[a] ?: EVENT_DEFAULT[b] ?: 0

    private fun key3(a: Int, b: Int, c: Int) = listOf(a, b, c).sorted().joinToString(",")

    private val BREWS3 = hashMapOf(
        key3(WATER, EMBER, IRON) to 34,
        key3(WATER, CRYSTAL, MOONDEW) to 35,
        key3(SOIL, IRON, GOLD) to 36,
        key3(EMBER, EMBER, SULFUR) to 37,
        key3(EMBER, FROST, CRYSTAL) to 38,
        key3(MOONDEW, MOONDEW, CRYSTAL) to 39,
        key3(PETAL, HONEY, MOONDEW) to 52,
        key3(SPARK, SPARK, IRON) to 53,
    )

    /** The rare species these three ingredients can make, or -1 if they aren't a rare recipe. */
    fun rareRecipe3(a: Int, b: Int, c: Int): Int = BREWS3[key3(a, b, c)] ?: -1

    /**
     * What three ingredients grow when they aren't a rare recipe (or the rare brew fizzles):
     * the species most of the ingredient pairs agree on, so it always fits what went in.
     */
    fun brew3Common(a: Int, b: Int, c: Int): Int {
        val results = listOf(brew(a, b), brew(a, c), brew(b, c))
        val best = results.groupingBy { it }.eachCount()
        val top = best.maxOf { it.value }
        return results.first { best[it] == top && (it != 0 || results.all { r -> r == 0 }) }
    }

    fun brew3(a: Int, b: Int, c: Int): Int = rareRecipe3(a, b, c).takeIf { it >= 0 } ?: brew3Common(a, b, c)

    /** Base odds of a special result working out, before stirring and other bonuses. */
    fun baseChance(species: Int): Float = when (SPECIES[species].tier) {
        4 -> 0.40f   // rare 3-ingredient brew
        3 -> 0.25f   // legendary cross
        else -> 0.50f // hybrid cross
    }

    /** Which species two grown mushrooms make, or -1 if they have no special hybrid. */
    fun cross(a: Int, b: Int): Int {
        val r = CROSSES[key(a, b)] ?: return -1
        return if (SPECIES[r].event != 0 && SPECIES[r].event != currentEvent()) -1 else r
    }

    /** True if this species can't be made right now because its event is over. */
    fun offSeason(species: Int): Boolean = SPECIES[species].event != 0 && SPECIES[species].event != currentEvent()

    /** Human-readable recipe for the Shroomdex once a species is discovered (up to two ways). */
    fun recipeText(species: Int): String {
        EVENT_DEFAULT.entries.firstOrNull { it.value == species }?.let { return "${INGREDIENTS[it.key].name} + almost anything" }
        val brews = BREWS.entries.filter { it.value == species }.take(2).map { e ->
            val (a, b) = e.key.split(",").map { it.toInt() }
            "${INGREDIENTS[a].name} + ${INGREDIENTS[b].name}"
        }
        if (brews.isNotEmpty()) return brews.joinToString("  or  ")
        BREWS3.entries.firstOrNull { it.value == species }?.let { e ->
            return e.key.split(",").joinToString(" + ") { INGREDIENTS[it.toInt()].name }
        }
        CROSSES.entries.firstOrNull { it.value == species }?.let { e ->
            val (a, b) = e.key.split(",").map { it.toInt() }
            return "${SPECIES[a].name} × ${SPECIES[b].name}"
        }
        return "Unknown"
    }

    // ---------- Garden decorations ----------
    val DECOR = arrayOf(
        Decor("Lantern", 30, glow = true),
        Decor("Flower Patch", 15),
        Decor("Big Rock", 20),
        Decor("Picket Fence", 25),
        Decor("Pond", 60),
        Decor("Crystal Cluster", 80, glow = true),
        Decor("Toadstool House", 150, glow = true),
        Decor("Bench", 40),
        Decor("Signpost", 20),
        Decor("Fairy Lights", 100, glow = true),
        Decor("Tree Stump", 25),
        // Toys
        Decor("Ball", 30),
        Decor("Swing", 90),
        Decor("Slide", 120),
        Decor("Trampoline", 110),
        Decor("Campfire", 80, glow = true),
        // Shroomoween
        Decor("Jack-o'-lantern", 50, glow = true, event = 1),
        Decor("Spooky Tree", 70, event = 1),
        Decor("Tombstone", 40, event = 1),
        Decor("Candy Bowl", 35, event = 1),
        // Frosty Festival
        Decor("Snowman", 60, event = 2),
        Decor("Gift Box", 40, event = 2),
        // Spring Bloom
        Decor("Flower Arch", 80, event = 3),
        Decor("Egg Basket", 40, event = 3),
    )

    val THEMES = arrayOf(
        Theme("Moonlit Meadow", 0, Color.rgb(30, 20, 64), Color.rgb(110, 70, 150), Color.rgb(40, 62, 70),
            Color.rgb(78, 140, 104), Color.rgb(70, 45, 105), Color.rgb(58, 38, 90)),
        Theme("Sunny Day", 100, Color.rgb(90, 170, 240), Color.rgb(190, 225, 255), Color.rgb(90, 150, 80),
            Color.rgb(130, 200, 100), Color.rgb(110, 160, 120), Color.rgb(90, 140, 100)),
        Theme("Autumn Woods", 150, Color.rgb(230, 130, 80), Color.rgb(255, 200, 140), Color.rgb(120, 80, 50),
            Color.rgb(170, 120, 60), Color.rgb(170, 70, 40), Color.rgb(130, 55, 35)),
        Theme("Crystal Cave", 250, Color.rgb(15, 10, 35), Color.rgb(40, 30, 80), Color.rgb(35, 30, 60),
            Color.rgb(70, 60, 110), Color.rgb(90, 60, 160), Color.rgb(60, 40, 120)),
        Theme("Snowy Night", 200, Color.rgb(20, 30, 70), Color.rgb(70, 90, 140), Color.rgb(170, 185, 210),
            Color.rgb(230, 238, 250), Color.rgb(50, 60, 100), Color.rgb(40, 48, 85)),
        Theme("Spooky Night", 120, Color.rgb(40, 15, 60), Color.rgb(200, 90, 50), Color.rgb(45, 35, 55),
            Color.rgb(80, 70, 70), Color.rgb(35, 20, 45), Color.rgb(25, 15, 35), event = 1),
        Theme("Cherry Blossom", 150, Color.rgb(255, 180, 210), Color.rgb(255, 230, 235), Color.rgb(110, 160, 110),
            Color.rgb(150, 210, 140), Color.rgb(240, 150, 190), Color.rgb(220, 120, 170), event = 3),
    )

    val STAGE_NAMES = arrayOf("Sprout", "Young", "Grown", "Elder")
    val STAGE_XP = intArrayOf(0, 40, 150, 500)
    val TIER_MUL = intArrayOf(0, 1, 2, 4, 3)

    // ---------- Personalities ----------
    val TRAITS = arrayOf("Sleepy", "Bouncy", "Shy", "Greedy", "Curious", "Cheerful")
    val TRAIT_DESC = arrayOf(
        "Naps a lot and walks slowly",
        "Hops everywhere, full of energy",
        "Keeps its distance from others",
        "Always hungry, but grows more from food",
        "Loves decorations and finds more on expeditions",
        "Stays happy longer",
    )
    val NAMES = arrayOf("Pip", "Bloop", "Mori", "Tuff", "Nibs", "Puddle", "Sprig", "Wobble", "Fen", "Gloop",
        "Truffle", "Button", "Pebble", "Doodle", "Mossy", "Bean", "Pudding", "Sporey", "Tiko", "Mallow",
        "Squish", "Bramble", "Nugget", "Toffee", "Lumi", "Fizz", "Bubbles", "Dot", "Waffle", "Biscuit",
        "Juniper", "Clover", "Pickle", "Noodle", "Sunny", "Morel", "Shiitake", "Puff", "Ziggy", "Mochi")

    // ---------- Hats & accessories ----------
    val ACCESSORIES = arrayOf(
        Accessory("Party Hat", 30, 0, Color.rgb(255, 120, 170)),
        Accessory("Bow", 30, 0, Color.rgb(255, 90, 130)),
        Accessory("Beanie", 45, 0, Color.rgb(90, 160, 255)),
        Accessory("Flower Crown", 60, 0, Color.rgb(255, 230, 120)),
        Accessory("Top Hat", 80, 0, Color.rgb(40, 35, 50)),
        Accessory("Cowboy Hat", 90, 0, Color.rgb(170, 110, 60)),
        Accessory("Wizard Hat", 140, 0, Color.rgb(110, 70, 200)),
        Accessory("Tiny Crown", 250, 0, Color.rgb(255, 205, 60)),
        Accessory("Halo", 300, 0, Color.rgb(255, 245, 170)),
        Accessory("Glasses", 40, 1, Color.rgb(50, 40, 60)),
        Accessory("Sunglasses", 60, 1, Color.rgb(20, 20, 30)),
        Accessory("Scarf", 50, 2, Color.rgb(230, 70, 70)),
        Accessory("Bow Tie", 45, 2, Color.rgb(90, 200, 255)),
        Accessory("Headphones", 120, 0, Color.rgb(255, 110, 80)),
        // Event outfits
        Accessory("Pumpkin Hat", 40, 0, Color.rgb(255, 140, 40), event = 1),
        Accessory("Witch Hat", 70, 0, Color.rgb(45, 30, 60), event = 1),
        Accessory("Ghost Sheet", 80, 0, Color.WHITE, event = 1),
        Accessory("Bat Wings", 90, 2, Color.rgb(60, 40, 70), event = 1),
        Accessory("Santa Hat", 60, 0, Color.rgb(220, 40, 50), event = 2),
        Accessory("Antlers", 70, 0, Color.rgb(150, 100, 60), event = 2),
        Accessory("Bunny Ears", 60, 0, Color.rgb(250, 245, 250), event = 3),
        Accessory("Daisy Clip", 40, 0, Color.WHITE, event = 3),
    )

    // ---------- Seasonal events ----------
    val EVENTS = arrayOf("", "Shroomoween", "Frosty Festival", "Spring Bloom")
    val EVENT_ICONS = arrayOf("", "🎃", "❄️", "🌸")
    val EVENT_ENDS = arrayOf("", "Nov 3", "Jan 6", "Apr 25")

    /** Which seasonal event is on today (0 = none). */
    /** Test runs can pretend an event is on (-1 = use the real date). */
    var forceEvent = -1

    fun currentEvent(cal: java.util.Calendar = java.util.Calendar.getInstance()): Int {
        if (forceEvent >= 0) return forceEvent
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return when {
            (m == 10 && d >= 10) || (m == 11 && d <= 3) -> 1
            m == 12 || (m == 1 && d <= 6) -> 2
            (m == 3 && d >= 20) || (m == 4 && d <= 25) -> 3
            else -> 0
        }
    }

    // ---------- Lab upgrades ----------
    const val UP_GARDEN = 0
    const val UP_FLASK = 1
    const val UP_FEEDER = 2
    const val UP_COLLECTOR = 3
    const val UP_REST = 4
    const val UP_BOOTS = 5
    val UPGRADES = arrayOf(
        Upgrade("Bigger Garden", "+2 garden spots per level", intArrayOf(200, 500, 1200)),
        Upgrade("Big Flask", "Brew with 3 ingredients for Rare species", intArrayOf(300)),
        Upgrade("Auto-Feeder", "Feeds hungry mushrooms from your food stock", intArrayOf(400)),
        Upgrade("Dust Collector", "Collects spore dust for you", intArrayOf(350)),
        Upgrade("Cozy Beds", "Shorter rest after crossbreeding", intArrayOf(150, 400)),
        Upgrade("Trail Boots", "Expeditions finish 25% faster", intArrayOf(250)),
    )

    // ---------- Expeditions ----------
    val ZONES = arrayOf(
        Zone("Whispering Forest", 1f, intArrayOf(SOIL, WATER, SULFUR), Color.rgb(90, 170, 100), 2),
        Zone("Crystal Cave", 3f, intArrayOf(CRYSTAL, IRON, CRYSTAL), Color.rgb(170, 120, 255), 2),
        Zone("Frost Peaks", 6f, intArrayOf(FROST, FROST, CRYSTAL), Color.rgb(170, 220, 255), 2),
        Zone("Moon Crater", 12f, intArrayOf(MOONDEW, MOONDEW, GOLD), Color.rgb(230, 230, 180), 3),
    )
    val STORIES = arrayOf(
        "made friends with a beetle", "got lost, then found a shortcut", "napped under a giant leaf",
        "found a shiny pebble", "danced in a rain puddle", "met a very old mushroom who told stories",
        "chased a firefly", "slid down a mossy hill", "found a hidden spring", "sang to the moon",
    )

    // ---------- Achievements ----------
    val ACHIEVEMENTS = arrayOf(
        Achievement("First Sprout", "Brew your first mushroom", 10),
        Achievement("Collector", "Discover 10 species", 40),
        Achievement("Scholar", "Discover 25 species", 100),
        Achievement("Shroomologist", "Discover every year-round species", 500),
        Achievement("Matchmaker", "Discover a hybrid", 40),
        Achievement("Legend", "Discover a legendary", 150),
        Achievement("Rare Find", "Discover a rare 3-ingredient species", 80),
        Achievement("Sparkle!", "Get a shiny mushroom", 60),
        Achievement("Perfectionist", "Make 10 perfect brews", 60),
        Achievement("Wise Elder", "Raise a mushroom to Elder", 60),
        Achievement("Interior Designer", "Place 10 decorations", 50),
        Achievement("Fashionista", "Own 5 accessories", 50),
        Achievement("Explorer", "Finish 10 expeditions", 80),
        Achievement("Champion", "Win a beauty contest", 80),
        Achievement("Full House", "Fill your garden", 40),
        Achievement("Regular", "Visit 7 days in a row", 100),
        Achievement("Mutant", "Get an albino or neon mushroom", 100),
    )
}
