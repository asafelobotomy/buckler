package dev.solon.bucklerhome

/**
 * One adjustable setting of a screensaver. Each screensaver keeps its own value for each of its
 * [ScreensaverMode.options], so changing DVD's speed leaves the Maze's alone. [choices] are the
 * stored value and its label; [default] is used until one is picked.
 */
enum class SaverOption(val title: String, val choices: List<Pair<Int, String>>, val default: Int) {
    CLOCK("Clock", listOf(1 to "On", 0 to "Off"), 1),
    SPEED("Speed", listOf(50 to "Slow", 100 to "Normal", 180 to "Fast"), 100),
    CLOCK_SIZE("Clock size", listOf(48 to "Small", 64 to "Medium", 96 to "Large"), 64),
    DATE("Date under the clock", listOf(0 to "Off", 1 to "On"), 0),
    DRIFT("Moves every", listOf(15 to "15 s", 45 to "45 s", 120 to "2 min"), 45),
    LOGO_SIZE("Logo size", listOf(80 to "Small", 110 to "Medium", 160 to "Large"), 110),
    RECOLOUR("New colour on each edge", listOf(1 to "On", 0 to "Off"), 1),
    INTERVAL("Change every", listOf(10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min", 120 to "2 min"), 20),
    TRANSITION("Transition", listOf(0 to "Fade and slow zoom", 1 to "Fade", 2 to "Cut"), 0),
    CREDITS("Photo credits", listOf(1 to "On", 0 to "Off"), 1),
    ORDER("Order", listOf(0 to "Shuffle", 1 to "Oldest first", 2 to "Newest first"), 0),
    BLUR("Blurred artwork behind", listOf(1 to "On", 0 to "Off"), 1),
    MAZE_SIZE("Maze size", listOf(7 to "Small", 9 to "Medium", 13 to "Large"), 9),
    WALLS("Walls", listOf(0 to "Mixed", 1 to "Brick", 2 to "Stone", 3 to "Wood"), 0),
    PIPES("Pipes at once", listOf(3 to "3", 6 to "6", 9 to "9"), 9),
    SHAPES("Shapes", listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4"), 2),
    TRAIL("Trail", listOf(4 to "Short", 7 to "Medium", 12 to "Long"), 7),
    STARS("Stars", listOf(200 to "Fewer", 420 to "Normal", 800 to "More"), 420),
    STAR_COLOUR("Star colour", listOf(0 to "White", 1 to "Tinted"), 0),
    ;

    fun label(value: Int): String = choices.firstOrNull { it.first == value }?.second ?: "$value"
}

/** The options each screensaver offers, in the order its page shows them. */
val ScreensaverMode.options: List<SaverOption>
    get() = when (this) {
        ScreensaverMode.CLOCK -> listOf(SaverOption.CLOCK_SIZE, SaverOption.DATE, SaverOption.DRIFT)
        ScreensaverMode.DVD -> listOf(SaverOption.SPEED, SaverOption.LOGO_SIZE, SaverOption.RECOLOUR, SaverOption.CLOCK)
        ScreensaverMode.PHOTOS -> listOf(SaverOption.INTERVAL, SaverOption.TRANSITION, SaverOption.CREDITS, SaverOption.CLOCK)
        ScreensaverMode.UPLOADED -> listOf(SaverOption.INTERVAL, SaverOption.ORDER, SaverOption.TRANSITION, SaverOption.CLOCK)
        ScreensaverMode.NOW_PLAYING -> listOf(SaverOption.BLUR, SaverOption.CLOCK)
        ScreensaverMode.MAZE -> listOf(SaverOption.SPEED, SaverOption.MAZE_SIZE, SaverOption.WALLS, SaverOption.CLOCK)
        ScreensaverMode.PIPES -> listOf(SaverOption.SPEED, SaverOption.PIPES, SaverOption.CLOCK)
        ScreensaverMode.MYSTIFY -> listOf(SaverOption.SPEED, SaverOption.SHAPES, SaverOption.TRAIL, SaverOption.CLOCK)
        ScreensaverMode.STARFIELD -> listOf(SaverOption.SPEED, SaverOption.STARS, SaverOption.STAR_COLOUR, SaverOption.CLOCK)
    }
