package com.novastreams.app.core.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Один поток с минимальным приоритетом для фоновой работы чата (разбор сообщений IRC, каталог смайликов).
 * На слабой приставке чат не должен отнимать процессор у декодера видео.
 */
object ChatDispatcher {
    val low: CoroutineDispatcher = Executors.newFixedThreadPool(1) { r ->
        Thread(r, "nova-chat").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}
