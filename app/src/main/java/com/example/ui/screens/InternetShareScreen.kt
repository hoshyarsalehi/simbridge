package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import com.example.ui.SimBridgeUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun InternetShareScreen(
    uiState: SimBridgeUiState,
    onSelectMode: (InternetShareMode) -> Unit,
    onHostSsidChanged: (String) -> Unit,
    onHostPassChanged: (String) -> Unit,
    onShareHotspotCredentials: () -> Unit,
    onOpenTetheringSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHost = uiState.settings.deviceRole == DeviceRole.HOST_SIM
    val clipboard = LocalClipboardManager.current
    val net = uiState.networkTelemetry

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (net.isInternetConnected) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = "وضعیت شبکه",
                                tint = if (net.isInternetConnected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Text(
                                text = net.transportLabelFa,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!net.wifiSsid.isNullOrBlank()) {
                            Text(
                                text = "شبکه وای‌فای: ${net.wifiSsid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!net.localIpAddress.isNullOrBlank()) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "IP: ${net.localIpAddress}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "انتخاب روش اشتراک اینترنت بین دو گوشی",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                ShareModeCard(
                    mode = InternetShareMode.BLUETOOTH_PAN,
                    selected = uiState.settings.internetShareMode == InternetShareMode.BLUETOOTH_PAN,
                    onClick = { onSelectMode(InternetShareMode.BLUETOOTH_PAN) },
                    modifier = Modifier.testTag("mode_bluetooth_pan_card")
                )
            }

            item {
                ShareModeCard(
                    mode = InternetShareMode.WIFI_HOTSPOT,
                    selected = uiState.settings.internetShareMode == InternetShareMode.WIFI_HOTSPOT,
                    onClick = { onSelectMode(InternetShareMode.WIFI_HOTSPOT) },
                    modifier = Modifier.testTag("mode_wifi_hotspot_card")
                )
            }

            if (uiState.settings.internetShareMode == InternetShareMode.BLUETOOTH_PAN) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (isHost) {
                                    "تنظیم اشتراک اینترنت از طریق بلوتوث (گوشی اول)"
                                } else {
                                    "دریافت اینترنت بلوتوث روی گوشی دوم"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isHost) {
                                    "۱. گزینه «تتر بلوتوث» (Bluetooth Tethering) را در تنظیمات نقطه اتصال گوشی اول روشن کنید.\n۲. گوشی دوم پس از اتصال بلوتوث مستقیماً از اینترنت سیم‌کارت استفاده می‌کند."
                                } else {
                                    "۱. در تنظیمات بلوتوث این گوشی روی نام گوشی اول بزنید و گزینه «دسترسی به اینترنت» (Internet Access) را فعال کنید."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isHost) {
                                    Button(
                                        onClick = onOpenTetheringSettings,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("open_bt_tethering_settings")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("باز کردن تتر بلوتوث")
                                    }
                                } else {
                                    Button(
                                        onClick = onOpenBluetoothSettings,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("open_companion_bt_internet_settings")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.BluetoothConnected,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("تنظیمات اینترنت بلوتوث")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (isHost) {
                                    "اشتراک هات‌اسپات وای‌فای (گوشی اول)"
                                } else {
                                    "اتصال به هات‌اسپات گوشی اول"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (isHost) {
                                Text(
                                    text = "نقطه اتصال (Hotspot) گوشی اول را روشن کنید و نام و رمز وای‌فای را بنویسید تا از طریق بلوتوث برای گوشی دوم ارسال شود.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = uiState.hostHotspotSsidInput,
                                    onValueChange = onHostSsidChanged,
                                    label = { Text("نام هات‌اسپات (SSID)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("hotspot_ssid_input")
                                )

                                OutlinedTextField(
                                    value = uiState.hostHotspotPassInput,
                                    onValueChange = onHostPassChanged,
                                    label = { Text("رمز عبور هات‌اسپات") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("hotspot_password_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onShareHotspotCredentials,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("share_hotspot_info_button")
                                    ) {
                                        Text("ارسال به گوشی دوم")
                                    }
                                    OutlinedButton(
                                        onClick = onOpenTetheringSettings,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("open_wifi_hotspot_settings")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.WifiTethering,
                                            contentDescription = "تنظیمات هات‌اسپات"
                                        )
                                    }
                                }
                            } else {
                                if (uiState.peerHotspotSsid.isNotBlank()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "مشخصات دریافتی از گوشی اول:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "SSID: ${uiState.peerHotspotSsid}",
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                IconButton(
                                                    onClick = {
                                                        clipboard.setText(
                                                            AnnotatedString(uiState.peerHotspotSsid)
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("copy_ssid_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.ContentCopy,
                                                        contentDescription = "کپی نام شبکه"
                                                    )
                                                }
                                            }
                                            if (uiState.peerHotspotPassword.isNotBlank()) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "رمز: ${uiState.peerHotspotPassword}",
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            clipboard.setText(
                                                                AnnotatedString(uiState.peerHotspotPassword)
                                                            )
                                                        },
                                                        modifier = Modifier.testTag("copy_password_button")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.ContentCopy,
                                                            contentDescription = "کپی رمز وای‌فای"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "هات‌اسپات وای‌فای را روی گوشی اول روشن کنید و از دکمه زیر به شبکه آن متصل شوید.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = onOpenWifiSettings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("open_wifi_connect_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("انتخاب و اتصال به شبکه وای‌فای")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ShareModeCard(
    mode: InternetShareMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (mode == InternetShareMode.BLUETOOTH_PAN) {
                    Icons.Filled.BluetoothConnected
                } else {
                    Icons.Filled.WifiTethering
                },
                contentDescription = mode.titleFa,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.titleFa,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mode.subtitleFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = if (selected) "فعال" else "غیرفعال",
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
