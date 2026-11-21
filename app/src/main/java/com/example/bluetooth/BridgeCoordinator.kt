package com.example.bluetooth

import android.content.Context
import com.example.data.repository.BridgeRepository
import com.example.model.BluetoothLinkState
import com.example.model.BridgePacket
import com.example.model.BridgePacketType
import com.example.model.CallBridgeState
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import com.example.system.TelephonyBridgeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BridgeCoordinator(
    private val context: Context,
    private val repository: BridgeRepository,
    private val bluetoothManager: BluetoothBridgeManager,
    private val telephonyHelper: TelephonyBridgeHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun start() {
        observeHostCallEvents()
        observeHostSmsEvents()
        observeBluetoothPackets()
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

                if (bluetoothManager.linkState.value == BluetoothLinkState.CONNECTED) {
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
                    BridgePacketType.CALL_ALERT.name -> {
                        val state = CallBridgeState.entries.firstOrNull {
                            it.name == packet.primaryText
                        } ?: CallBridgeState.IDLE

                        _activeCallState.value = state
                        _incomingCallerLabel.value = packet.secondaryText

                        when (state) {
                            CallBridgeState.INCOMING_RINGING -> {
                                BridgeForegroundService.triggerIncomingRingAlert(
                                    context = context,
                                    callerLabel = packet.secondaryText,
                                    playRingtone = settings.ringOnCompanion,
                                    vibrate = settings.vibrateOnCall
                                )
                                repository.addCallLog(
                                    phoneNumber = packet.secondaryText.ifBlank { "تماس ورودی گوشی اول" },
                                    direction = "INCOMING",
                                    status = "زنگ خوردن روی گوشی دوم"
                                )
                            }
                            CallBridgeState.ACTIVE_BRIDGE,
                            CallBridgeState.IDLE -> {
                                BridgeForegroundService.stopIncomingRingAlert(context)
                            }
                            else -> Unit
                        }
                    }

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

                    BridgePacketType.SMS_FORWARD_TEXT.name -> {
                        val sender = packet.primaryText.ifBlank { "پیامک گوشی اول" }
                        val body = packet.secondaryText
                        repository.addSmsLog(
                            peer = sender,
                            body = body,
                            direction = "INCOMING_SHARED",
                            status = "هدایت خودکار از سیم‌کارت گوشی اول"
                        )
                        BridgeForegroundService.showSmsRelayNotification(
                            context = context,
                            title = "پیامک جدید از $sender",
                            body = body
                        )
                        _eventsBanner.value = "پیامک جدید از $sender دریافت شد."
                    }

                    BridgePacketType.SMS_SYNC_REQUEST.name -> {
                        if (settings.deviceRole == DeviceRole.HOST_SIM) {
                            val recent = telephonyHelper.readRecentInboxSms(limit = 10)
                            recent.reversed().forEach { sms ->
                                bluetoothManager.sendPacket(
                                    BridgePacket(
                                        type = BridgePacketType.SMS_FORWARD_TEXT.name,
                                        timestamp = sms.timestamp,
                                        primaryText = sms.sender,
                                        secondaryText = sms.body
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

    fun clearCallAlertState() {
        _activeCallState.value = CallBridgeState.IDLE
        BridgeForegroundService.stopIncomingRingAlert(context)
    }

    fun clearBanner() {
        _eventsBanner.value = null
    }
}
