package com.novastreams.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Поле ввода для пульта ТВ.
 *
 * Обычное поле открывает экранную клавиатуру сразу, как только на него попал фокус, — при
 * перемещении по настройкам D-pad'ом клавиатура вылезает на каждом поле и мешает. Здесь поле
 * сначала только «выделено» (readOnly = клавиатура не запускается), а ввод включается по кнопке OK.
 * Когда фокус уходит с поля, оно снова становится «только выделенным».
 *
 * [okToEdit] = false даёт обычное поведение (для телефона, где поле открывают касанием).
 */
@Composable
fun NovaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    okToEdit: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var editing by remember { mutableStateOf(!okToEdit) }
    val keyboard = LocalSoftwareKeyboardController.current

    // Как только ввод включён по OK — показываем клавиатуру.
    LaunchedEffect(editing) {
        if (okToEdit && editing) keyboard?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        readOnly = okToEdit && !editing,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .onFocusChanged { if (okToEdit && !it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (okToEdit && !editing && isOk) {
                    // Реагируем на отпускание кнопки, нажатие просто «съедаем».
                    if (event.type == KeyEventType.KeyUp) editing = true
                    true
                } else {
                    false
                }
            },
    )
}
