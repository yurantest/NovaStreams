package com.novastreams.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.novastreams.app.ui.components.AccentButton
import com.novastreams.app.ui.components.NovaTextField
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.NovaColors
import com.novastreams.app.ui.theme.accentPresets
import com.novastreams.app.ui.theme.colorToHex
import com.novastreams.app.ui.theme.ensureVisibleAccent

/**
 * Настройка акцентного цвета: пресеты, поле HEX с проверкой, мгновенный предпросмотр и сохранение.
 * Предпросмотр показывается в этом блоке сразу при вводе; в тему всего приложения цвет применяется после «Применить»
 * (или выбора пресета) и сохраняется в DataStore.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccentEditor(viewModel: SettingsViewModel, modifier: Modifier = Modifier, okToEdit: Boolean = false) {
    val editor by viewModel.editor.collectAsState()
    val savedHex by viewModel.savedAccentHex.collectAsState()

    Column(modifier = modifier) {
        Text(
            text = "Акцентный цвет",
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Влияет на активные элементы и фокус. Фон, карточки и текст не меняются.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            accentPresets.forEach { (_, color) ->
                val selected = colorToHex(color).equals(savedHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .novaClickable(CircleShape, focusScale = 1.1f) { viewModel.selectColor(color) }
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(3.dp, NovaColors.TextPrimary, CircleShape) else Modifier
                        ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        NovaTextField(
            value = editor.input,
            onValueChange = viewModel::onHexInput,
            label = "Свой цвет HEX, например #9147FF",
            okToEdit = okToEdit,
            isError = !editor.isValid,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.applyDraft() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!editor.isValid) {
            Text(
                text = "Введите цвет в формате #RRGGBB",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.Live,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        val preview = editor.preview
        if (preview != null) {
            val shown = ensureVisibleAccent(preview)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Предпросмотр", style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(shown)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Кнопка",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (shown.red * 0.2126f + shown.green * 0.7152f + shown.blue * 0.0722f > 0.55f)
                            Color(0xFF101014) else Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(shown))
                Spacer(Modifier.width(6.dp))
                Text("Активный пункт", style = MaterialTheme.typography.bodyMedium, color = shown)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(text = "Применить", onClick = viewModel::applyDraft)
            AccentButton(text = "Сбросить", onClick = viewModel::resetAccent, filled = false)
        }
    }
}
