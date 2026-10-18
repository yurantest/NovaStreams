package com.novastreams.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novastreams.app.ui.theme.NovaColors

/** Локальные заглушки превью: пары приглушённых цветов. Настоящие кадры трансляций не используются. */
private val artPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFF2B1E5C) to Color(0xFF6B3FD1),
    Color(0xFF123A4A) to Color(0xFF2FA4A0),
    Color(0xFF4A1F3A) to Color(0xFFD1587F),
    Color(0xFF1F3A2A) to Color(0xFF58B36E),
    Color(0xFF3F2A12) to Color(0xFFD08A3A),
    Color(0xFF3A1A1A) to Color(0xFFC8553D),
    Color(0xFF1B2447) to Color(0xFF4F7BD9),
    Color(0xFF2F2F3A) to Color(0xFF8A8AA3),
)

fun artColors(seed: Int): Pair<Color, Color> =
    artPalettes[((seed % artPalettes.size) + artPalettes.size) % artPalettes.size]

/** Спокойная градиентная заглушка с мягкими геометрическими формами вместо кадра трансляции. */
@Composable
fun ArtBox(
    seed: Int,
    modifier: Modifier = Modifier,
    /** Адрес настоящей картинки; пока она грузится (или адреса нет) виден градиент-заглушка. */
    imageUrl: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val (dark, light) = artColors(seed)
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(dark, light.copy(alpha = 0.85f)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
            val r = size.minDimension
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = r * 0.55f,
                center = Offset(size.width * (0.2f + 0.1f * (seed % 4)), size.height * 0.15f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.12f),
                radius = r * 0.7f,
                center = Offset(size.width * 0.9f, size.height * 1.05f),
            )
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        content()
    }
}

@Composable
fun Avatar(name: String, seed: Int, size: Dp, modifier: Modifier = Modifier, imageUrl: String? = null) {
    val (dark, light) = artColors(seed)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(dark, light))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = NovaColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
