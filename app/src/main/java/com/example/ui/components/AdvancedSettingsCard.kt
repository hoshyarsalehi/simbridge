package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.model.DeviceRole
import com.example.ui.SimBridgeUiState
import java.security.SecureRandom

@Composable
fun AdvancedSettingsCard(
    uiState: SimBridgeUiState,
    onSaveSecurityCode: (String) -> Unit,
    onToggleSilenceHostRinger: (Boolean) -> Unit,
    onOpenDndAccess: () -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onOpenCallAccountSettings: () -> Unit,
    onRequestDefaultSms: () -> Unit,
    onRunAudioTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHost = uiState.settings.deviceRole == DeviceRole.HOST_SIM
    var code by remember(uiState.settings.securityCode) {
        mutableStateOf(uiState.settings.securityCode)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "تنظیمات پیشرفته و امنیت",
                style = MaterialTheme.typography.titleSmall
            )

            // ── رمزنگاری ──
            Text(
                text = "کد امنیتی (رمزنگاری پیام‌ها): در هر دو گوشی دقیقاً یک کد وارد کنید.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("کد امنیتی") }
            )
            OutlinedButton(
                onClick = { code = generateSecurityCode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ساخت کد تصادفی قوی")
            }
            Button(
                onClick = { onSaveSecurityCode(code) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ذخیره کد امنیتی")
            }

            // ── پایداری و باتری ──
            Text(
                text = if (uiState.isIgnoringBatteryOpt) {
                    "محدودیت باتری برای برنامه برداشته شده است ✓ (اتصال پایدار می‌ماند)"
                } else {
                    "برای اینکه سیستم اتصال بلوتوث را در پس‌زمینه قطع نکند، محدودیت باتری را بردارید."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!uiState.isIgnoringBatteryOpt) {
                OutlinedButton(
                    onClick = onRequestIgnoreBattery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("برداشتن محدودیت باتری")
                }
            }

            if (isHost) {
                // ── تست صدای تماس ──
                Text(
                    text = "تست صدای تماس: وسط یک تماس زنده، همه روش‌های گرفتن صدا امتحان می‌شود و بهترین روش برای همین گوشی ذخیره می‌شود. طرف مقابل باید در طول تست صحبت کند. پل صوتی در این مدت متوقف می‌شود.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onRunAudioTest,
                    enabled = !uiState.audioTestRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.audioTestRunning) "در حال تست…" else "اجرای تست صدای تماس")
                }
                if (uiState.audioTestSummary.isNotBlank()) {
                    Text(
                        text = uiState.audioTestSummary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // ── فقط گوشی دوم زنگ بخورد ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "وقتی به گوشی دوم وصل است، زنگ فقط روی گوشی دوم بخورد",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = uiState.settings.silenceHostRinger,
                        onCheckedChange = onToggleSilenceHostRinger
                    )
                }
                if (uiState.settings.silenceHostRinger && !uiState.hasDndAccess) {
                    Text(
                        text = "برای خاموش کردن زنگ این گوشی، دسترسی «مزاحم نشوید» برای برنامه لازم است.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = onOpenDndAccess,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("باز کردن دسترسی «مزاحم نشوید»")
                    }
                }
            } else {
                // ── تماس با صفحه تماس خود گوشی ──
                Text(
                    text = if (uiState.isCallAccountEnabled) {
                        "حساب تماس SimBridge فعال است ✓ تماس‌ها با صفحه و زنگ پیش‌فرض گوشی نمایش داده می‌شوند."
                    } else {
                        "برای اینکه تماس‌ها با صفحه و زنگ پیش‌فرض همین گوشی بیایند، حساب «SimBridge» را در تنظیمات حساب‌های تماس روشن کنید."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!uiState.isCallAccountEnabled) {
                    OutlinedButton(
                        onClick = onOpenCallAccountSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("باز کردن تنظیمات حساب‌های تماس")
                    }
                }

                // ── پیامک در برنامه پیامک گوشی ──
                Text(
                    text = if (uiState.isDefaultSmsApp) {
                        "SimBridge برنامه پیامک پیش‌فرض است ✓ پیامک‌های گوشی اول در بخش پیامک همین گوشی ذخیره می‌شوند."
                    } else {
                        "برای اینکه پیامک‌های گوشی اول در بخش پیامک همین گوشی ذخیره شوند و ارسال هم از طریق گوشی اول انجام شود، SimBridge را برنامه پیامک پیش‌فرض کنید."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!uiState.isDefaultSmsApp) {
                    OutlinedButton(
                        onClick = onRequestDefaultSms,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تبدیل به برنامه پیامک پیش‌فرض")
                    }
                }
            }
        }
    }
}

// حروف و اعداد بدون شکل‌های مشابه (0/O و 1/l/I) تا تایپ کردنش روی گوشی دیگر راحت باشد
private fun generateSecurityCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return buildString {
        repeat(10) { append(chars[random.nextInt(chars.length)]) }
    }
}
