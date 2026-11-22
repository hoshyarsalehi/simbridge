package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BluetoothLinkState
import com.example.model.DeviceRole
import com.example.model.PairedDeviceUi
import com.example.ui.SimBridgeUiState
import com.example.ui.components.AdvancedSettingsCard
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.PermissionRequestBanner
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun DashboardScreen(
    uiState: SimBridgeUiState,
    onSelectRole: (DeviceRole) -> Unit,
    onStartHost: () -> Unit,
    onConnectDevice: (PairedDeviceUi) -> Unit,
    onDisconnect: () -> Unit,
    onRefreshDevices: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onSendTestRing: () -> Unit,
    onToggleRingOnCompanion: (Boolean) -> Unit,
    onToggleVibrateOnCall: (Boolean) -> Unit,
    onNavigateToInternet: () -> Unit,
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
    val missingPerms = buildList {
        if (!uiState.hasBluetoothPerms) add("بلوتوث")
        if (!uiState.hasMicPerm) add("میکروفون پل صوتی")
        if (!uiState.hasNotificationPerm) add("اعلان‌ها")
        if (!isHost && !uiState.hasContactsPerm) add("مخاطبین")
        if (!isHost && !uiState.hasCallPhonePerm) add("تماس از صفحه تماس گوشی")
        if (isHost) {
            if (!uiState.hasPhoneStatePerm) add("وضعیت تماس")
            if (!uiState.hasCallLogPerm) add("شماره تماس ورودی")
            if (!uiState.hasCallPhonePerm) add("تماس مستقیم")
            if (!uiState.hasSmsPerms) add("ارسال و دریافت پیامک")
        }
    }

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
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_bridge_hero),
                                contentDescription = "تصویر پل ارتباطی دو گوشی",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
                                            )
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "SimBridge",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "هدایت تماس، پیامک و اشتراک اینترنت از طریق بلوتوث و وای‌فای",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ConnectionStatusBadge(
                                linkState = uiState.linkState,
                                connectedDeviceName = uiState.connectedDeviceName,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Surface(
                                onClick = onNavigateToInternet,
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dashboard_internet_summary_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.WifiTethering,
                                        contentDescription = "حالت اینترنت",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "روش فعال اشتراک اینترنت",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = uiState.settings.internetShareMode.titleFa,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "تغییر",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (missingPerms.isNotEmpty()) {
                item {
                    PermissionRequestBanner(
                        missingPermissionsSummary = "مجوزهای مورد نیاز: ${missingPerms.joinToString("، ")}",
                        onRequestPermissions = onRequestPermissions
                    )
                }
            }

            item {
                Text(
                    text = "۱. انتخاب نقش این گوشی",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RoleSelectionCard(
                        role = DeviceRole.HOST_SIM,
                        selected = uiState.settings.deviceRole == DeviceRole.HOST_SIM,
                        onClick = { onSelectRole(DeviceRole.HOST_SIM) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("role_host_card")
                    )
                    RoleSelectionCard(
                        role = DeviceRole.COMPANION_NO_SIM,
                        selected = uiState.settings.deviceRole == DeviceRole.COMPANION_NO_SIM,
                        onClick = { onSelectRole(DeviceRole.COMPANION_NO_SIM) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("role_companion_card")
                    )
                }
            }

            item {
                Text(
                    text = "۲. برقراری ارتباط بلوتوث",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (uiState.settings.deviceRole == DeviceRole.HOST_SIM) {
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
                                text = "میزبان بلوتوث (گوشی دارای سیم‌کارت)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "ابتدا دو گوشی را در تنظیمات بلوتوث جفت (Pair) کنید، سپس سرور بلوتوث را روی این گوشی فعال کنید تا گوشی دوم متصل شود.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (uiState.linkState == BluetoothLinkState.DISCONNECTED ||
                                    uiState.linkState == BluetoothLinkState.ERROR
                                ) {
                                    Button(
                                        onClick = onStartHost,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("start_host_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.BluetoothSearching,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("فعال‌سازی میزبان")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = onDisconnect,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("disconnect_bridge_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.LinkOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("قطع ارتباط")
                                    }
                                }

                                OutlinedButton(
                                    onClick = onOpenBluetoothSettings,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("open_bt_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SettingsBluetooth,
                                        contentDescription = "تنظیمات بلوتوث"
                                    )
                                }
                            }

                            if (uiState.linkState == BluetoothLinkState.CONNECTED) {
                                OutlinedButton(
                                    onClick = onSendTestRing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("test_ring_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.NotificationsActive,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("ارسال زنگ آزمایشی به گوشی دوم")
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "دستگاه‌های بلوتوث جفت‌شده",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row {
                                    IconButton(
                                        onClick = onRefreshDevices,
                                        modifier = Modifier.testTag("refresh_devices_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = "بروزرسانی دستگاه‌ها"
                                        )
                                    }
                                    IconButton(
                                        onClick = onOpenBluetoothSettings,
                                        modifier = Modifier.testTag("companion_bt_settings_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.SettingsBluetooth,
                                            contentDescription = "تنظیمات بلوتوث"
                                        )
                                    }
                                }
                            }

                            if (uiState.linkState != BluetoothLinkState.DISCONNECTED &&
                                uiState.linkState != BluetoothLinkState.ERROR
                            ) {
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("companion_disconnect_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("قطع اتصال فعلی")
                                }
                            }

                            if (uiState.pairedDevices.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PhonelinkSetup,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = "هیچ دستگاه جفت‌شده‌ای یافت نشد",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "ابتدا در تنظیمات بلوتوث گوشی اول و دوم را Pair کنید و سپس بروزرسانی را بزنید.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                items(uiState.pairedDevices, key = { it.address }) { device ->
                    PairedDeviceRowCard(
                        device = device,
                        onConnect = { onConnectDevice(device) }
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "تنظیمات زنگ روی گوشی دوم",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "پخش زنگ تماس هنگام زنگ خوردن گوشی اول",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.settings.ringOnCompanion,
                                    onCheckedChange = onToggleRingOnCompanion,
                                    modifier = Modifier.testTag("switch_ring_companion")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "لرزش (ویبره) هنگام تماس ورودی",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.settings.vibrateOnCall,
                                    onCheckedChange = onToggleVibrateOnCall,
                                    modifier = Modifier.testTag("switch_vibrate_companion")
                                )
                            }
                        }
                    }
                }
            }

            item {
                AdvancedSettingsCard(
                    uiState = uiState,
                    onSaveSecurityCode = onSaveSecurityCode,
                    onToggleSilenceHostRinger = onToggleSilenceHostRinger,
                    onOpenDndAccess = onOpenDndAccess,
                    onRequestIgnoreBattery = onRequestIgnoreBattery,
                    onOpenCallAccountSettings = onOpenCallAccountSettings,
                    onRequestDefaultSms = onRequestDefaultSms,
                    onRunAudioTest = onRunAudioTest
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun RoleSelectionCard(
    role: DeviceRole,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (role == DeviceRole.HOST_SIM) {
                        Icons.Filled.SimCard
                    } else {
                        Icons.Filled.Smartphone
                    },
                    contentDescription = role.titleFa,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "انتخاب شده",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = role.titleFa,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = role.subtitleFa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PairedDeviceRowCard(
    device: PairedDeviceUi,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isConnected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            1.dp,
            if (device.isConnected) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (device.isConnected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onConnect,
                enabled = !device.isConnected,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("connect_device_${device.address}")
            ) {
                Text(if (device.isConnected) "متصل" else "اتصال")
            }
        }
    }
}
