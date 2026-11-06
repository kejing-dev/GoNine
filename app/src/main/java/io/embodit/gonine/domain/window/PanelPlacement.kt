package io.embodit.gonine.domain.window

/**
 * Where the control window opens (profile D44 corrected 2026-11-05): a Shared Space app only *reopens* its panel;
 * every entry — launch, the S10 pinch on the volume, [重开控制窗] in Settings and a tap on the seat card — puts it
 * `Placement.right(体积)`. Pure Kotlin so the rule is unit-tested; Main.kt maps [Kind] onto the SDK Placement.
 */
object PanelPlacement {
    enum class Entry(val why: String) { LAUNCH("launch"), PINCH_VOLUME("tap"), SETTINGS_BUTTON("recall"), SEAT_CARD("recall") }

    enum class Kind { RIGHT_OF_VOLUME, NONE }

    /** The open reason [WindowCtl] logs for an entry (`WINDOW open why=…`). */
    fun why(entry: Entry): String = entry.why

    /** Placement for an open with reason [why] when the board volume [volumePresent] is known to the system. */
    fun kindFor(why: String, volumePresent: Boolean): Kind = if (volumePresent) Kind.RIGHT_OF_VOLUME else Kind.NONE

    /**
     * The window's assumed pose for `Placement.right(volume)` (design spatialLayout.controlWindow): its left edge
     * x = volume half width + system gap, centred at the volume's depth, [widthM] wide, in scene metres (origin on
     * the volume floor centre, +Z toward the player). The real pose is the system's; the test checks this model.
     */
    data class Rect(val left: Float, val right: Float, val bottom: Float, val top: Float, val z: Float)

    fun rightOfVolume(volumeHalfW: Float = 0.40f, gap: Float = 0.05f, widthM: Float = 0.45f, heightM: Float = 0.48f, volumeHalfH: Float = 0.18f) =
        Rect(volumeHalfW + gap, volumeHalfW + gap + widthM, volumeHalfH - heightM / 2, volumeHalfH + heightM / 2, 0f)
}
