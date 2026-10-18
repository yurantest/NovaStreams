package com.novastreams.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CommentsDisabled
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novastreams.app.core.emotes.EmoteIndex
import com.novastreams.app.core.emotes.inlineEmoteUrl
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.novastreams.app.core.chat.ChatStatus
import com.novastreams.app.core.model.ChatMessage
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.ui.theme.parseHexColor

private val fallbackNickColors = listOf(
    Color(0xFF58B36E), Color(0xFFD1587F), Color(0xFF4F7BD9), Color(0xFFD08A3A), Color(0xFF9B7CFF),
)

/** Цвет ника: тот, что прислал Twitch, иначе один из цветов приложения. */
private fun nickColor(message: ChatMessage): Color =
    message.colorHex?.let { parseHexColor(it) }
        ?: fallbackNickColors[message.colorSeed % fallbackNickColors.size]

/**
 * Панель чата: статус подключения, список сообщений (прокручивается к новым) и поле ввода.
 * Не использовать внутри другого вертикально прокручиваемого списка — ей нужна ограниченная высота.
 */
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    status: ChatStatus,
    error: String?,
    onSend: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    /** true — показана только полоса заголовка (чат свёрнут). */
    collapsed: Boolean = false,
    /** Нажатие «Свернуть/Развернуть»; null — кнопки нет. */
    onToggleCollapse: (() -> Unit)? = null,
    /** Текст кнопки в заголовке (по умолчанию «Свернуть» / «Показать чат»). */
    collapseLabel: String? = null,
    /** true — вместо текста кнопка-иконка (перечёркнутый чат), удобно в оверлее. */
    collapseAsIcon: Boolean = false,
    /** Смайлики канала: картинки в сообщениях и окно выбора для отправки. */
    emotes: EmoteIndex = EmoteIndex.Empty,
    /** На ТВ при открытии панели сразу передать фокус чату, а не плееру. */
    requestInitialFocus: Boolean = false,
) {
    val emojiFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(requestInitialFocus, (status as? ChatStatus.Connected)?.canSend, emotes.isEmpty) {
        if (requestInitialFocus && (status as? ChatStatus.Connected)?.canSend == true && !emotes.isEmpty) {
            // Дать AnimatedVisibility и окну чата появиться в дереве фокуса.
            kotlinx.coroutines.delay(120)
            try { emojiFocusRequester.requestFocus() } catch (_: IllegalStateException) { }
        }
    }

    if (pickerOpen) {
        EmotePickerDialog(
            index = emotes,
            // Смайлик вставляется в конец текста с пробелами вокруг: Twitch узнаёт его как отдельное слово.
            onPick = { name -> draft = (draft.trimEnd() + " " + name + " ").trimStart() },
            onDismiss = { pickerOpen = false },
        )
    }

    // Новое сообщение — прокручиваем вниз. Ключ — номер последнего сообщения, а не размер списка: когда список
    // упёрся в лимит, размер не меняется, и прокрутка раньше останавливалась. Без анимации — на слабых приставках
    // анимированная прокрутка при частых сообщениях заметно тормозит.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.background(NovaColors.Card)) {
        ChatHeader(status, collapsed, onToggleCollapse, collapseLabel, collapseAsIcon)
        // Свёрнутый чат показывает только заголовок.
        if (collapsed) return@Column
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(messages, key = { it.id }) { message ->
                val nick = nickColor(message)
                // Ник и текст — одна строка с картинками-смайликами внутри; пересобирается, когда загрузились смайлики.
                val line = remember(message.id, emotes) { buildChatLine(message, nick, emotes) }
                Text(
                    text = line.first,
                    inlineContent = line.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.Live,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        val canSend = (status as? ChatStatus.Connected)?.canSend == true
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (error != null) onErrorShown()
                },
                placeholder = {
                    Text(
                        when {
                            status is ChatStatus.Demo -> "Демо-чат: отправка отключена"
                            canSend -> "Написать в чат"
                            else -> "Войдите, чтобы писать"
                        }
                    )
                },
                enabled = canSend,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(draft)
                    draft = ""
                }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            if (canSend && !emotes.isEmpty) {
                Spacer(Modifier.width(8.dp))
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .focusRequester(emojiFocusRequester)
                        .novaClickable(shape, focusScale = 1.08f, onClick = { pickerOpen = true })
                        .clip(shape)
                        .background(NovaColors.Surface)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.EmojiEmotions, contentDescription = "Смайлики", tint = NovaColors.TextPrimary)
                }
            }
            Spacer(Modifier.width(8.dp))
            AccentButton(
                text = "Отправить",
                onClick = {
                    onSend(draft)
                    draft = ""
                },
            )
        }
    }
}

/**
 * Собирает строку чата: «ник: текст», где смайлики Twitch (по позициям из тега) и слова-смайлики
 * 7TV/BTTV/FFZ/Twitch (по совпадению слова) заменены картинками. Картинки статичные.
 */
private fun buildChatLine(
    message: ChatMessage,
    nick: Color,
    emotes: EmoteIndex,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = LinkedHashMap<String, InlineTextContent>()
    val text = message.text

    fun addEmote(builder: AnnotatedString.Builder, name: String, url: String) {
        val id = "e${inline.size}"
        inline[id] = InlineTextContent(
            Placeholder(width = 24.sp, height = 24.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
        ) {
            ChatEmoteImage(inlineEmoteUrl(url), name, Modifier.fillMaxSize())
        }
        builder.appendInlineContent(id, name)
    }

    fun addWords(builder: AnnotatedString.Builder, segment: String) {
        if (segment.isEmpty()) return
        // Делим на слова и пробелы: слово, совпавшее со смайликом, становится картинкой.
        Regex("\\S+|\\s+").findAll(segment).forEach { m ->
            val token = m.value
            val emote = if (token.isBlank()) null else emotes.byName[token]
            if (emote != null) addEmote(builder, emote.name, emote.url) else builder.append(token)
        }
    }

    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = nick)) { append(message.author + ": ") }
        // Позиции Twitch считаются в символах Unicode, а не в UTF-16: переводим.
        val ranges = message.twitchEmotes.mapNotNull { sp ->
            try {
                val s = text.offsetByCodePoints(0, sp.start)
                val e = text.offsetByCodePoints(0, sp.end + 1)
                if (s < e && e <= text.length) Triple(s, e, sp.id) else null
            } catch (ex: IndexOutOfBoundsException) {
                null
            }
        }
        var pos = 0
        for ((s, e, id) in ranges) {
            if (s < pos) continue
            addWords(this, text.substring(pos, s))
            addEmote(this, text.substring(s, e), "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0")
            pos = e
        }
        addWords(this, text.substring(pos))
    }
    return annotated to inline
}

/** Картинка смайлика: грузится через «кран» скорости чата (см. ChatThrottle), чтобы не мешать видео. */
@Composable
private fun ChatEmoteImage(url: String, name: String, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        coil.request.ImageRequest.Builder(context)
            .data(url)
            .addHeader(com.novastreams.app.core.network.ChatThrottle.HEADER, "1")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * Окно выбора смайликов: вкладки наборов (Twitch / 7TV / BTTV / FFZ) и сетка картинок.
 * Окно остаётся открытым, чтобы вставить несколько смайликов подряд; «Готово» или «Назад» закрывает.
 */
@Composable
private fun EmotePickerDialog(
    index: EmoteIndex,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    val pickerFocusRequester = remember { FocusRequester() }
    val group = index.groups.getOrNull(selected)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(20.dp))
                .background(NovaColors.Card)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                index.groups.forEachIndexed { i, g ->
                    AccentButton(
                        text = g.title,
                        onClick = { selected = i },
                        modifier = if (i == 0) Modifier.focusRequester(pickerFocusRequester) else Modifier,
                        filled = i == selected,
                    )
                }
                Spacer(Modifier.weight(1f))
                AccentButton(text = "Готово", onClick = onDismiss, filled = false)
            }
            LaunchedEffect(Unit) {
                try { pickerFocusRequester.requestFocus() } catch (_: IllegalStateException) { }
            }
            Spacer(Modifier.height(10.dp))
            if (group == null) {
                Text("Смайликов нет", color = NovaColors.TextSecondary)
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(52.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(group.emotes, key = { emote -> emote.name }) { emote ->
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .height(52.dp)
                                .novaClickable(shape, focusScale = 1.1f, onClick = { onPick(emote.name) })
                                .clip(shape)
                                .background(NovaColors.Surface)
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChatEmoteImage(emote.url, emote.name, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** Заголовок: статус подключения слева и кнопка сворачивания справа. */
@Composable
private fun ChatHeader(
    status: ChatStatus,
    collapsed: Boolean,
    onToggle: (() -> Unit)?,
    collapseLabel: String? = null,
    collapseAsIcon: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { StatusLine(status) }
        if (onToggle != null) {
            if (collapseAsIcon) {
                val shape = androidx.compose.foundation.shape.CircleShape
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .novaClickable(shape, focusScale = 1.08f, onClick = onToggle)
                        .clip(shape)
                        .background(NovaColors.Surface)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CommentsDisabled,
                        contentDescription = collapseLabel ?: "Закрыть чат",
                        tint = NovaColors.TextPrimary,
                    )
                }
            } else {
                val label = collapseLabel
                    ?: if (collapsed) "Показать чат" else "Свернуть"
                AccentButton(
                    text = label,
                    onClick = onToggle,
                    filled = false,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(status: ChatStatus) {
    val accent = LocalNovaColors.current.accent
    val (text, color) = when (status) {
        is ChatStatus.Connecting -> "Подключение к чату…" to NovaColors.TextSecondary
        is ChatStatus.Connected ->
            (if (status.canSend) "Чат подключён" else "Чат только для чтения: войдите, чтобы писать") to
                (if (status.canSend) accent else NovaColors.TextSecondary)
        is ChatStatus.Failed -> status.message to NovaColors.Live
        is ChatStatus.Demo -> "Демонстрационный чат (не Twitch)" to NovaColors.TextSecondary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
