package com.novastreams.app.core.emotes

import com.novastreams.app.core.network.Http
import com.novastreams.app.core.twitchapi.HelixApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Один смайлик: слово, которое пишется в чате, и адрес картинки. */
data class Emote(val name: String, val url: String)

/** Набор для окна выбора: «Twitch», «7TV», «BTTV», «FFZ». */
data class EmoteGroup(val title: String, val emotes: List<Emote>)

/** Все смайлики канала: наборы для окна выбора и поиск по слову (для подстановки картинок в сообщения). */
class EmoteIndex(val groups: List<EmoteGroup>, val byName: Map<String, Emote>) {
    val isEmpty: Boolean get() = byName.isEmpty()

    companion object {
        val Empty = EmoteIndex(emptyList(), emptyMap())
    }
}

/**
 * Загружает смайлики канала:
 *  — Twitch (глобальные и канала) через Helix — только после входа;
 *  — 7TV, BetterTTV, FrankerFaceZ — публичные API без ключей.
 * Каждый источник независим: если один недоступен, остальные работают. Картинки статичные (первый кадр),
 * без анимации — чтобы чат не тормозил на слабых приставках. Запросы к сторонним API написаны по памяти
 * и в среде разработки не проверялись.
 */
class EmoteRepository(private val helix: HelixApi) {
    private val cache = ConcurrentHashMap<String, EmoteIndex>()

    // Глобальные наборы одни на всех — грузим один раз за запуск приложения.
    @Volatile private var globals: Globals? = null

    private class Globals(
        val twitch: List<Emote>,
        val sevenTv: List<Emote>,
        val bttv: List<Emote>,
        val ffz: List<Emote>,
    )

    private suspend fun getText(url: String): String? = try {
        val r = Http.execute(Request.Builder().url(url).header("Accept", "application/json").header(com.novastreams.app.core.network.ChatThrottle.HEADER, "1").build())
        if (r.isSuccess) r.body else null
    } catch (e: Exception) {
        null
    }

    private suspend fun getJson(url: String): JSONObject? =
        getText(url)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    private suspend fun getArray(url: String): JSONArray? =
        getText(url)?.let { try { JSONArray(it) } catch (e: Exception) { null } }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun twitchUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"

    // ——— Twitch (Helix) ———
    private suspend fun twitchList(path: String, params: List<Pair<String, String>>): List<Emote> = try {
        helix.get(path, params).optJSONArray("data").objects().mapNotNull {
            val id = it.optString("id")
            val name = it.optString("name")
            if (id.isBlank() || name.isBlank()) null else Emote(name, twitchUrl(id))
        }
    } catch (e: Exception) {
        emptyList() // не вошли в аккаунт или Twitch недоступен
    }

    // ——— 7TV ———
    private fun sevenTvEmotes(set: JSONObject?): List<Emote> =
        set?.optJSONArray("emotes").objects().mapNotNull {
            val name = it.optString("name")
            val host = it.optJSONObject("data")?.optJSONObject("host")?.optString("url").orEmpty()
            if (name.isBlank() || host.isBlank()) null else Emote(name, "https:" + host.removePrefix("https:") + "/2x.webp")
        }

    // ——— BetterTTV ———
    private fun bttvEmotes(arr: JSONArray?): List<Emote> = arr.objects().mapNotNull {
        val id = it.optString("id")
        val code = it.optString("code")
        if (id.isBlank() || code.isBlank()) null else Emote(code, "https://cdn.betterttv.net/emote/$id/2x.webp")
    }

    // ——— FrankerFaceZ ———
    private fun ffzEmotes(root: JSONObject?): List<Emote> {
        val sets = root?.optJSONObject("sets") ?: return emptyList()
        val out = mutableListOf<Emote>()
        sets.keys().forEach { key ->
            sets.optJSONObject(key)?.optJSONArray("emoticons").objects().forEach { e ->
                val name = e.optString("name")
                val urls = e.optJSONObject("urls")
                val u = urls?.optString("2")?.takeIf { it.isNotBlank() }
                    ?: urls?.optString("1")?.takeIf { it.isNotBlank() }
                if (name.isNotBlank() && u != null) {
                    out += Emote(name, if (u.startsWith("//")) "https:$u" else u)
                }
            }
        }
        return out
    }

    private suspend fun loadGlobals(): Globals {
        globals?.let { return it }
        val g = coroutineScope {
            val tw = async { twitchList("chat/emotes/global", emptyList()) }
            val st = async { sevenTvEmotes(getJson("https://7tv.io/v3/emote-sets/global")) }
            val bt = async { bttvEmotes(getArray("https://api.betterttv.net/3/cached/emotes/global")) }
            val ff = async { ffzEmotes(getJson("https://api.frankerfacez.com/v1/set/global")) }
            Globals(tw.await(), st.await(), bt.await(), ff.await())
        }
        // Если всё пусто (нет сети) — не запоминаем, попробуем в следующий раз.
        if (g.twitch.isNotEmpty() || g.sevenTv.isNotEmpty() || g.bttv.isNotEmpty() || g.ffz.isNotEmpty()) globals = g
        return g
    }

    /** Смайлики канала [channelId] (номер канала Twitch). Результат кэшируется на время работы приложения. */
    suspend fun load(channelId: String): EmoteIndex = kotlinx.coroutines.withContext(com.novastreams.app.core.chat.ChatDispatcher.low) {
        cache[channelId]?.let { return@withContext it }
        val index = coroutineScope {
            val g = async { loadGlobals() }
            val twCh = async { twitchList("chat/emotes", listOf("broadcaster_id" to channelId)) }
            val stCh = async { sevenTvEmotes(getJson("https://7tv.io/v3/users/twitch/$channelId")?.optJSONObject("emote_set")) }
            val btCh = async {
                getJson("https://api.betterttv.net/3/cached/users/twitch/$channelId")?.let {
                    bttvEmotes(it.optJSONArray("channelEmotes")) + bttvEmotes(it.optJSONArray("sharedEmotes"))
                } ?: emptyList()
            }
            val ffCh = async { ffzEmotes(getJson("https://api.frankerfacez.com/v1/room/id/$channelId")) }
            val gl = g.await()
            // Эмодзи канала важнее глобальных, поэтому идут первыми.
            val groups = listOf(
                EmoteGroup("Twitch", twCh.await() + gl.twitch),
                EmoteGroup("7TV", stCh.await() + gl.sevenTv),
                EmoteGroup("BTTV", btCh.await() + gl.bttv),
                EmoteGroup("FFZ", ffCh.await() + gl.ffz),
            ).map { it.copy(emotes = it.emotes.distinctBy { e -> e.name }) }.filter { it.emotes.isNotEmpty() }
            // При одинаковом слове в разных наборах побеждает тот, что выше в списке.
            val byName = LinkedHashMap<String, Emote>()
            groups.forEach { grp -> grp.emotes.forEach { byName.putIfAbsent(it.name, it) } }
            EmoteIndex(groups, byName)
        }
        // Пустой результат (например, нет сети) не кэшируем.
        if (!index.isEmpty) cache[channelId] = index
        index
    }
}

/**
 * Адрес картинки смайлика для строки чата: самый маленький размер (1x). В тексте смайлик ~24 sp, а размер 2x втрое
 * тяжелее по трафику и памяти (у 7TV ещё и анимированный). В окне выбора остаётся обычный адрес.
 */
fun inlineEmoteUrl(url: String): String = when {
    url.endsWith("/2.0") -> url.removeSuffix("/2.0") + "/1.0"
    url.endsWith("/2x.webp") -> url.removeSuffix("/2x.webp") + "/1x.webp"
    url.endsWith("/2") -> url.removeSuffix("/2") + "/1"
    else -> url
}
