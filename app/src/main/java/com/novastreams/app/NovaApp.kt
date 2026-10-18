package com.novastreams.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.auth.TokenStore
import com.novastreams.app.core.auth.TwitchAuthRepository
import com.novastreams.app.core.emotes.EmoteRepository
import com.novastreams.app.core.filtering.FilterPipeline
import com.novastreams.app.core.settings.LanguageSupport
import com.novastreams.app.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.novastreams.app.core.notifications.NotificationScheduler
import com.novastreams.app.core.stream.HlsStreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.novastreams.app.core.twitchapi.GqlTwitchRepository
import com.novastreams.app.core.twitchapi.HelixApi
import com.novastreams.app.core.twitchapi.HelixTwitchRepository
import com.novastreams.app.core.twitchapi.RoutingTwitchRepository
import com.novastreams.app.core.video.VideoProgressStore
import com.novastreams.app.core.video.VideoRepository

/** Простой контейнер зависимостей. Для учебного проекта DI-фреймворк не нужен. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsRepository(context.applicationContext)
    val auth: AuthRepository = TwitchAuthRepository(TokenStore(context.applicationContext), settings)
    val helixApi = HelixApi(auth)

    /** Ручное обновление экранов (меню ТВ, нижняя панель, клавиша «Меню»). */
    val refreshBus = com.novastreams.app.ui.components.RefreshBus()

    /** Смайлики чата: Twitch, 7TV, BTTV, FFZ. */
    val emotes = EmoteRepository(helixApi)

    /** Чат текущего канала на уровне приложения: одно подключение, не рвётся при смене экранов. */
    val chats = com.novastreams.app.core.chat.ChatHub(auth, emotes)

    /** Получение HLS-ссылки для своего плеера (неофициально). */
    val hlsStreams = HlsStreamRepository(settings)

    /** Единая точка данных: Helix после входа, GraphQL без входа. */
    val twitch = RoutingTwitchRepository(
        auth = auth,
        helix = HelixTwitchRepository(helixApi, auth),
        gql = GqlTwitchRepository(settings)
    )
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Скрытые пользователем категории (id → название); экраны обновляются, когда набор меняется. */
    val hiddenCategories: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = settings.hiddenCategories
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    val filters = FilterPipeline(
        listOf(
            com.novastreams.app.core.filtering.PassThroughFilter(),
            com.novastreams.app.core.filtering.HiddenCategoriesFilter { hiddenCategories.value.keys },
        )
    )

    /** Записи эфиров и клипы каналов (неофициально, через GraphQL). */
    val videos = VideoRepository(settings)

    /** Где остановился просмотр записи (на этом устройстве). */
    val videoProgress = VideoProgressStore(context.applicationContext)

    /**
     * Язык рекомендаций: язык телефона (если включено в настройках и язык поддерживается), иначе null — все эфиры.
     * Автоматический выбор: системный язык → ISO 639-1 (верхний регистр) → фильтр Twitch.
     */
    suspend fun recommendationLanguage(): String? =
        if (settings.languageRecommendations.first()) LanguageSupport.systemTwitchLanguage() else null
}

class NovaApp : Application(), coil.ImageLoaderFactory {
    /**
     * Загрузчик картинок, настроенный под слабые приставки: без плавного появления (лишняя анимация на каждой карточке),
     * 16-битные пиксели для картинок без прозрачности (вдвое меньше памяти; с прозрачностью Coil сам берёт 32 бита),
     * умеренный кэш в памяти и кэш на диске (картинки не качаются заново при каждом открытии экрана).
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .crossfade(false)
        // Свой клиент: картинки чата (смайлики) идут через «кран» скорости, чтобы не отнимать канал у видео;
        // параллельных загрузок немного (слабая приставка), таймаут общего вызова снят — очередь за краном может быть долгой.
        .okHttpClient {
            com.novastreams.app.core.network.Http.client.newBuilder()
                .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
                .build()
        }
        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            coil.disk.DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Поднять фоновую проверку эфиров, если пользователь уже включал уведомления.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val on = container.settings.notificationsEnabled.first()
            NotificationScheduler.apply(this@NovaApp, on)
        }
    }
}

fun Context.appContainer(): AppContainer = (applicationContext as NovaApp).container

/** Фабрика ViewModel без параметров-конструкторов Android. */
@Suppress("UNCHECKED_CAST")
fun <T : ViewModel> vmFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }
