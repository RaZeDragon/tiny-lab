package com.razedragon.tinylab

import android.graphics.Color

/** Things you brew with. `icon` picks how the little picture is drawn. */
class Ingredient(val name: String, val color: Int, val cost: Int)

/** Garden decorations you can buy and place. */
class Decor(val name: String, val cost: Int, val glow: Boolean = false)

/** Garden looks. */
class Theme(val name: String, val cost: Int, val skyTop: Int, val skyBottom: Int, val groundFar: Int,
            val groundNear: Int, val forest1: Int, val forest2: Int)

/** Wearable: slot 0 = on the cap, 1 = on the face, 2 = around the stem. */
class Accessory(val name: String, val cost: Int, val slot: Int, val color: Int)

/** A lab upgrade with one or more levels. */
class Upgrade(val name: String, val desc: String, val costs: IntArray)

/** An expedition destination. */
class Zone(val name: String, val hours: Float, val loot: IntArray, val color: Int, val minStage: Int)

class Achievement(val name: String, val desc: String, val reward: Int)

/** Things you feed your mushrooms. */
class Food(val name: String, val color: Int, val cost: Int, val hunger: Int, val xp: Int, val happy: Int)

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
    )

    // ---------- Food ----------
    val FOODS = arrayOf(
        Food("Water Drop", Color.rgb(90, 170, 255), 2, 15, 4, 0),
        Food("Compost", Color.rgb(120, 85, 55), 5, 30, 10, 2),
        Food("Sugar Cube", Color.rgb(250, 245, 235), 10, 20, 20, 12),
        Food("Mineral Mix", Color.rgb(160, 165, 180), 15, 35, 30, 4),
        Food("Moon Jelly", Color.rgb(200, 120, 255), 40, 50, 80, 20),
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
    )

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
    )

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
    )

    /** Which species two ingredients make. Every pair has a recipe. */
    fun brew(a: Int, b: Int): Int = BREWS[key(a, b)] ?: 0

    private fun key3(a: Int, b: Int, c: Int) = listOf(a, b, c).sorted().joinToString(",")

    private val BREWS3 = hashMapOf(
        key3(WATER, EMBER, IRON) to 34,
        key3(WATER, CRYSTAL, MOONDEW) to 35,
        key3(SOIL, IRON, GOLD) to 36,
        key3(EMBER, EMBER, SULFUR) to 37,
        key3(EMBER, FROST, CRYSTAL) to 38,
        key3(MOONDEW, MOONDEW, CRYSTAL) to 39,
    )

    /** Three ingredients: a rare species if it's a recipe, otherwise what two of them make. */
    fun brew3(a: Int, b: Int, c: Int): Int {
        BREWS3[key3(a, b, c)]?.let { return it }
        val pair = listOf(Pair(a, b), Pair(a, c), Pair(b, c)).random()
        return brew(pair.first, pair.second)
    }

    /** Which species two grown mushrooms make, or -1 if they have no special hybrid. */
    fun cross(a: Int, b: Int): Int = CROSSES[key(a, b)] ?: -1

    /** Human-readable recipe for the Shroomdex once a species is discovered (up to two ways). */
    fun recipeText(species: Int): String {
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
    )

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
        Achievement("Shroomologist", "Discover every species", 500),
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
