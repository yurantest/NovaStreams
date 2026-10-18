package com.novastreams.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.novastreams.app.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Шина ручного обновления. Приложение (ТВ-меню, нижняя панель телефона, клавиша «Меню») кладёт сюда маршрут
 * текущего экрана, а экран с этим маршрутом ([ScreenRefresh]) обновляет свои данные.
 */
class RefreshBus {
    val requests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun request(route: String) {
        requests.tryEmit(route)
    }
}

/**
 * Автообновление данных экрана:
 *  — при возврате на экран и когда приставка проснулась (ON_RESUME) — `onRefresh(false)`: экран сам решает,
 *    устарели ли данные (см. `refreshIfStale` во ViewModel);
 *  — раз в [intervalMs], пока экран открыт, — то же самое;
 *  — по ручной просьбе (повторное нажатие на пункт меню, долгое OK на нём, «Меню» на пульте) — `onRefresh(true)`.
 * Работает только пока экран на виду: фоновые экраны сеть не нагружают.
 */
@Composable
fun ScreenRefresh(route: String, intervalMs: Long = 3 * 60_000L, onRefresh: (force: Boolean) -> Unit) {
    val container = LocalContext.current.appContainer()
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onRefresh)

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current(false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(intervalMs)
                current(false)
            }
        }
    }
    LaunchedEffect(route) {
        container.refreshBus.requests.collect { if (it == route) current(true) }
    }
}

/** Давность данных, после которой экран при возврате обновляется сам. */
const val STALE_AFTER_MS = 3 * 60_000L
