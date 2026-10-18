package com.novastreams.app.core.chat

import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.emotes.EmoteIndex
import com.novastreams.app.core.emotes.EmoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Хранит чат текущего канала на уровне приложения, а не экрана: подключение к IRC одно на канал и живёт,
 * пока вы смотрите канал, даже если панель чата свёрнута, вы вышли из плеера на страницу канала и вернулись.
 * При уходе с канала соединение держится ещё [IDLE_CLOSE_MS] (вдруг вернётесь), потом закрывается.
 * Чат другого канала закрывает предыдущий.
 */
class ChatHub(
    private val auth: AuthRepository,
    private val emotes: EmoteRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + ChatDispatcher.low)
    private val lock = Any()
    private var current: Entry? = null

    class Entry internal constructor(
        val channelId: String,
        val login: String,
        val session: ChatSession,
    ) {
        /** Смайлики канала; пока грузятся — пусто. */
        val emotes = MutableStateFlow(EmoteIndex.Empty)
        internal var refs = 0
        internal var idleJob: Job? = null
        internal var emotesRequested = false
    }

    /** Берёт чат канала (создаёт и подключает, если его ещё нет). Каждому acquire — свой [release]. */
    fun acquire(channelId: String, login: String): Entry {
        synchronized(lock) {
            val cur = current
            if (cur != null && cur.channelId == channelId && !cur.session.isClosed) {
                cur.idleJob?.cancel()
                cur.idleJob = null
                cur.refs++
                return cur
            }
            cur?.let {
                it.idleJob?.cancel()
                it.session.close()
            }
            val entry = Entry(channelId, login, ChatSession(auth, scope, login))
            entry.refs = 1
            entry.session.start()
            current = entry
            return entry
        }
    }

    /** Экран больше не смотрит чат. Соединение не рвём сразу: вернётесь — чат уже на месте. */
    fun release(entry: Entry) {
        synchronized(lock) {
            if (entry.refs > 0) entry.refs--
            if (entry.refs > 0 || current !== entry) return
            entry.idleJob?.cancel()
            entry.idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                synchronized(lock) {
                    if (current === entry && entry.refs == 0) {
                        entry.session.close()
                        current = null
                    }
                }
            }
        }
    }

    /** Смайлики канала грузятся один раз на запись. */
    fun ensureEmotes(entry: Entry) {
        synchronized(lock) {
            if (entry.emotesRequested && !entry.emotes.value.isEmpty) return
            if (entry.emotesRequested) return
            entry.emotesRequested = true
        }
        scope.launch {
            try {
                entry.emotes.value = emotes.load(entry.channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Смайлики не загрузились — чат покажет слова как есть; следующий вход повторит попытку.
                synchronized(lock) { entry.emotesRequested = false }
            }
        }
    }

    private companion object {
        const val IDLE_CLOSE_MS = 10 * 60_000L
    }
}
