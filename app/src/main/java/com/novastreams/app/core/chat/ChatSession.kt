package com.novastreams.app.core.chat

import com.novastreams.app.core.auth.AuthRepository
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.model.ChatMessage
import com.novastreams.app.core.model.EmoteSpan
import com.novastreams.app.core.model.seedOf
import com.novastreams.app.core.network.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Состояние подключения к чату. */
sealed interface ChatStatus {
    data object Connecting : ChatStatus
    /** [canSend] — можно ли писать (нужен вход через Twitch). */
    data class Connected(val canSend: Boolean) : ChatStatus
    data class Failed(val message: String) : ChatStatus
    /** Показан демонстрационный чат, к Twitch мы не подключались. */
    data object Demo : ChatStatus
}

/**
 * Чат одного канала через Twitch IRC поверх WebSocket (wss://irc-ws.chat.twitch.tv:443).
 * Вошли — подключаемся под своим аккаунтом (можно читать и писать).
 * Не вошли — пробуем анонимное чтение (ник justinfan…); это в документации не описано и может не работать.
 * Смайлики Twitch приходят в теге emotes (номер:позиции) — они разбираются здесь и рисуются картинками в панели чата.
 */
class ChatSession(
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
    channelLogin: String,
) {
    private val channel = channelLogin.lowercase()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow<ChatStatus>(ChatStatus.Connecting)
    val status: StateFlow<ChatStatus> = _status

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var authed = false
    @Volatile private var attempts = 0
    @Volatile private var connectionGeneration = 0
    /** История подгружается один раз при старте, не при каждом reconnect. */
    @Volatile private var historyLoaded = false
    @Volatile private var lastSendMs = 0L
    private var displayName = ""
    private val nextId = AtomicLong(1)
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<ChatMessage>()
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    val isClosed: Boolean get() = closed

    @Volatile private var authWatcher: kotlinx.coroutines.Job? = null

    /** Запускает подключение. Вызывать один раз. */
    fun start() {
        // IRC и история параллельно: история не должна блокировать статус «Чат подключён».
        scope.launch { open() }
        scope.launch {
            if (!historyLoaded) {
                historyLoaded = true
                // Сервис истории бывает медленным (особенно через VPN/прокси): пробуем несколько раз.
                for (attempt in 1..3) {
                    if (closed || loadRecentHistory()) break
                    delay(2_000L * attempt)
                }
            }
        }
        // Вошли или вышли, пока чат уже открыт, — переподключаемся под новым аккаунтом (раньше оставался «только чтение»).
        authWatcher = scope.launch {
            var last = auth.state.value is AuthState.SignedIn
            auth.state.collect { st ->
                val now = st is AuthState.SignedIn
                if (now != last) {
                    last = now
                    if (!closed) {
                        attempts = 0
                        open()
                    }
                }
            }
        }
    }

    /**
     * Недавние сообщения канала (последние 50). Twitch IRC истории не присылает.
     * Источник: recent-messages (robotty). Возвращает true, если сервис ответил (даже пустым списком),
     * false — не ответил/ошибка (тогда start() повторит попытку).
     */
    private suspend fun loadRecentHistory(): Boolean {
        return try {
            withTimeout(15_000) {
                val url = "https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=50&hide_moderation_messages=true"
                val result = Http.execute(Request.Builder().url(url).header(com.novastreams.app.core.network.ChatThrottle.HEADER, "1").get().build())
                if (!result.isSuccess) return@withTimeout false
                val arr = result.json().optJSONArray("messages") ?: return@withTimeout true
                val parsed = ArrayList<ChatMessage>(arr.length())
                for (i in 0 until arr.length()) {
                    val line = arr.optString(i)
                    if (line.contains(" PRIVMSG ")) {
                        parseMessage(line)?.let { parsed.add(it) }
                    }
                }
                if (parsed.isEmpty()) return@withTimeout true
                _messages.update { current ->
                    // Сообщения, которые успели прийти вживую и уже есть в истории, второй раз не добавляем.
                    val seen = current.mapTo(HashSet()) { it.author + "\u0000" + it.text }
                    val history = parsed.filter { (it.author + "\u0000" + it.text) !in seen }
                    (history + current).takeLast(MAX_MESSAGES)
                }
                true
            }
        } catch (_: TimeoutCancellationException) {
            false // Сервис истории не ответил — live-чат работает сам, история будет перезапрошена.
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false // Нет сети / сервис недоступен — только live.
        }
    }

    private suspend fun open() {
        if (closed) return
        _status.value = ChatStatus.Connecting

        // Токен может понадобиться обновить, поэтому спрашиваем его у репозитория, а не храним сами.
        val signedIn = auth.state.value is AuthState.SignedIn
        val token = if (signedIn) {
            try {
                auth.validAccessToken()
            } catch (e: IOException) {
                // Сеть/прокси подвели при обновлении токена. Вход при этом есть — не падаем в анонимный режим
                // (иначе чат писал «войдите», хотя вы вошли), а пробуем ещё раз.
                onDisconnected()
                return
            }
        } else {
            null
        }
        val user = (auth.state.value as? AuthState.SignedIn)?.user
        authed = token != null && user != null
        val nick = if (authed) user!!.login else "justinfan" + (10000..99999).random()
        displayName = if (authed) user!!.displayName else ""
        val pass = if (authed) "oauth:$token" else "SCHMOOPIIE"

        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        val generation = ++connectionGeneration
        socket?.cancel()
        socket = Http.wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Порядок важен: сначала возможности, потом PASS, затем NICK (по документации Twitch).
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands twitch.tv/membership")
                webSocket.send("PASS $pass")
                webSocket.send("NICK $nick")
                // JOIN после 001 (регистрация), как в SmartTwitchTV — надёжнее на тихих каналах.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                // lineSequence() корректно режет и CRLF, и LF.
                text.lineSequence().filter { it.isNotBlank() }.forEach { handleLine(webSocket, it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation == connectionGeneration) onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation == connectionGeneration) onDisconnected()
            }
        })
    }

    private fun handleLine(ws: WebSocket, line: String) {
        when {
            // Twitch проверяет, что мы живы: на PING нужно ответить, иначе соединение закроют.
            line.startsWith("PING") -> {
                val payload = line.removePrefix("PING").trim().ifBlank { ":tmi.twitch.tv" }
                ws.send("PONG $payload")
            }

            // Код 001 — «добро пожаловать», вход принят.
            line.contains(" 001 ") -> {
                attempts = 0
                ws.send("JOIN #$channel")
                _status.value = ChatStatus.Connected(canSend = authed)
            }

            line.contains("Login authentication failed") || line.contains("Improperly formatted auth") -> {
                _status.value = ChatStatus.Failed(
                    if (authed) "Чат отклонил вход. Выйдите и войдите в Twitch заново"
                    else "Анонимное чтение чата недоступно. Войдите через Twitch"
                )
                closed = true
                ws.close(1000, null)
            }

            // Twitch просит переподключиться (например, при обслуживании сервера).
            line.contains(" RECONNECT") -> ws.close(1000, null)

            line.contains(" PRIVMSG ") -> parseMessage(line)?.let { msg ->
                // Сообщения копим и выкладываем пачкой раз в 250 мс: в быстром чате иначе список перерисовывается
                // на каждое сообщение, и слабые приставки не успевают.
                pending.add(msg)
                if (flushScheduled.compareAndSet(false, true)) {
                    scope.launch {
                        delay(250)
                        flushScheduled.set(false)
                        val batch = ArrayList<ChatMessage>()
                        while (true) batch.add(pending.poll() ?: break)
                        if (batch.isNotEmpty()) _messages.update { (it + batch).takeLast(MAX_MESSAGES) }
                    }
                }
            }
        }
    }

    private fun onDisconnected() {
        if (closed) return
        if (attempts < MAX_RECONNECTS) {
            attempts++
            // Не мигаем «Подключение…» при кратком обрыве: сообщения не трогаем,
            // статус остаётся Connected (или Connecting, если ещё ни разу не вошли).
            if (_status.value !is ChatStatus.Connected) {
                _status.value = ChatStatus.Connecting
            }
            scope.launch {
                delay(1_500L * attempts) // 1.5, 3, 4.5… с
                if (!closed) open()
            }
        } else {
            _status.value = ChatStatus.Failed("Чат отключился. Проверьте интернет")
        }
    }

    /**
     * Отправляет сообщение. Возвращает текст ошибки для показа пользователю или null, если всё хорошо.
     * Ограничение частоты нужно, потому что Twitch молча отбрасывает слишком частые сообщения.
     */
    fun send(raw: String): String? {
        if (!authed) return "Чтобы писать в чат, войдите через Twitch"
        val text = raw.replace('\r', ' ').replace('\n', ' ').trim().take(500)
        if (text.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (now - lastSendMs < 1200) return "Слишком часто. Подождите секунду"
        val ok = socket?.send("PRIVMSG #$channel :$text") ?: false
        if (!ok) return "Сообщение не отправлено: нет соединения"
        lastSendMs = now
        // Twitch не присылает нам наше же сообщение обратно, поэтому добавляем его в список сами.
        _messages.update {
            (it + ChatMessage(nextId.getAndIncrement(), displayName, text, colorSeed = 0, isOwn = true))
                .takeLast(MAX_MESSAGES)
        }
        return null
    }

    fun close() {
        closed = true
        authWatcher?.cancel()
        socket?.close(1000, null)
    }

    /** Разбирает строку PRIVMSG: «@теги :ник!… PRIVMSG #канал :текст». */
    private fun parseMessage(line: String): ChatMessage? {
        var rest = line
        val tags = HashMap<String, String>()
        if (rest.startsWith("@")) {
            val space = rest.indexOf(' ')
            if (space < 0) return null
            rest.substring(1, space).split(';').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) tags[pair.substring(0, eq)] = unescapeTag(pair.substring(eq + 1))
            }
            rest = rest.substring(space + 1)
        }
        val marker = " PRIVMSG "
        val idx = rest.indexOf(marker)
        if (idx < 0) return null
        val nick = rest.substring(0, idx).removePrefix(":").substringBefore('!')
        val after = rest.substring(idx + marker.length)
        val textStart = after.indexOf(" :")
        if (textStart < 0) return null
        var text = after.substring(textStart + 2)
        // Сообщение вида «/me текст» приходит в обёртке ACTION — убираем служебные символы.
        var isAction = false
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
            isAction = true
        }
        return ChatMessage(
            id = nextId.getAndIncrement(),
            author = tags["display-name"]?.takeIf { it.isNotBlank() } ?: nick,
            text = text,
            colorSeed = seedOf(nick),
            colorHex = tags["color"]?.takeIf { it.isNotBlank() },
            // У сообщений «/me» позиции смайликов не сверялись с обёрткой ACTION — для них картинки из тега не берём
            // (слова-смайлики сторонних наборов всё равно подставятся по имени).
            twitchEmotes = if (isAction) emptyList() else parseEmoteTag(tags["emotes"]),
        )
    }

    /** Тег emotes: «25:0-4,12-16/1902:6-10» → список позиций. */
    private fun parseEmoteTag(tag: String?): List<EmoteSpan> {
        if (tag.isNullOrBlank()) return emptyList()
        val out = mutableListOf<EmoteSpan>()
        tag.split('/').forEach { entry ->
            val colon = entry.indexOf(':')
            if (colon <= 0) return@forEach
            val id = entry.substring(0, colon)
            entry.substring(colon + 1).split(',').forEach { range ->
                val dash = range.indexOf('-')
                val s = range.substring(0, if (dash < 0) range.length else dash).toIntOrNull()
                val e = if (dash < 0) null else range.substring(dash + 1).toIntOrNull()
                if (s != null && e != null && e >= s) out += EmoteSpan(s, e, id)
            }
        }
        return out.sortedBy { it.start }
    }

    /** В значениях тегов IRC пробел и «;» записаны escape-последовательностями. */
    private fun unescapeTag(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    's' -> out.append(' ')
                    ':' -> out.append(';')
                    'r' -> out.append('\r')
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private companion object {
        // Лимит списка: чем он меньше, тем легче чату на слабой приставке (каждая строка может нести картинки-смайлики).
        const val MAX_MESSAGES = 120
        const val MAX_RECONNECTS = 8
    }
}
