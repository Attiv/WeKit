package dev.ujhhgtg.wekit.features.items.beautify

import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.wekit.preferences.WePrefs

/**
 * Per-side colors of a built-in bubble theme. Values are color strings in the same format the
 * color fields accept, so picking a theme simply prefills the editable fields and the user can
 * still tweak each color afterwards.
 */
data class BubbleThemeColors(
    val foregroundLight: String,
    val foregroundDark: String,
    val backgroundLight: String,
    val backgroundDark: String,
) {
    fun foreground(darkMode: Boolean): String = if (darkMode) foregroundDark else foregroundLight

    fun background(darkMode: Boolean): String = if (darkMode) backgroundDark else backgroundLight
}

/**
 * Built-in bubble themes selectable in [CustomMessageBubbles]. Besides the bubble colors and the
 * corner radius, each theme carries a matching chat background gradient which
 * [ApplyGlobalBackground] renders when the user has not picked their own background image (and has
 * not turned the theme background off there).
 */
enum class BubbleTheme(
    val id: String,
    val title: String,
    val self: BubbleThemeColors,
    val other: BubbleThemeColors,
    val cornerRadiusDp: Float,
    val tail: BubbleTail,
    private val backgroundLight: List<String>,
    private val backgroundDark: List<String>,
) {
    NONE(
        "none", "无 (自定义)",
        self = BubbleThemeColors("", "", "", ""),
        other = BubbleThemeColors("", "", "", ""),
        cornerRadiusDp = 18f,
        tail = BubbleTail.NONE,
        backgroundLight = emptyList(),
        backgroundDark = emptyList(),
    ),

    TELEGRAM(
        "telegram", "Telegram",
        self = BubbleThemeColors("#FF000000", "#FFFFFFFF", "#FFEFFDDE", "#FF2B5278"),
        other = BubbleThemeColors("#FF000000", "#FFFFFFFF", "#FFFFFFFF", "#FF182533"),
        cornerRadiusDp = 18f,
        tail = BubbleTail.BOTTOM_CURVE,
        backgroundLight = listOf("#FFA9C7E5", "#FFD3E4F1"),
        backgroundDark = listOf("#FF0E1621", "#FF1C2733"),
    ),

    IMESSAGE(
        "imessage", "iMessage",
        self = BubbleThemeColors("#FFFFFFFF", "#FFFFFFFF", "#FF007AFF", "#FF0A84FF"),
        other = BubbleThemeColors("#FF000000", "#FFFFFFFF", "#FFE9E9EB", "#FF3A3A3C"),
        cornerRadiusDp = 18f,
        tail = BubbleTail.BOTTOM_CURVE,
        backgroundLight = listOf("#FFFFFFFF", "#FFF2F2F7"),
        backgroundDark = listOf("#FF000000", "#FF1C1C1E"),
    ),

    WHATSAPP(
        "whatsapp", "WhatsApp",
        self = BubbleThemeColors("#FF111B21", "#FFE9EDEF", "#FFD9FDD3", "#FF005C4B"),
        other = BubbleThemeColors("#FF111B21", "#FFE9EDEF", "#FFFFFFFF", "#FF202C33"),
        cornerRadiusDp = 12f,
        tail = BubbleTail.TOP_TRIANGLE,
        backgroundLight = listOf("#FFEFEAE2", "#FFDDD3C8"),
        backgroundDark = listOf("#FF0B141A", "#FF15202A"),
    ),

    MIDNIGHT(
        "midnight", "暗夜紫",
        self = BubbleThemeColors("#FFFFFFFF", "#FFFFFFFF", "#FF7C4DFF", "#FF6A3DE8"),
        other = BubbleThemeColors("#FF241934", "#FFEDE7F6", "#FFF0EAFB", "#FF2A2139"),
        cornerRadiusDp = 16f,
        tail = BubbleTail.NONE,
        backgroundLight = listOf("#FFE8DFF7", "#FFC9B8EC"),
        backgroundDark = listOf("#FF16102A", "#FF2D1B4E"),
    ),

    PEACH(
        "peach", "蜜桃粉",
        self = BubbleThemeColors("#FFFFFFFF", "#FFFFFFFF", "#FFF06292", "#FFAD1457"),
        other = BubbleThemeColors("#FF432A33", "#FFF8E1E7", "#FFFFFFFF", "#FF3A2A33"),
        cornerRadiusDp = 20f,
        tail = BubbleTail.NONE,
        backgroundLight = listOf("#FFFFE3E8", "#FFFFC9DA"),
        backgroundDark = listOf("#FF2B1A22", "#FF3D2430"),
    );

    fun colorsFor(isSelfSender: Boolean): BubbleThemeColors = if (isSelfSender) self else other

    /**
     * The matching chat wallpaper as a vertical gradient, or null for [NONE]. A drawable with no
     * intrinsic size fills the overlay ImageView, so no bitmap file needs to be shipped or decoded.
     */
    fun backgroundDrawable(darkMode: Boolean): GradientDrawable? {
        val colors = if (darkMode) backgroundDark else backgroundLight
        if (colors.isEmpty()) return null
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            colors.map { it.toColorInt() }.toIntArray(),
        )
    }

    companion object {
        /** Pref key shared by [CustomMessageBubbles] (writer) and [ApplyGlobalBackground] (reader). */
        const val PREF_KEY = "custom_bubbles_theme"

        val current: BubbleTheme
            get() = fromId(WePrefs.getStringOrDef(PREF_KEY, NONE.id))

        fun fromId(id: String?): BubbleTheme = entries.firstOrNull { it.id == id } ?: NONE
    }
}
