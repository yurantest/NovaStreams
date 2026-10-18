package com.novastreams.app.core.twitchapi

import com.novastreams.app.core.model.Category
import com.novastreams.app.core.model.ChatMessage
import com.novastreams.app.core.model.Channel
import com.novastreams.app.core.model.Stream

/**
 * ДЕМОНСТРАЦИОННЫЕ ДАННЫЕ. Все каналы, названия и числа вымышлены и не связаны
 * с реальными стримерами Twitch. Используются, пока не подключён официальный Twitch API.
 */
object DemoData {

    val categories: List<Category> = listOf(
        Category("c1", "Космические гонки", 128_400, 0),
        Category("c2", "Пиксельные подземелья", 96_200, 1),
        Category("c3", "Просто общение", 214_900, 2),
        Category("c4", "Стратегии в реальном времени", 54_300, 3),
        Category("c5", "Музыка и живые сеты", 41_800, 4),
        Category("c6", "Кулинария", 22_100, 5),
        Category("c7", "Шахматы", 37_650, 6),
        Category("c8", "Творчество и рисование", 18_900, 7),
    )

    private fun cat(id: String) = categories.first { it.id == id }

    val channels: List<Channel> = listOf(
        Channel("u1", "orbit_fox", "OrbitFox", "Гонки на орбитальных трассах и разборы заездов.", 182_000, 0),
        Channel("u2", "pixel_miner", "PixelMiner", "Спидраны и случайные забеги по подземельям.", 96_500, 1),
        Channel("u3", "late_lamp", "LateLamp", "Поздние разговоры под тёплый свет лампы.", 310_200, 2),
        Channel("u4", "grid_marshal", "GridMarshal", "Стратегии, турниры и обучение с нуля.", 72_300, 3),
        Channel("u5", "soft_signal", "SoftSignal", "Живые сеты и электронные эксперименты.", 58_700, 4),
        Channel("u6", "pan_and_pixel", "PanAndPixel", "Готовим и играем одновременно.", 24_900, 5),
        Channel("u7", "knight_square", "KnightSquare", "Шахматные партии с комментариями.", 66_100, 6),
        Channel("u8", "ink_harbor", "InkHarbor", "Рисуем персонажей и фоны в прямом эфире.", 31_400, 7),
        Channel("u9", "north_comet", "NorthComet", "Ночные гонки и тесты настроек.", 15_800, 0),
        Channel("u10", "quiet_forge", "QuietForge", "Спокойные забеги без спешки.", 12_300, 1),
    )

    private fun ch(id: String) = channels.first { it.id == id }

    val streams: List<Stream> = listOf(
        Stream("s1", ch("u1"), "Квалификация на кольце Юпитера", "c1", cat("c1").name, 18_400, "RU", artSeed = 0),
        Stream("s2", ch("u2"), "Забег без сохранений: глава 3", "c2", cat("c2").name, 9_650, "RU", artSeed = 1),
        Stream("s3", ch("u3"), "Разговоры о неожиданном", "c3", cat("c3").name, 27_300, "RU", artSeed = 2),
        Stream("s4", ch("u4"), "Турнир выходного дня, финал", "c4", cat("c4").name, 11_200, "EN", artSeed = 3),
        Stream("s5", ch("u5"), "Рассветный сет: синтезаторы", "c5", cat("c5").name, 6_840, "EN", artSeed = 4),
        Stream("s6", ch("u6"), "Борщ по-новому и новая карта", "c6", cat("c6").name, 3_120, "RU", artSeed = 5),
        Stream("s7", ch("u7"), "Партия против зрителей", "c7", cat("c7").name, 8_900, "RU", artSeed = 6),
        Stream("s8", ch("u8"), "Рисуем город на закате", "c8", cat("c8").name, 2_450, "RU", artSeed = 7),
        Stream("s9", ch("u9"), "Ночной тест новых настроек руля", "c1", cat("c1").name, 1_980, "EN", artSeed = 2),
        Stream("s10", ch("u10"), "Спокойный забег по глубоким уровням", "c2", cat("c2").name, 1_420, "RU", artSeed = 5),
    )

    val demoChat: List<ChatMessage> = listOf(
        ChatMessage(1, "ViewerOne", "Отличный заезд!", 0),
        ChatMessage(2, "NightOwl", "Ждал этот эфир весь день", 1),
        ChatMessage(3, "CasualFan", "А что за настройки сейчас?", 2),
        ChatMessage(4, "PixelPal", "Круто выглядит", 3),
        ChatMessage(5, "ModBot", "[демо] Это пример чата, реальный чат Twitch не подключён", 4),
        ChatMessage(6, "ViewerOne", "Давай ещё раз!", 0),
    )

    val recentChannelIds: List<String> = listOf("u3", "u1", "u7")
}
