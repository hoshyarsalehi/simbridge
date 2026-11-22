package com.example.bluetooth

import android.content.Context
import com.example.data.preferences.BridgeSettings
import com.example.data.repository.BridgeRepository
import com.example.model.BluetoothLinkState
import com.example.model.BridgePacket
import com.example.model.BridgePacketType
import com.example.model.CallBridgeState
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import com.example.system.ContactsHelper
import com.example.system.RingerSilencer
import com.example.system.SmsStore
import com.example.system.TelephonyBridgeHelper
import com.example.telecom.BridgeTelecom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BridgeCoordinator(
    private val context: Context,
    private val repository: BridgeRepository,
    private val bluetoothManager: BluetoothBridgeManager,
    private val telephonyHelper: TelephonyBridgeHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ringerSilencer = RingerSilencer(context)

    private val _activeCallState = MutableStateFlow(CallBridgeState.IDLE)
    val activeCallState: StateFlow<CallBridgeState> = _activeCallState.asStateFlow()

    private val _incomingCallerLabel = MutableStateFlow("")
    val incomingCallerLabel: StateFlow<String> = _incomingCallerLabel.asStateFlow()

    private val _peerHotspotSsid = MutableStateFlow("")
    val peerHotspotSsid: StateFlow<String> = _peerHotspotSsid.asStateFlow()

    private val _peerHotspotPassword = MutableStateFlow("")
    val peerHotspotPassword: StateFlow<String> = _peerHotspotPassword.asStateFlow()

    private val _eventsBanner = MutableStateFlow<String?>(null)
    val eventsBanner: StateFlow<String?> = _eventsBanner.asStateFlow()

    private var lastHostCallState = CallBridgeState.IDLE

    @Volatile
    private var companionCallState = CallBridgeState.IDLE

    fun start() {
        // اگر برنامه وسط زنگ بسته شده بود، حالت زنگ گوشی برگردانده شود
        ringerSilencer.restore()
        observeSettings()
        observeLinkState()
        observeHostCallEvents()
        observeHostSmsEvents()
        observeBluetoothPackets()
    }

    private fun observeSettings() {
        scope.launch {
            repository.settingsFlow.collect { settings ->
                bluetoothManager.securityCode = settings.securityCode
                bluetoothManager.preferredHostAudioSource = settings.hostAudioSource
            }
        }
        // حساب تماس سیستمی فقط روی گوشی دوم ثبت می‌شود
        scope.launch {
            repository.settingsFlow
                .map { it.deviceRole }
                .distinctUntilChanged()
                .collect { role ->
                    if (role == DeviceRole.COMPANION_NO_SIM) {
                        BridgeTelecom.registerAccount(context)
                    } else {
                        BridgeTelecom.unregisterAccount(context)
                    }
                }
        }
    }

    private fun observeLinkState() {
        scope.launch {
            bluetoothManager.linkState.collect { state ->
                if (state != BluetoothLinkState.CONNECTED) {
                    ringerSilencer.restore()
                    BridgeTelecom.onLinkLost()
                    if (_activeCallState.value != CallBridgeState.IDLE) {
                        _activeCallState.value = CallBridgeState.IDLE
                    }
                    BridgeForegroundService.stopIncomingRingAlert(context)
                }
            }
        }
    }

    private fun observeHostCallEvents() {
        scope.launch {
            telephonyHelper.incomingCallEvents.collect { payload ->
                val settings = repository.settingsFlow.first()
                if (settings.deviceRole != DeviceRole.HOST_SIM) return@collect

                val stateChanged = payload.state != lastHostCallState
                lastHostCallState = payload.state
                _activeCallState.value = payload.state
                if (payload.phoneNumber.isNotBlank()) {
                    _incomingCallerLabel.value = payload.phoneNumber
                }

                val connected = bluetoothManager.linkState.value == BluetoothLinkState.CONNECTED

                // فقط گوشی دوم زنگ بخورد
                if (settings.silenceHostRinger && connected &&
                    payload.state == CallBridgeState.INCOMING_RINGING
                ) {
                    ringerSilencer.silence()
                } else if (payload.state != CallBridgeState.INCOMING_RINGING) {
                    ringerSilencer.restore()
                }

                if (connected) {
                    bluetoothManager.sendPacket(
                        BridgePacket(
                            type = BridgePacketType.CALL_ALERT.name,
                            primaryText = payload.state.name,
                            secondaryText = payload.phoneNumber.ifBlank { "سیم‌کارت گوشی اول" }
                        )
                    )
                }

                if (stateChanged && payload.state == CallBridgeState.INCOMING_RINGING) {
                    repository.addCallLog(
                        phoneNumber = payload.phoneNumber.ifBlank { "تماس ورودی ناشناس" },
                        direction = "INCOMING",
                        status = "ارسال هشدار زنگ به گوشی دوم"
                    )
                }
            }
        }
    }

    private fun observeHostSmsEvents() {
        scope.launch {
            telephonyHelper.incomingSmsEvents.collect { sms ->
                val settings = repository.settingsFlow.first()
                if (settings.deviceRole != DeviceRole.HOST_SIM) return@collect

                val connected = bluetoothManager.linkState.value == BluetoothLinkState.CONNECTED
                if (connected) {
                    bluetoothManager.sendPacket(
                        BridgePacket(
                            type = BridgePacketType.SMS_FORWARD_TEXT.name,
                            timestamp = sms.timestamp,
                            primaryText = sms.sender,
                            secondaryText = sms.body
                        )
                    )
                }
                repository.addSmsLog(
                    peer = sms.sender,
                    body = sms.body,
                    direction = "INCOMING_SHARED",
                    status = if (connected) {
                        "دریافت روی سیم‌کارت و ارسال خودکار به گوشی دوم"
                    } else {
                        "دریافت شد (بلوتوث متصل نبود)"
                    }
                )
            }
        }
    }

    private fun observeBluetoothPackets() {
        scope.launch {
            bluetoothManager.incomingPackets.collect { packet ->
                val settings = repository.settingsFlow.first()
                when (packet.type) {
                    BridgePacketType.CALL_ALERT.name -> handleCallAlert(packet, settings)

                    BridgePacketType.DIAL_REQUEST.name -> {
                        val number = packet.primaryText.trim()
                        if (number.isNotEmpty()) {
                            val ok = telephonyHelper.placeDirectCall(number)
                            repository.addCallLog(
                                phoneNumber = number,
                                direction = "OUTGOING",
                                status = if (ok) {
                                    "تماس مستقیم با سیم‌کارت گوشی اول آغاز شد"
                                } else {
                                    "خطا در برقراری تماس"
                                }
                            )
                            _eventsBanner.value = "برقراری تماس با $number به فرمان گوشی دوم"
                        }
                    }

                    BridgePacketType.CALL_CONTROL_REQUEST.name -> {
                        when (packet.primaryText) {
                            "ANSWER" -> {
                                val answered = telephonyHelper.answerIncomingCall()
                                _eventsBanner.value = if (answered) {
                                    "تماس به فرمان گوشی دوم پاسخ داده شد."
                                } else {
                                    "امکان پاسخ خودکار روی این دستگاه وجود نداشت؛ دستی پاسخ دهید."
                                }
                            }
                            "END" -> {
                                val ended = telephonyHelper.endCurrentCall()
                                _eventsBanner.value = if (ended) {
                                    "تماس به فرمان گوشی دوم قطع شد."
                                } else {
                                    "تماس پایان یافت."
                                }
                            }
                        }
                    }

                    BridgePacketType.SMS_RELAY_REQUEST.name -> {
                        val recipient = packet.primaryText.trim()
                        val body = packet.secondaryText
                        if (recipient.isNotEmpty() && body.isNotEmpty()) {
                            val sent = telephonyHelper.sendDirectSms(recipient, body)
                            val statusText = if (sent) {
                                "پیامک از سیم‌کارت گوشی اول ارسال شد"
                            } else {
                                "ارسال مستقیم پیامک ناموفق بود"
                            }
                            repository.addSmsLog(
                                peer = recipient,
                                body = body,
                                direction = "OUTGOING_RELAY",
                                status = statusText
                            )
                            bluetoothManager.sendPacket(
                                BridgePacket(
                                    type = BridgePacketType.SMS_STATUS_ACK.name,
                                    primaryText = recipient,
                                    secondaryText = statusText,
                                    flag = sent
                                )
                            )
                            _eventsBanner.value = "پیامک ارسالی گوشی دوم به $recipient پردازش شد."
                        }
                    }

                    BridgePacketType.SMS_STATUS_ACK.name -> {
                        _eventsBanner.value = "${packet.primaryText}: ${packet.secondaryText}"
                    }

                    BridgePacketType.SMS_FORWARD_TEXT.name -> handleForwardedSms(packet)

                    BridgePacketType.SMS_SYNC_REQUEST.name -> {
                        if (settings.deviceRole == DeviceRole.HOST_SIM) {
                            val recent = telephonyHelper.readRecentInboxSms(limit = 10)
                            recent.reversed().forEach { sms ->
                                bluetoothManager.sendPacket(
                                    BridgePacket(
                                        type = BridgePacketType.SMS_FORWARD_TEXT.name,
                                        timestamp = sms.timestamp,
                                        primaryText = sms.sender,
                                        secondaryText = sms.body,
                                        flag = true // پیام همگام‌سازی؛ تکراری‌ها نادیده گرفته می‌شوند
                                    )
                                )
                            }
                            _eventsBanner.value = "${recent.size} پیامک اخیر به گوشی دوم ارسال شد."
                        }
                    }

                    BridgePacketType.INTERNET_MODE_SYNC.name -> {
                        val mode = InternetShareMode.entries.firstOrNull { it.name == packet.mode }
                        if (mode != null) {
                            repository.setInternetShareMode(mode)
                        }
                        if (packet.primaryText.isNotBlank()) {
                            _peerHotspotSsid.value = packet.primaryText
                            _peerHotspotPassword.value = packet.secondaryText
                        }
                        mode?.let {
                            _eventsBanner.value = "حالت انتقال اینترنت به «${it.titleFa}» تغییر یافت."
                        }
                    }

                    BridgePacketType.VOICE_BRIDGE_CONTROL.name -> {
                        if (packet.flag) {
                            _eventsBanner.value = "گوشی مقابل پل صوتی بلوتوث را فعال کرد."
                        } else {
                            bluetoothManager.stopVoiceBridge()
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────── تماس روی گوشی دوم ─────────────────────────

    private fun handleCallAlert(packet: BridgePacket, settings: BridgeSettings) {
        val state = CallBridgeState.entries.firstOrNull { it.name == packet.primaryText }
            ?: CallBridgeState.IDLE
        val rawLabel = packet.secondaryText
        val number = if (rawLabel.any { it.isDigit() }) rawLabel else ""
        val displayLabel = ContactsHelper.lookupName(context, number) ?: rawLabel
        companionCallState = state

        when (state) {
            CallBridgeState.INCOMING_RINGING -> {
                _incomingCallerLabel.value = displayLabel
                scope.launch {
                    repository.addCallLog(
                        phoneNumber = displayLabel.ifBlank { "تماس ورودی گوشی اول" },
                        direction = "INCOMING",
                        status = "زنگ خوردن روی گوشی دوم"
                    )
                }
                val viaSystem = BridgeTelecom.addIncomingCall(context, number)
                if (viaSystem) {
                    // اگر سیستم تماس ظرف چند ثانیه تماس را نپذیرفت (حساب فعال نیست)، زنگ خود برنامه فعال می‌شود
                    scope.launch {
                        delay(2000)
                        if (BridgeTelecom.currentConnection == null &&
                            companionCallState == CallBridgeState.INCOMING_RINGING
                        ) {
                            startFallbackAlert(displayLabel, settings)
                        }
                    }
                } else {
                    startFallbackAlert(displayLabel, settings)
                }
            }

            CallBridgeState.ACTIVE_BRIDGE,
            CallBridgeState.IDLE -> {
                _activeCallState.value = state
                _incomingCallerLabel.value = displayLabel
                BridgeForegroundService.stopIncomingRingAlert(context)
                BridgeTelecom.onHostCallState(context, state)
            }

            else -> {
                _activeCallState.value = state
            }
        }
    }

    private fun startFallbackAlert(label: String, settings: BridgeSettings) {
        _activeCallState.value = CallBridgeState.INCOMING_RINGING
        BridgeForegroundService.triggerIncomingRingAlert(
            context = context,
            callerLabel = label,
            playRingtone = settings.ringOnCompanion,
            vibrate = settings.vibrateOnCall
        )
    }

    /** شماره‌گیری از صفحه تماس سیستم گوشی دوم */
    fun requestDial(number: String) {
        bluetoothManager.sendPacket(
            BridgePacket(type = BridgePacketType.DIAL_REQUEST.name, primaryText = number)
        )
        scope.launch {
            repository.addCallLog(number, "OUTGOING", "شماره‌گیری از طریق سیم‌کارت گوشی اول")
        }
    }

    /** پاسخ از صفحه تماس سیستم گوشی دوم */
    fun answerFromTelecom() {
        BridgeForegroundService.stopIncomingRingAlert(context)
        bluetoothManager.sendPacket(
            BridgePacket(type = BridgePacketType.CALL_CONTROL_REQUEST.name, primaryText = "ANSWER")
        )
        bluetoothManager.startVoiceBridge()
    }

    /** پایان یا رد تماس از صفحه تماس سیستم گوشی دوم */
    fun endFromTelecom() {
        BridgeForegroundService.stopIncomingRingAlert(context)
        bluetoothManager.stopVoiceBridge()
        bluetoothManager.sendPacket(
            BridgePacket(type = BridgePacketType.CALL_CONTROL_REQUEST.name, primaryText = "END")
        )
    }

    fun startVoiceForTelecom() {
        bluetoothManager.startVoiceBridge()
    }

    // ───────────────────────── پیامک روی گوشی دوم ─────────────────────────

    private suspend fun handleForwardedSms(packet: BridgePacket) {
        val sender = packet.primaryText.ifBlank { "پیامک گوشی اول" }
        val body = packet.secondaryText
        val result = SmsStore.insertInbox(
            context = context,
            address = sender,
            body = body,
            date = packet.timestamp,
            checkDuplicate = packet.flag
        )
        if (result == SmsStore.Result.DUPLICATE) return

        val name = ContactsHelper.lookupName(context, sender)
        val shownSender = name ?: sender
        repository.addSmsLog(
            peer = shownSender,
            body = body,
            direction = "INCOMING_SHARED",
            status = if (result == SmsStore.Result.STORED) {
                "ذخیره در پیامک‌های گوشی دوم"
            } else {
                "هدایت خودکار از سیم‌کارت گوشی اول"
            }
        )
        BridgeForegroundService.showSmsRelayNotification(
            context = context,
            title = "پیامک جدید از $shownSender",
            body = body
        )
        _eventsBanner.value = if (result == SmsStore.Result.NOT_DEFAULT) {
            "پیامک جدید از $shownSender دریافت شد. برای رفتن پیام‌ها به بخش پیامک گوشی، برنامه را پیامک پیش‌فرض کنید."
        } else {
            "پیامک جدید از $shownSender دریافت شد."
        }
    }

    /** ارسال پیامک گوشی دوم از طریق سیم‌کارت گوشی اول */
    fun relaySmsToHost(recipient: String, body: String): Boolean {
        if (bluetoothManager.linkState.value != BluetoothLinkState.CONNECTED) {
            _eventsBanner.value = "برای ارسال پیامک، بلوتوث باید به گوشی اول متصل باشد."
            return false
        }
        bluetoothManager.sendPacket(
            BridgePacket(
                type = BridgePacketType.SMS_RELAY_REQUEST.name,
                primaryText = recipient,
                secondaryText = body
            )
        )
        SmsStore.insertSent(context, recipient, body)
        scope.launch {
            repository.addSmsLog(recipient, body, "OUTGOING_RELAY", "ارسال فرمان پیامک به گوشی اول")
        }
        return true
    }

    fun clearCallAlertState() {
        _activeCallState.value = CallBridgeState.IDLE
        BridgeForegroundService.stopIncomingRingAlert(context)
    }

    fun clearBanner() {
        _eventsBanner.value = null
    }
}
