package com.novastreams.app.core.twitchapi

/**
 * Сопоставление поискового запроса с текстом эфира (название, канал, категория).
 *  - Слова запроса берутся без окончания: «фильмы» находит «фильм», «фильмов», «фильмы».
 *  - Для «фильмов/кино» добавлены близкие слова (movie, сериал, watch party…).
 *  - Несколько слов — должны найтись все (каждое со своими вариантами).
 */
internal class QueryMatcher(raw: String) {
    private val groups: List<List<String>> = raw.lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map(::variantsOf)

    /** Запрос про фильмы/кино — тогда названия вроде «Интерстеллар (2014)» тоже считаем попаданием. */
    val isMovieQuery: Boolean = groups.size == 1 && groups[0].contains("watch party")

    fun matches(hay: String): Boolean {
        if (groups.isEmpty()) return false
        val h = hay.lowercase()
        if (groups.all { variants -> variants.any { h.contains(it) } }) return true
        return isMovieQuery && looksLikeMovie(h)
    }

    /**
     * Эвристика «в названии эфира, похоже, название фильма/сериала», без слов «фильм/кино»:
     *  - год в скобках: «Начало (2010)»;
     *  - серия/сезон: «3 сезон 5 серия», S02E04;
     *  - слова просмотра и озвучки: «смотрим», «совместный просмотр», «дубляж», «озвучка», «кинопоказ»;
     *  - жанры: ужасы, боевик, триллер, мультфильм, аниме…;
     *  - голый год (1990–2029) — только если категория «общение/Movies/TV», чтобы не ловить «турнир 2024».
     */
    private fun looksLikeMovie(h: String): Boolean {
        if (YEAR_IN_BRACKETS.containsMatchIn(h)) return true
        if (EPISODE.containsMatchIn(h)) return true
        if (MOVIE_WORDS.any { h.contains(it) }) return true
        if (BARE_YEAR.containsMatchIn(h) && CHAT_CATEGORIES.any { h.contains(it) }) return true
        return false
    }

    private companion object {
        val YEAR_IN_BRACKETS = Regex("[(\\[]\\s*(19[3-9]\\d|20[0-2]\\d)\\s*[)\\]]")
        val BARE_YEAR = Regex("(?<![\\p{L}\\d])(19[5-9]\\d|20[0-2]\\d)(?![\\p{L}\\d])")
        val EPISODE = Regex("(\\d+\\s*(сезон|серия|серии|серий|сер\\.|эпизод|часть))|((сезон|серия|эпизод|часть)\\s*\\d+)|(s\\d{1,2}\\s?e\\d{1,3})")
        val MOVIE_WORDS = listOf(
            "смотрим", "смотрю", "смотрит", "совместный просмотр", "совместн", "просмотр", "кинопоказ", "кинозал",
            "кинотеатр", "киношка", "кинчик", "премьера", "дубляж", "озвучка", "озвучк", "в оригинале", "субтитр",
            "мультфильм", "мульт", "аниме", "сериал", "ужасы", "боевик", "триллер", "комедия", "фантастика",
            "драма", "marvel", "марвел", "дисней", "disney", "netflix", "нетфликс", "movies", "tv show", "cinema",
        )
        val CHAT_CATEGORIES = listOf("just chatting", "общение", "movies", "tv", "кино", "фильм", "special events", "особые события")
    }

    private fun variantsOf(word: String): List<String> {
        val stem = stemOf(word)
        val out = linkedSetOf(stem)
        if (stem.startsWith("фильм") || stem.startsWith("кино") || stem.startsWith("movie") || stem.startsWith("film")) {
            out += listOf("фильм", "кино", "movie", "film", "сериал", "watch party", "watchparty", "вотчпати", "кинотеатр")
        }
        return out.toList()
    }

    /** Грубо отрезает окончание: до двух конечных гласных/«й»/«ь» (остаётся минимум 4 буквы) и английское «s». */
    private fun stemOf(word: String): String {
        var s = word
        var cut = 0
        while (cut < 2 && s.length > 4 && s.last() in "аеёиоуыэюяйь") {
            s = s.dropLast(1)
            cut++
        }
        if (s.length > 4 && s.endsWith("s")) s = s.dropLast(1)
        return s
    }
}
