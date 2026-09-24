package com.example.floatingkit

/** Generic content unit — replaces LiveFootball_II's LiveMatch. */
data class FloatingItem(
    val id: Long,
    val title: String,      // e.g. "1 - 0" or "$182.44"
    val status: String,     // e.g. "72'" or "+1.2%"
    val left: String = "",  // emoji / letter shown left  (was home logo)
    val right: String = "", // emoji / letter shown right (was away logo)
    val detail: String = "",// shown when expanded on tap
    val badge: String? = null // e.g. "GOAL 72'" — triggers pulse when non-null
)

data class FloatingTheme(
    val bg: Int,
    val border: Int,
    val title: Int,
    val status: Int,
    val close: Int
)

object FloatingThemes {
    const val CLASSIC = "classic"
    const val DARK = "dark"
    const val GREEN = "green"
    const val MIDNIGHT = "midnight"
    const val CRIMSON = "crimson"
    const val OCEAN = "ocean"
    const val PURPLE = "purple"
    const val AMBER = "amber"

    // Same ARGB values as LiveFootball_II FloatingScoreService.THEMES
    val ALL = mapOf(
        CLASSIC to FloatingTheme(0xE6FFFFFF.toInt(), 0xFFB0B0B0.toInt(), 0xFF212121.toInt(), 0xFF616161.toInt(), 0xFF616161.toInt()),
        DARK to FloatingTheme(0xE6212121.toInt(), 0xFF616161.toInt(), 0xFFFFFFFF.toInt(), 0xFFB0B0B0.toInt(), 0xFFB0B0B0.toInt()),
        GREEN to FloatingTheme(0xE61B5E20.toInt(), 0xFF4CAF50.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8E6C9.toInt(), 0xFFC8E6C9.toInt()),
        MIDNIGHT to FloatingTheme(0xE60D1B2A.toInt(), 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(), 0xFF90CAF9.toInt(), 0xFF90CAF9.toInt()),
        CRIMSON to FloatingTheme(0xE6B71C1C.toInt(), 0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFCDD2.toInt(), 0xFFFFCDD2.toInt()),
        OCEAN to FloatingTheme(0xE6006064.toInt(), 0xFF00ACC1.toInt(), 0xFFFFFFFF.toInt(), 0xFFB2EBF2.toInt(), 0xFFB2EBF2.toInt()),
        PURPLE to FloatingTheme(0xE64A148C.toInt(), 0xFF7B1FA2.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1BEE7.toInt(), 0xFFE1BEE7.toInt()),
        AMBER to FloatingTheme(0xE6FF6F00.toInt(), 0xFFFFB300.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFF8E1.toInt(), 0xFFFFF8E1.toInt())
    )

    fun get(key: String?): FloatingTheme = ALL[key] ?: ALL[CLASSIC]!!
    val names: List<String> get() = ALL.keys.toList()
}
