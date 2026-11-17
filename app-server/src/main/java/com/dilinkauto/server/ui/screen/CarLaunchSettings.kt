package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.ui.theme.CardElevatedColor
import com.dilinkauto.server.ui.theme.ChipUnselectedColor
import com.dilinkauto.server.ui.theme.WarningColor

/**
 * Settings row + WiFi-ADB setup card from the car launch screen.
 *
 * Extracted from CarLaunchScreen (docs/audit-srp-dry.md SRP-9): these are a
 * different subject from the connection status card they were embedded in.
 * Note they were not standalone top-level composables — SettingSection was
 * invoked three times from inside ConnectionStatusCard, so moving them also
 * meant moving those call sites out.
 *
 * ## Manual-entry contract (audit UX-02)
 *
 * The field owns its own raw text while the user is editing and only reports a
 * value on **Done** or **focus loss**. It used to echo the *coerced persisted*
 * value back on every keystroke, so typing "160" collapsed to 120 on the first
 * digit and silently stored the wrong DPI. [coerceManualValue] receives the
 * finished digit string — never a partial one — and returns the value to store,
 * which is handed to [onValueChange] exactly like a chip tap is.
 *
 * @param manualSeed digits to prefill the field with, or `""` when
 *   [selectedPresetValue] is one of [presets] (a selected chip is the readout,
 *   so the field stays empty). Callers pass the raw stored number rather than
 *   stripping digits out of the display label — the label may be "2.5M", whose
 *   digits are not the value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingSection(
    title: String,
    subtitle: String,
    hint: String,
    currentValueText: String,
    manualSeed: String,
    presets: List<Pair<String, Int>>,
    selectedPresetValue: Int,
    onValueChange: (Int) -> Unit,
    coerceManualValue: (String) -> Int,
    manualSuffix: String = "",
    /**
     * issue #202 评审第 2 条：输入框下方的"实际生效值 / 视口上限"提示。
     * null = 这一段不需要（比特率/帧率/档位只有 DPI 会被视口上限削减）。
     */
    note: String? = null
) {
    val focusManager = LocalFocusManager.current

    // Raw text is seeded once per stored value, not re-derived on every
    // keystroke — that is the whole point of the fix above.
    var rawText by remember(manualSeed) { mutableStateOf(manualSeed) }
    // Guards against committing on the initial (unfocused) composition and
    // against double-committing when Done also clears focus.
    var hadFocus by remember { mutableStateOf(false) }

    val commit: () -> Unit = {
        hadFocus = false
        val digits = rawText.filter { it.isDigit() }
        if (digits.isNotEmpty()) onValueChange(coerceManualValue(digits))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        currentValueText,
                        color = WarningColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Manual Input field
            OutlinedTextField(
                value = rawText,
                onValueChange = { input -> rawText = input.filter { it.isDigit() }.take(3) },
                singleLine = true,
                placeholder = {
                    Text(
                        if (manualSuffix.isNotEmpty()) manualSuffix else stringResource(R.string.manual_custom_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .width(96.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) hadFocus = true
                        else if (hadFocus) commit()
                    },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // Preset chips — FlowRow so a longer translation or a narrow viewport
        // wraps onto a second line instead of overflowing the card edge.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { (label, value) ->
                val isSelected = selectedPresetValue == value
                Button(
                    onClick = { onValueChange(value) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else ChipUnselectedColor,
                        // onPrimary is black in this theme: white on the light-blue
                        // container measured 2.00:1 (audit UX-03).
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp
        )
        // issue #202：越界回落对用户不可见的问题在这里补齐 —— 用 WarningColor 与普通
        // hint 区分开，明确"填的不是实际生效的那个数"。
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                color = WarningColor,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
internal fun WifiAdbSetupCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardElevatedColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.wifi_adb_setup_title),
                color = WarningColor,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wifi_adb_setup_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
        }
    }
}
