package com.novastreams.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Постоянные цвета поверхностей и текста NovaStreams Next. Акцент на них не влияет. */
object NovaColors {
    val Background = Color(0xFF101014)
    val Card = Color(0xFF19191F)
    val Surface = Color(0xFF222229)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFA3A3AE)
    val Live = Color(0xFFE5484D)
    val Outline = Color(0xFF2E2E37)
}

@Immutable
data class NovaExtraColors(
    val accent: Color,
    /** Читаемый цвет текста/иконок поверх акцента. */
    val onAccent: Color,
    /** Слабая заливка акцентом для активных состояний. */
    val accentSoft: Color,
)

val LocalNovaColors = staticCompositionLocalOf {
    NovaExtraColors(Color(0xFF9147FF), Color.White, Color(0x339147FF))
}

val LocalIsTv = staticCompositionLocalOf { false }

/** Разбор строки вида #RRGGBB. Возвращает null, если формат неверный. */
fun parseHexColor(input: String): Color? {
    val text = input.trim().removePrefix("#")
    if (text.length != 6) return null
    if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = text.toLong(16)
    return Color((0xFF000000L or value).toInt())
}

fun colorToHex(color: Color): String {
    val r = (color.red * 255f + 0.5f).toInt()
    val g = (color.green * 255f + 0.5f).toInt()
    val b = (color.blue * 255f + 0.5f).toInt()
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/** Слишком тёмные акценты осветляются, чтобы оставаться видимыми на тёмном фоне. */
fun ensureVisibleAccent(c: Color): Color {
    if (luminance(c) >= 0.18f) return c
    val t = 0.45f
    return Color(
        red = c.red + (1f - c.red) * t,
        green = c.green + (1f - c.green) * t,
        blue = c.blue + (1f - c.blue) * t,
        alpha = 1f,
    )
}

private val NovaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private fun novaTypography(tv: Boolean): Typography {
    val k = if (tv) 1.25f else 1f
    fun s(size: Float, weight: FontWeight, line: Float) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = (size * k).sp,
        lineHeight = (line * k).sp,
    )
    return Typography(
        headlineLarge = s(30f, FontWeight.Bold, 36f),
        headlineMedium = s(24f, FontWeight.Bold, 30f),
        titleLarge = s(20f, FontWeight.SemiBold, 26f),
        titleMedium = s(16f, FontWeight.SemiBold, 22f),
        bodyLarge = s(16f, FontWeight.Normal, 22f),
        bodyMedium = s(14f, FontWeight.Normal, 20f),
        bodySmall = s(12f, FontWeight.Normal, 16f),
        labelLarge = s(14f, FontWeight.SemiBold, 18f),
        labelSmall = s(11f, FontWeight.Bold, 14f),
    )
}

@Composable
fun NovaTheme(
    accent: Color,
    isTv: Boolean,
    content: @Composable () -> Unit,
) {
    val visible = ensureVisibleAccent(accent)
    val onAccent = if (luminance(visible) > 0.55f) Color(0xFF101014) else Color.White
    val extra = NovaExtraColors(
        accent = visible,
        onAccent = onAccent,
        accentSoft = visible.copy(alpha = 0.18f),
    )
    val scheme = darkColorScheme(
        primary = visible,
        onPrimary = onAccent,
        secondary = visible,
        background = NovaColors.Background,
        onBackground = NovaColors.TextPrimary,
        surface = NovaColors.Card,
        onSurface = NovaColors.TextPrimary,
        surfaceVariant = NovaColors.Surface,
        onSurfaceVariant = NovaColors.TextSecondary,
        outline = NovaColors.Outline,
        error = NovaColors.Live,
    )
    CompositionLocalProvider(
        LocalNovaColors provides extra,
        LocalIsTv provides isTv,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = novaTypography(isTv),
            shapes = NovaShapes,
            content = content,
        )
    }
}

val accentPresets: List<Pair<String, Color>> = listOf(
    "Фиолетовый" to Color(0xFF9147FF),
    "Индиго" to Color(0xFF5B6CFF),
    "Бирюзовый" to Color(0xFF1FC8B4),
    "Зелёный" to Color(0xFF3DDC84),
    "Янтарный" to Color(0xFFFFB020),
    "Коралловый" to Color(0xFFFF6B6B),
    "Розовый" to Color(0xFFFF5CAA),
)
