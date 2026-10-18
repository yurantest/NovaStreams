package com.novastreams.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastreams.app.AppContainer
import com.novastreams.app.core.auth.AuthException
import com.novastreams.app.core.auth.AuthState
import com.novastreams.app.core.auth.DeviceCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Что сейчас происходит на экране входа. */
data class LoginUi(
    /** Код для входа (Device Code Flow): не null — показываем код и ждём подтверждения. */
    val deviceCode: DeviceCode? = null,
    /** Ход проверки подтверждения на экране кода («Проверка №3: …»). */
    val deviceStatus: String = "",
    /** true — идёт запрос кода или проверка токена. */
    val busy: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {

    /** Вошёл пользователь или нет. Живёт в репозитории, поэтому одинаков на всех экранах. */
    val authState: StateFlow<AuthState> = container.auth.state

    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui

    private var deviceJob: Job? = null

    /** Вход по коду (Device Code): код вводится на странице Twitch. */
    fun startDeviceLogin() {
        deviceJob?.cancel()
        deviceJob = viewModelScope.launch {
            try {
                _ui.value = LoginUi(busy = true)
                val code = container.auth.startDeviceFlow()
                _ui.value = LoginUi(deviceCode = code)
                container.auth.awaitAuthorization(code) { status ->
                    // Обновляем только пока этот экран кода ещё открыт (не отменён).
                    if (_ui.value.deviceCode === code) _ui.value = _ui.value.copy(deviceStatus = status)
                }
                _ui.value = LoginUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _ui.value = LoginUi(error = e.message)
            } catch (e: IOException) {
                _ui.value = LoginUi(error = "Нет связи с Twitch. Проверьте интернет")
            } catch (e: Exception) {
                _ui.value = LoginUi(error = e.message ?: "Ошибка авторизации")
            }
        }
    }

    /** Кнопка «Проверить сейчас»: опросить Twitch немедленно. */
    fun checkNow() {
        container.auth.pollNow()
    }

    fun cancel() {
        deviceJob?.cancel()
        _ui.value = LoginUi()
    }

    /** Выход из аккаунта: сброс токенов и состояния. */
    fun logout() {
        viewModelScope.launch {
            try {
                container.auth.signOut()
            } catch (_: Exception) {
            }
            _ui.value = LoginUi()
        }
    }

    override fun onCleared() {
        deviceJob?.cancel()
        super.onCleared()
    }
}
