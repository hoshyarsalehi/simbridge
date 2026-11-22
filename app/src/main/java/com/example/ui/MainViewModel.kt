package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SimBridgeApplication
import com.example.bluetooth.BridgeForegroundService
import com.example.data.preferences.BridgeSettings
import com.example.model.BluetoothLinkState
import com.example.model.BridgePacket
import com.example.model.BridgePacketType
import com.example.model.CallBridgeState
import com.example.model.CallEventUi
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import com.example.model.NetworkTelemetryUi
import com.example.model.PairedDeviceUi
import com.example.model.SmsEventUi
import com.example.system.CallAudioTester
import com.example.system.ContactsHelper
import com.example.system.PowerHelper
import com.example.system.RingerSilencer
import com.example.system.SmsStore
import com.example.telecom.BridgeTelecom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SimBridgeUiState(
    val settings: BridgeSettings = BridgeSettings(),
    val linkState: BluetoothLinkState = BluetoothLinkState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val pairedDevices: List<PairedDeviceUi> = emptyList(),
    val callLogs: List<CallEventUi> = emptyList(),
    val smsLogs: List<SmsEventUi> = emptyList(),
    val networkTelemetry: NetworkTelemetryUi = NetworkTelemetryUi(),
    val activeCallState: CallBridgeState = CallBridgeState.IDLE,
    val incomingCallerLabel: String = "",
    val voiceBridgeActive: Boolean = false,
    val dialerInput: String = "",
    val smsRecipientInput: String = "",
    val smsBodyInput: String = "",
    val hostHotspotSsidInput: String = "",
    val hostHotspotPassInput: String = "",
    val peerHotspotSsid: String = "",
    val peerHotspotPassword: String = "",
    val hasBluetoothPerms: Boolean = false,
    val hasPhoneStatePerm: Boolean = false,
    val hasCallLogPerm: Boolean = false,
    val hasCallPhonePerm: Boolean = false,
    val hasAnswerCallsPerm: Boolean = false,
    val hasSmsPerms: Boolean = false,
    val hasMicPerm: Boolean = false,
    val hasNotificationPerm: Boolean = false,
    val hasContactsPerm: Boolean = false,
    val isIgnoringBatteryOpt: Boolean = true,
    val isDefaultSmsApp: Boolean = false,
    val isCallAccountEnabled: Boolean = false,
    val hasDndAccess: Boolean = true,
    val audioTestRunning: Boolean = false,
    val audioTestSummary: String = "",
    val isBluetoothEnabled: Boolean = false,
    val isBluetoothSupported: Boolean = true,
    val statusBannerMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SimBridgeApplication
    private val repo = app.repository
    private val bt = app.bluetoothManager
    private val telephony = app.telephonyHelper
    private val tethering = app.tetheringHelper
    private val coordinator = app.coordinator
    private val ringerSilencer = RingerSilencer(application)

    private val _uiState = MutableStateFlow(SimBridgeUiState())
    val uiState: StateFlow<SimBridgeUiState> = _uiState.asStateFlow()

    init {
        tethering.startMonitoring()
        refreshSystemStatus()
        observeFlows()
    }

    private fun observeFlows() {
        viewModelScope.launch {
            combine(
                repo.settingsFlow,
                bt.linkState,
                bt.connectedDeviceName,
                bt.pairedDevices,
                bt.voiceBridgeActive
            ) { settings, link, devName, devices, voiceActive ->
                FiveTuple(settings, link, devName, devices, voiceActive)
            }.collect { (settings, link, devName, devices, voiceActive) ->
                _uiState.update { cur ->
                    cur.copy(
                        settings = settings,
                        linkState = link,
                        connectedDeviceName = devName,
                        pairedDevices = devices,
                        voiceBridgeActive = voiceActive,
                        hostHotspotSsidInput = cur.hostHotspotSsidInput.ifEmpty { settings.hotspotSsid },
                        hostHotspotPassInput = cur.hostHotspotPassInput.ifEmpty { settings.hotspotPassword }
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                coordinator.activeCallState,
                coordinator.incomingCallerLabel,
                coordinator.peerHotspotSsid,
                coordinator.peerHotspotPassword
            ) { callState, callerLabel, peerSsid, peerPass ->
                FourTuple(callState, callerLabel, peerSsid, peerPass)
            }.collect { (callState, callerLabel, peerSsid, peerPass) ->
                _uiState.update {
                    it.copy(
                        activeCallState = callState,
                        incomingCallerLabel = callerLabel,
                        peerHotspotSsid = peerSsid,
                        peerHotspotPassword = peerPass
                    )
                }
            }
        }

        viewModelScope.launch {
            coordinator.eventsBanner.collect { msg ->
                if (msg != null) {
                    _uiState.update { it.copy(statusBannerMessage = msg) }
                    coordinator.clearBanner()
                }
            }
        }

        viewModelScope.launch {
            repo.callLogsFlow.collect { logs ->
                _uiState.update { it.copy(callLogs = logs) }
            }
        }

        viewModelScope.launch {
            repo.smsLogsFlow.collect { logs ->
                _uiState.update { it.copy(smsLogs = logs) }
            }
        }

        viewModelScope.launch {
            tethering.telemetry.collect { net ->
                _uiState.update { it.copy(networkTelemetry = net) }
            }
        }

        viewModelScope.launch {
            bt.errorMessage.collect { err ->
                if (err != null) {
                    _uiState.update { it.copy(statusBannerMessage = err) }
                    bt.clearError()
                }
            }
        }
    }

    fun refreshSystemStatus() {
        bt.refreshPairedDevices()
        if (telephony.hasPhoneStatePermission()) {
            telephony.startObservingCallState()
        }
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                app,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(
                hasBluetoothPerms = bt.hasBluetoothPermissions(),
                hasPhoneStatePerm = telephony.hasPhoneStatePermission(),
                hasCallLogPerm = telephony.hasCallLogPermission(),
                hasCallPhonePerm = telephony.hasCallPhonePermission(),
                hasAnswerCallsPerm = telephony.hasAnswerCallsPermission(),
                hasSmsPerms = telephony.hasSmsPermissions(),
                hasMicPerm = hasMic,
                hasNotificationPerm = hasNotif,
                hasContactsPerm = ContactsHelper.hasPermission(app),
                isIgnoringBatteryOpt = PowerHelper.isIgnoringBatteryOptimizations(app),
                isDefaultSmsApp = SmsStore.isDefaultSmsApp(app),
                isCallAccountEnabled = BridgeTelecom.isAccountEnabled(app),
                hasDndAccess = ringerSilencer.hasPolicyAccess(),
                isBluetoothEnabled = bt.isBluetoothEnabled(),
                isBluetoothSupported = bt.isBluetoothSupported()
            )
        }
        tethering.refreshTelemetry()
    }

    fun setDeviceRole(role: DeviceRole) {
        viewModelScope.launch {
            repo.setDeviceRole(role)
            if (bt.linkState.value != BluetoothLinkState.DISCONNECTED) {
                BridgeForegroundService.stopBridge(app)
            }
            _uiState.update {
                it.copy(statusBannerMessage = "نقش دستگاه روی «${role.titleFa}» تنظیم شد.")
            }
        }
    }

    fun startHostListening() {
        if (!bt.hasBluetoothPermissions()) {
            _uiState.update { it.copy(statusBannerMessage = "لطفاً ابتدا مجوز بلوتوث را بدهید.") }
            return
        }
        if (!bt.isBluetoothEnabled()) {
            _uiState.update { it.copy(statusBannerMessage = "لطفاً ابتدا بلوتوث گوشی را روشن کنید.") }
            return
        }
        if (telephony.hasPhoneStatePermission()) {
            telephony.startObservingCallState()
        }
        BridgeForegroundService.startHost(app)
    }

    fun connectToPairedHost(device: PairedDeviceUi) {
        if (!bt.hasBluetoothPermissions()) {
            _uiState.update { it.copy(statusBannerMessage = "لطفاً ابتدا مجوز بلوتوث را بدهید.") }
            return
        }
        if (!bt.isBluetoothEnabled()) {
            _uiState.update { it.copy(statusBannerMessage = "لطفاً بلوتوث را روشن کنید.") }
            return
        }
        viewModelScope.launch {
            repo.saveLastDevice(device.name, device.address)
        }
        BridgeForegroundService.connectCompanion(app, device.address)
    }

    fun disconnectBridge() {
        BridgeForegroundService.stopBridge(app)
    }

    fun onDialDigit(digit: String) {
        _uiState.update { it.copy(dialerInput = it.dialerInput + digit) }
    }

    fun onDialBackspace() {
        _uiState.update { cur ->
            if (cur.dialerInput.isNotEmpty()) {
                cur.copy(dialerInput = cur.dialerInput.dropLast(1))
            } else cur
        }
    }

    fun placeCallViaHostSim() {
        val number = _uiState.value.dialerInput.trim()
        if (number.isEmpty()) {
            _uiState.update { it.copy(statusBannerMessage = "لطفاً شماره تلفن را وارد کنید.") }
            return
        }
        val role = _uiState.value.settings.deviceRole
        if (role == DeviceRole.HOST_SIM) {
            telephony.placeDirectCall(number)
            viewModelScope.launch {
                repo.addCallLog(number, "OUTGOING", "تماس مستقیم از گوشی میزبان")
            }
        } else {
            if (bt.linkState.value != BluetoothLinkState.CONNECTED) {
                _uiState.update {
                    it.copy(statusBannerMessage = "برای شماره‌گیری با سیم‌کارت گوشی اول، ابتدا بلوتوث را متصل کنید.")
                }
                return
            }
            // اگر حساب تماس SimBridge فعال باشد، تماس با صفحه تماس خود گوشی برقرار می‌شود
            if (BridgeTelecom.placeCall(app, number)) {
                _uiState.update {
                    it.copy(
                        dialerInput = "",
                        statusBannerMessage = "تماس با $number از طریق سیستم تماس گوشی شروع شد."
                    )
                }
                return
            }
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.DIAL_REQUEST.name,
                    primaryText = number
                )
            )
            viewModelScope.launch {
                repo.addCallLog(number, "OUTGOING", "شماره‌گیری از طریق سیم‌کارت گوشی اول")
            }
            _uiState.update {
                it.copy(statusBannerMessage = "فرمان تماس مستقیم با $number به گوشی اول ارسال شد.")
            }
        }
    }

    fun answerCallViaHost(startVoiceBridge: Boolean = true) {
        coordinator.clearCallAlertState()
        if (_uiState.value.settings.deviceRole == DeviceRole.HOST_SIM) {
            telephony.answerIncomingCall()
        } else if (bt.linkState.value == BluetoothLinkState.CONNECTED) {
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.CALL_CONTROL_REQUEST.name,
                    primaryText = "ANSWER"
                )
            )
            if (startVoiceBridge && !_uiState.value.voiceBridgeActive) {
                bt.startVoiceBridge()
            }
        }
    }

    fun endCallViaHost() {
        coordinator.clearCallAlertState()
        bt.stopVoiceBridge()
        if (_uiState.value.settings.deviceRole == DeviceRole.HOST_SIM) {
            telephony.endCurrentCall()
        } else if (bt.linkState.value == BluetoothLinkState.CONNECTED) {
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.CALL_CONTROL_REQUEST.name,
                    primaryText = "END"
                )
            )
        }
    }

    fun simulateTestIncomingRingToPeer() {
        if (bt.linkState.value != BluetoothLinkState.CONNECTED) {
            _uiState.update {
                it.copy(statusBannerMessage = "ابتدا اتصال بلوتوث را برقرار کنید.")
            }
            return
        }
        bt.sendPacket(
            BridgePacket(
                type = BridgePacketType.CALL_ALERT.name,
                primaryText = CallBridgeState.INCOMING_RINGING.name,
                secondaryText = "09120000000"
            )
        )
    }

    fun dismissIncomingCallAlert() {
        coordinator.clearCallAlertState()
    }

    fun toggleVoiceBridge() {
        if (_uiState.value.voiceBridgeActive) {
            bt.stopVoiceBridge()
        } else {
            BridgeForegroundService.stopIncomingRingAlert(app)
            val started = bt.startVoiceBridge()
            if (started) {
                _uiState.update {
                    it.copy(statusBannerMessage = "کانال صوتی بلوتوث برای مکالمه بین دو گوشی فعال شد.")
                }
            }
        }
    }

    fun onSmsRecipientChanged(value: String) {
        _uiState.update { it.copy(smsRecipientInput = value) }
    }

    fun onSmsBodyChanged(value: String) {
        _uiState.update { it.copy(smsBodyInput = value) }
    }

    fun sendSmsViaHostSim() {
        val recipient = _uiState.value.smsRecipientInput.trim()
        val body = _uiState.value.smsBodyInput.trim()
        if (recipient.isEmpty() || body.isEmpty()) {
            _uiState.update {
                it.copy(statusBannerMessage = "شماره گیرنده و متن پیامک را وارد کنید.")
            }
            return
        }

        val role = _uiState.value.settings.deviceRole
        if (role == DeviceRole.HOST_SIM) {
            val ok = telephony.sendDirectSms(recipient, body)
            viewModelScope.launch {
                repo.addSmsLog(
                    recipient,
                    body,
                    "OUTGOING_RELAY",
                    if (ok) "ارسال مستقیم از سیم‌کارت" else "ارسال ناموفق"
                )
            }
            _uiState.update {
                it.copy(
                    smsBodyInput = "",
                    statusBannerMessage = if (ok) "پیامک با سیم‌کارت ارسال شد." else "خطا در ارسال پیامک"
                )
            }
        } else {
            if (bt.linkState.value != BluetoothLinkState.CONNECTED) {
                _uiState.update {
                    it.copy(statusBannerMessage = "برای ارسال پیامک از سیم‌کارت گوشی اول، بلوتوث باید متصل باشد.")
                }
                return
            }
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.SMS_RELAY_REQUEST.name,
                    primaryText = recipient,
                    secondaryText = body
                )
            )
            SmsStore.insertSent(app, recipient, body)
            viewModelScope.launch {
                repo.addSmsLog(recipient, body, "OUTGOING_RELAY", "ارسال فرمان پیامک به گوشی اول")
            }
            _uiState.update {
                it.copy(
                    smsBodyInput = "",
                    statusBannerMessage = "پیامک برای ارسال مستقیم با سیم‌کارت به گوشی اول فرستاده شد."
                )
            }
        }
    }

    fun requestRecentSmsSyncFromHost() {
        if (bt.linkState.value != BluetoothLinkState.CONNECTED) {
            _uiState.update { it.copy(statusBannerMessage = "ابتدا اتصال بلوتوث را برقرار کنید.") }
            return
        }
        bt.sendPacket(BridgePacket(type = BridgePacketType.SMS_SYNC_REQUEST.name))
        _uiState.update { it.copy(statusBannerMessage = "درخواست همگام‌سازی پیامک‌های اخیر ارسال شد.") }
    }

    fun forwardTextToCompanion(senderLabel: String, messageText: String) {
        val cleanBody = messageText.trim()
        if (cleanBody.isEmpty()) return
        val label = senderLabel.trim().ifEmpty { "پیامک گوشی اول" }
        if (bt.linkState.value == BluetoothLinkState.CONNECTED) {
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.SMS_FORWARD_TEXT.name,
                    primaryText = label,
                    secondaryText = cleanBody
                )
            )
            _uiState.update {
                it.copy(statusBannerMessage = "متن پیامک به گوشی دوم هدایت شد.")
            }
        } else {
            _uiState.update {
                it.copy(statusBannerMessage = "برای ارسال پیامک، اتصال بلوتوث برقرار نیست.")
            }
        }
        viewModelScope.launch {
            repo.addSmsLog(label, cleanBody, "INCOMING_SHARED", "ارسال شده از گوشی اول")
        }
    }

    fun handleIncomingSharedText(sharedText: String) {
        if (sharedText.isBlank()) return
        _uiState.update { it.copy(smsBodyInput = sharedText) }
        if (_uiState.value.settings.deviceRole == DeviceRole.HOST_SIM &&
            bt.linkState.value == BluetoothLinkState.CONNECTED
        ) {
            forwardTextToCompanion("پیام اشتراک‌گذاری شده", sharedText)
        } else {
            _uiState.update {
                it.copy(statusBannerMessage = "متن در بخش پیامک بارگذاری شد.")
            }
        }
    }

    fun selectInternetShareMode(mode: InternetShareMode) {
        viewModelScope.launch {
            repo.setInternetShareMode(mode)
            if (bt.linkState.value == BluetoothLinkState.CONNECTED) {
                bt.sendPacket(
                    BridgePacket(
                        type = BridgePacketType.INTERNET_MODE_SYNC.name,
                        mode = mode.name,
                        primaryText = _uiState.value.hostHotspotSsidInput,
                        secondaryText = _uiState.value.hostHotspotPassInput
                    )
                )
            }
            _uiState.update {
                it.copy(statusBannerMessage = "روش انتقال اینترنت روی «${mode.titleFa}» تنظیم شد.")
            }
        }
    }

    fun onHostHotspotSsidChanged(ssid: String) {
        _uiState.update { it.copy(hostHotspotSsidInput = ssid) }
    }

    fun onHostHotspotPassChanged(pass: String) {
        _uiState.update { it.copy(hostHotspotPassInput = pass) }
    }

    fun shareHotspotCredentialsWithPeer() {
        val ssid = _uiState.value.hostHotspotSsidInput.trim()
        val pass = _uiState.value.hostHotspotPassInput.trim()
        if (ssid.isEmpty()) {
            _uiState.update { it.copy(statusBannerMessage = "نام شبکه وای‌فای (SSID) را وارد کنید.") }
            return
        }
        viewModelScope.launch {
            repo.saveHotspotCredentials(ssid, pass)
            repo.setInternetShareMode(InternetShareMode.WIFI_HOTSPOT)
        }
        if (bt.linkState.value == BluetoothLinkState.CONNECTED) {
            bt.sendPacket(
                BridgePacket(
                    type = BridgePacketType.INTERNET_MODE_SYNC.name,
                    mode = InternetShareMode.WIFI_HOTSPOT.name,
                    primaryText = ssid,
                    secondaryText = pass
                )
            )
            _uiState.update {
                it.copy(statusBannerMessage = "مشخصات هات‌اسپات وای‌فای برای گوشی دوم ارسال شد.")
            }
        } else {
            _uiState.update {
                it.copy(statusBannerMessage = "مشخصات ذخیره شد. پس از اتصال بلوتوث برای گوشی دوم ارسال می‌شود.")
            }
        }
    }

    fun openTetheringSettings() {
        tethering.openTetheringSettings()
    }

    fun openWifiSettings() {
        tethering.openWifiSettings()
    }

    fun openBluetoothSettings() {
        tethering.openBluetoothSettings()
    }

    fun toggleRingOnCompanion(enabled: Boolean) {
        viewModelScope.launch { repo.setRingOnCompanion(enabled) }
    }

    fun toggleVibrateOnCall(enabled: Boolean) {
        viewModelScope.launch { repo.setVibrateOnCall(enabled) }
    }

    /** تست صدای تماس (گوشی اول، وسط یک تماس زنده) */
    fun runCallAudioTest() {
        if (_uiState.value.audioTestRunning) return
        val hasMic = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _uiState.update {
                it.copy(statusBannerMessage = "برای تست صدا، مجوز میکروفون را بدهید.")
            }
            return
        }
        if (coordinator.activeCallState.value != CallBridgeState.ACTIVE_BRIDGE) {
            _uiState.update {
                it.copy(
                    audioTestSummary = "ابتدا با گوشی اول یک تماس برقرار کنید (مثلاً با یک دوست)، از او بخواهید مدام صحبت کند و در همان لحظه دوباره دکمه تست را بزنید."
                )
            }
            return
        }
        // تست و پل صوتی هم‌زمان نمی‌توانند میکروفون را بگیرند
        bt.stopVoiceBridge()
        _uiState.update {
            it.copy(
                audioTestRunning = true,
                audioTestSummary = "در حال تست… از طرف مقابل بخواهید بدون توقف صحبت کند."
            )
        }
        viewModelScope.launch {
            val tester = CallAudioTester(app)
            val results = tester.run { label ->
                _uiState.update {
                    it.copy(audioTestSummary = "در حال تست: $label … (طرف مقابل صحبت کند)")
                }
            }
            val best = tester.chooseBest(results)
            repo.setHostAudioSource(best?.source ?: 0)
            val summary = buildString {
                results.forEach { r ->
                    val state = when {
                        !r.supported -> "پشتیبانی نمی‌شود"
                        r.audible -> "صدا دارد ✓"
                        else -> "بی‌صدا"
                    }
                    append("• ${r.label}: $state\n")
                }
                append("\n")
                if (best != null) {
                    append("روش انتخاب‌شده برای این گوشی: ${best.label}. ذخیره شد و در تماس‌های بعدی استفاده می‌شود.")
                } else {
                    append("هیچ روشی صدا نگرفت. چند نکته: تماس باید واقعاً فعال باشد و طرف مقابل صحبت کند؛ «تماس Wi-Fi» و VoLTE را خاموش کنید؛ صدای بلندگوی گوشی را زیاد کنید؛ دوباره تست کنید. اگر باز هم بی‌صدا بود، این گوشی اجازه انتقال صدای تماس را نمی‌دهد.")
                }
            }
            _uiState.update { it.copy(audioTestRunning = false, audioTestSummary = summary) }
        }
    }

    fun toggleSilenceHostRinger(enabled: Boolean) {
        viewModelScope.launch { repo.setSilenceHostRinger(enabled) }
    }

    fun saveSecurityCode(code: String) {
        val clean = code.trim()
        viewModelScope.launch {
            repo.setSecurityCode(clean)
            bt.securityCode = clean
        }
        _uiState.update {
            it.copy(
                statusBannerMessage = if (clean.isEmpty()) {
                    "کد امنیتی خالی است؛ برای امنیت واقعی در هر دو گوشی یک کد یکسان وارد کنید."
                } else {
                    "کد امنیتی ذخیره شد. در هر دو گوشی باید یکسان باشد؛ بعد دوباره وصل شوید."
                }
            )
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        if (!startSettingsIntent(PowerHelper.requestIgnoreIntent(app))) {
            startSettingsIntent(PowerHelper.appDetailsIntent(app))
        }
    }

    fun openDndAccessSettings() {
        startSettingsIntent(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openCallAccountSettings() {
        if (!startSettingsIntent(BridgeTelecom.callAccountSettingsIntent())) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "تنظیمات را دستی باز کنید: برنامه تلفن ← تنظیمات ← حساب‌های تماس ← SimBridge را روشن کنید."
                )
            }
        }
    }

    fun prefillSms(number: String, body: String) {
        _uiState.update {
            it.copy(
                smsRecipientInput = number.ifBlank { it.smsRecipientInput },
                smsBodyInput = body.ifBlank { it.smsBodyInput },
                statusBannerMessage = "شماره و متن پیامک در بخش «پیامک» بارگذاری شد."
            )
        }
    }

    private fun startSettingsIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearCallLogs() {
        viewModelScope.launch { repo.clearCallLogs() }
    }

    fun clearSmsLogs() {
        viewModelScope.launch { repo.clearSmsLogs() }
    }

    fun dismissBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    override fun onCleared() {
        tethering.stopMonitoring()
        super.onCleared()
    }

    private data class FourTuple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class FiveTuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
