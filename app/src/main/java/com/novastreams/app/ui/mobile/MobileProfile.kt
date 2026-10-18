package com.novastreams.app.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novastreams.app.BuildConfig
import com.novastreams.app.appContainer
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.feature.auth.AuthViewModel
import com.novastreams.app.feature.settings.AccentEditor
import com.novastreams.app.feature.settings.AccountSection
import com.novastreams.app.feature.settings.AdsSection
import com.novastreams.app.feature.settings.RecommendationsSection
import com.novastreams.app.feature.settings.PlayerLogSection
import com.novastreams.app.feature.settings.DataSourceSection
import com.novastreams.app.feature.settings.DefaultQualitySection
import com.novastreams.app.feature.settings.HiddenCategoriesSection
import com.novastreams.app.feature.settings.StreamSourceSection
import com.novastreams.app.feature.settings.VideoSurfaceSection
import com.novastreams.app.feature.settings.VideoFillSection
import com.novastreams.app.feature.settings.WebChatNavSection
import com.novastreams.app.feature.settings.NotificationsSection
import com.novastreams.app.feature.settings.SettingsViewModel
import com.novastreams.app.ui.components.Avatar
import com.novastreams.app.ui.components.SectionTitle
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.vmFactory

/** Карточка-раздел профиля: общий фон и отступы. */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NovaColors.Card)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun MobileProfileScreen(onOpenChannel: (String) -> Unit, onLogin: () -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(container) })
    val authVm: AuthViewModel = viewModel(factory = vmFactory { AuthViewModel(container) })
    val auth by authVm.authState.collectAsState()
    val hasClientId by vm.hasOwnClientId.collectAsState()
    val favorites by vm.favoriteChannels.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = "Профиль",
                style = MaterialTheme.typography.headlineMedium,
                color = NovaColors.TextPrimary,
            )
        }
        item {
            SectionCard {
                AccountSection(
                    auth = auth,
                    hasClientId = hasClientId,
                    onLogin = onLogin,
                    onLogout = authVm::logout,
                )
            }
        }
        item {
            SectionCard { NotificationsSection(vm, signedIn = auth is AuthState.SignedIn) }
        }
        item {
            Column {
                SectionTitle("Избранное в приложении")
                Spacer(Modifier.height(8.dp))
                if (favorites.isEmpty()) {
                    Text(
                        text = "Пока пусто. Добавляйте каналы на странице канала или в разделе «Каналы». " +
                            "Подписаться на канал в самом Twitch из стороннего приложения нельзя: у Twitch нет такой функции API.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaColors.TextSecondary,
                    )
                } else {
                    favorites.forEach { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .novaClickable(RoundedCornerShape(12.dp), focusScale = 1.02f) { onOpenChannel(channel.id) }
                                .clip(RoundedCornerShape(12.dp))
                                .background(NovaColors.Card)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(channel.displayName, channel.artSeed, 40.dp, imageUrl = channel.avatarUrl)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = channel.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = NovaColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
        item { SectionCard { AccentEditor(vm) } }
        item { SectionCard { RecommendationsSection(vm) } }
        item { SectionCard { AdsSection() } }
        item { SectionCard { StreamSourceSection(vm) } }
        item { SectionCard { DefaultQualitySection(vm) } }
        item { SectionCard { VideoSurfaceSection(vm) } }
        item { SectionCard { VideoFillSection(vm) } }
        item { SectionCard { WebChatNavSection(vm) } }
        item { SectionCard { HiddenCategoriesSection(vm) } }
        item { SectionCard { DataSourceSection(vm) } }
        item { SectionCard { PlayerLogSection(vm) } }
        item {
            Column {
                SectionTitle("О приложении")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "NovaStreams ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextPrimary,
                )
                Text(
                    text = "Неофициальный клиент Twitch. Не связан с Twitch Interactive, Inc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
            }
        }
    }
}
