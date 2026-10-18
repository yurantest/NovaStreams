package com.novastreams.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novastreams.app.core.model.Stream
import com.novastreams.app.ui.components.ArtBox
import com.novastreams.app.ui.components.LiveBadge
import com.novastreams.app.ui.theme.NovaColors

/**
 * Честная заглушка плеера: видео не воспроизводится, картинка — локальная заливка, а не кадр эфира.
 * Используется и как «превью» на странице канала, и как полноэкранный плеер.
 */
@Composable
fun PlayerPlaceholder(
    stream: Stream?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    title: String = "Воспроизведение пока не подключено",
    subtitle: String = "Это демо-версия: видеопоток Twitch не загружается.",
) {
    Box(modifier = modifier.background(NovaColors.Background)) {
        ArtBox(seed = stream?.artSeed ?: 7, imageUrl = stream?.thumbnailUrl, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x66000000)),
        )
        if (stream != null) {
            LiveBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                color = NovaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
