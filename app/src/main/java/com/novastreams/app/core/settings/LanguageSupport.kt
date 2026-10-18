package com.novastreams.app.core.settings

import java.util.Locale

/**
 * Автоматический выбор языка рекомендаций.
 * Системный язык → ISO 639-1 (верхний регистр) → фильтр Twitch.
 * Если язык не поддерживается — возвращаем null, и показываются все стримы.
 */
object LanguageSupport {

    /** Языки, для которых включаем фильтр (двухбуквенные коды ISO 639-1). */
    private val supported = setOf(
        "RU", "EN", "DE", "FR", "ES", "PT", "JA", "KO", "ZH",
        "IT", "PL", "TR", "UK", "CS", "NL", "SV", "AR", "FI", "NO", "DA", "HU", "RO", "TH", "VI", "ID",
    )

    /** Код языка системы для Twitch (например, "RU") или null, если такой язык не поддерживается. */
    fun systemTwitchLanguage(): String? {
        val code = Locale.getDefault().language.uppercase()
        return code.takeIf { it in supported }
    }
}
