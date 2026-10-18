package com.novastreams.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import com.novastreams.app.appContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.Stream
import com.novastreams.app.core.model.formatViewers
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NovaColors.Live)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}

@Composable
fun ViewersChip(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Color(0xB3101014))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(NovaColors.Live)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = formatViewers(count),
            style = MaterialTheme.typography.labelLarge,
            color = NovaColors.TextPrimary,
        )
    }
}

/**
 * Карточка эфира: превью сверху (LIVE и зрители поверх него),
 * текстовый блок под превью — название канала, название эфира, категория.
 */
@Composable
fun StreamCard(
    stream: Stream,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
) {
    val shape = RoundedCornerShape(corner)
    // Долгое нажатие на карточку открывает страницу стримера (записи и клипы).
    val openChannelPage = LocalChannelPageOpener.current
    val container = LocalContext.current.appContainer()
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .width(width)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    // Пока пользователь «стоит» на карточке — уже тянем токен и master-плейлист.
                    val login = stream.channel.login
                    if (login.isNotBlank()) scope.launch { container.hlsStreams.prefetch(login) }
                }
            }
            .novaClickable(shape, onLongClick = { openChannelPage(stream.channel.id) }, onClick = onClick)
            .clip(shape)
            .background(NovaColors.Card),
    ) {
        ArtBox(
            seed = stream.artSeed,
            imageUrl = stream.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            ViewersChip(stream.viewers, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(stream.channel.displayName, stream.channel.artSeed, 34.dp, imageUrl = stream.channel.avatarUrl)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stream.channel.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = NovaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stream.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stream.categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CategoryCard(
    category: Category,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .width(width)
            .novaClickable(shape, onClick = onClick)
            .clip(shape),
    ) {
        ArtBox(
            seed = category.artSeed,
            imageUrl = category.boxArtUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape),
        ) {
            // Название поверх заглушки; у настоящей обложки название уже нарисовано, поэтому пишем его под ней.
            if (category.boxArtUrl.isNullOrBlank()) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                )
            }
        }
        // Число зрителей Twitch отдаёт не везде (-1 = неизвестно). Тогда показываем название категории.
        val caption = when {
            category.viewers >= 0 -> formatViewers(category.viewers) + " зрителей"
            !category.boxArtUrl.isNullOrBlank() -> category.name
            else -> ""
        }
        if (caption.isNotEmpty()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                // Отступы со всех сторон: карточка обрезана скруглением и рисует рамку фокуса, без них края букв съедались.
                modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = NovaColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
fun DemoBanner(modifier: Modifier = Modifier, reason: String? = null) {
    val accent = LocalNovaColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.accentSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Демо-режим: данные вымышленные" + (reason?.let { ". Причина: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextPrimary,
        )
    }
}

/** Небольшая подсказка в рамке (например, «Войдите, чтобы видеть подписки»). */
@Composable
fun HintBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NovaColors.TextSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NovaColors.Card)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalNovaColors.current.accent, strokeWidth = 3.dp)
    }
}

@Composable
fun MessageState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NovaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                AccentButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val colors = LocalNovaColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .novaClickable(shape, focusScale = 1.05f, onClick = onClick)
            .clip(shape)
            .background(if (filled) colors.accent else NovaColors.Surface)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) colors.onAccent else NovaColors.TextPrimary,
        )
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp)
