package com.novastreams.app.core.auth

/** Данные вошедшего пользователя Twitch. */
data class UserInfo(
    val id: String,
    val login: String,
    val displayName: String,
    /** Адрес аватарки; пустая строка, если её не удалось получить. */
    val profileImageUrl: String,
)
