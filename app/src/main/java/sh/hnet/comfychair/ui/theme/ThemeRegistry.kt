package sh.hnet.comfychair.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Five fixed light themes. Theme identity and display metadata live here; all actual
 * color values are owned by [ThemeRegistry] so the picker preview and runtime scheme
 * cannot drift apart.
 */
enum class AppTheme(
    val id: String,
    val number: String,
    val displayNameZh: String,
    val displayNameEn: String
) {
    OBSIDIAN_WHITE(
        id = "obsidian_white",
        number = "01",
        displayNameZh = "曜石白",
        displayNameEn = "Obsidian White"
    ),
    CHAMPAGNE_WHITE(
        id = "champagne_white",
        number = "05",
        displayNameZh = "香槟白",
        displayNameEn = "Champagne White"
    ),
    GLACIER_BLUE(
        id = "glacier_blue",
        number = "06",
        displayNameZh = "冰川蓝",
        displayNameEn = "Glacier Blue"
    ),
    GRAPHITE_LIME(
        id = "graphite_lime",
        number = "07",
        displayNameZh = "石墨青柠",
        displayNameEn = "Graphite Lime"
    ),
    AURORA_PURPLE(
        id = "aurora_purple",
        number = "08",
        displayNameZh = "极光紫",
        displayNameEn = "Aurora Purple"
    );

    companion object {
        val DEFAULT = GLACIER_BLUE
        const val DYNAMIC_ID = "dynamic"
        private const val LEGACY_OBSIDIAN_SILVER_ID = "obsidian_silver"

        /** Old 07 installations migrate to the replacement 07 theme without losing selection. */
        fun fromId(id: String?): AppTheme = when (id) {
            LEGACY_OBSIDIAN_SILVER_ID -> GRAPHITE_LIME
            else -> entries.firstOrNull { it.id == id } ?: DEFAULT
        }

        fun normalizeId(id: String?): String = when (id) {
            LEGACY_OBSIDIAN_SILVER_ID -> GRAPHITE_LIME.id
            DYNAMIC_ID -> DYNAMIC_ID
            else -> entries.firstOrNull { it.id == id }?.id ?: DEFAULT.id
        }

        fun isDynamic(id: String?): Boolean = id == DYNAMIC_ID
    }
}

/**
 * Single source of truth for fixed theme colors.
 *
 * All themes share the same neutral canvas and surfaces. Only the accent roles vary.
 * Material 3 component states (especially selected chips) use the theme's primary
 * container rather than a generic grey secondary container.
 */
object ThemeRegistry {
    private val pageBackground = Color(0xFFF4F6F8)
    private val cardSurface = Color(0xFFFFFFFF)
    private val subtleSurface = Color(0xFFF7F8FA)
    private val containerLow = Color(0xFFF4F6F8)
    private val container = Color(0xFFEFF2F5)
    private val containerHigh = Color(0xFFE9EDF2)
    private val raisedSurface = Color(0xFFE3E8EE)
    private val onSurface = Color(0xFF20242B)
    // A little stronger than the previous neutral grey so small labels remain readable.
    // 次级文字：原来 #5F6B78 在白底 5.44:1、行底 4.84:1，刚过门槛看着发虚。
    // 压到 #4A5462 后白底 7.68:1，仍比主文字浅一倍多，层级还在。
    private val onSurfaceVariant = Color(0xFF4A5462)
    private val outline = Color(0xFFC8D0D9)
    private val outlineVariant = Color(0xFFE2E7EC)

    data class Definition(
        val primary: Color,
        val onPrimary: Color,
        val primaryContainer: Color,
        val onPrimaryContainer: Color,
        val inversePrimary: Color,
        val background: Color = pageBackground,
        val surface: Color = cardSurface,
        val surfaceSubtle: Color = subtleSurface,
        val surfaceContainerLow: Color = containerLow,
        val surfaceContainer: Color = container,
        val surfaceContainerHigh: Color = containerHigh,
        val surfaceContainerHighest: Color = raisedSurface,
        val onSurface: Color = ThemeRegistry.onSurface,
        val onSurfaceVariant: Color = ThemeRegistry.onSurfaceVariant,
        val outline: Color = ThemeRegistry.outline,
        val outlineVariant: Color = ThemeRegistry.outlineVariant
    )

    private val definitions = mapOf(
        AppTheme.OBSIDIAN_WHITE to Definition(
            primary = Color(0xFF292E36),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE9EDF1),
            onPrimaryContainer = Color(0xFF222831),
            inversePrimary = Color(0xFFC8D0D9)
        ),
        AppTheme.CHAMPAGNE_WHITE to Definition(
            // A lighter champagne-gold replaces the previous ochre cast; the button uses a
            // dark foreground, while text links use onPrimaryContainer for readable contrast.
            primary = Color(0xFFC49A58),
            onPrimary = Color(0xFF292115),
            primaryContainer = Color(0xFFF7EBDD),
            onPrimaryContainer = Color(0xFF80531E),
            inversePrimary = Color(0xFFE4C18F)
        ),
        AppTheme.GLACIER_BLUE to Definition(
            // Slightly strengthened from the preview swatch to keep white button text accessible.
            primary = Color(0xFF1174DF),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE5F1FF),
            onPrimaryContainer = Color(0xFF0759B0),
            inversePrimary = Color(0xFF9CCBFF)
        ),
        AppTheme.GRAPHITE_LIME to Definition(
            primary = Color(0xFFA5E635),
            onPrimary = Color(0xFF1D2710),
            primaryContainer = Color(0xFFEFF9D9),
            onPrimaryContainer = Color(0xFF3C5C12),
            inversePrimary = Color(0xFFC8F27D)
        ),
        AppTheme.AURORA_PURPLE to Definition(
            primary = Color(0xFF8251E8),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF0E9FF),
            onPrimaryContainer = Color(0xFF5630B1),
            inversePrimary = Color(0xFFC4B1F5)
        )
    )

    /** Returns the exact palette also used by the appearance-page preview. */
    fun definition(theme: AppTheme): Definition = definitions.getValue(theme)

    fun getColorScheme(theme: AppTheme): ColorScheme = compose(definition(theme))

    private val success = Color(0xFF166534)
    private val successContainer = Color(0xFFDCFCE7)
    private val onSuccessContainer = Color(0xFF14532D)

    private fun compose(p: Definition): ColorScheme = lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        inversePrimary = p.inversePrimary,
        secondary = p.onSurfaceVariant,
        onSecondary = Color(0xFFFFFFFF),
        // Selected chips and secondary selection surfaces follow the active theme accent.
        secondaryContainer = p.primaryContainer,
        onSecondaryContainer = p.onPrimaryContainer,
        tertiary = success,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = successContainer,
        onTertiaryContainer = onSuccessContainer,
        background = p.background,
        onBackground = p.onSurface,
        surface = p.surface,
        onSurface = p.onSurface,
        surfaceVariant = p.surfaceSubtle,
        onSurfaceVariant = p.onSurfaceVariant,
        surfaceTint = p.primary,
        inverseSurface = p.onSurface,
        inverseOnSurface = Color(0xFFF4F6F8),
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surfaceContainerLow,
        surfaceContainer = p.surfaceContainer,
        surfaceContainerHigh = p.surfaceContainerHigh,
        surfaceContainerHighest = p.surfaceContainerHighest,
        outline = p.outline,
        outlineVariant = p.outlineVariant,
        scrim = Color(0xFF000000),
        error = Color(0xFFB42318),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFEE4E2),
        onErrorContainer = Color(0xFF7A1B14)
    )
}
