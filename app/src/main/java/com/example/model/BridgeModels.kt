package com.example.model

import com.squareup.moshi.JsonClass

enum class DeviceRole(val titleFa: String, val subtitleFa: String) {
    HOST_SIM(
        titleFa = "گوشی اول (دارای سیم‌کارت)",
        subtitleFa = "اندروید ۶ به بالا — میزبان تماس، پیامک و اینترنت"
    ),
    COMPANION_NO_SIM(
        titleFa = "گوشی دوم (بدون سیم‌کارت)",
        subtitleFa = "اندروید جدید — پاسخ به تماس، شماره‌گیری، پیامک و اینترنت"
    )
}

enum class InternetShareMode(val titleFa: String, val subtitleFa: String) {
    BLUETOOTH_PAN(
        titleFa = "اینترنت از طریق بلوتوث (PAN)",
        subtitleFa = "انتقال مستقیم اینترنت از طریق بلوتوث بدون نیاز به هات‌اسپات وای‌فای"
    ),
    WIFI_HOTSPOT(
        titleFa = "اینترنت از طریق وای‌فای (Hotspot)",
        subtitleFa = "انتقال پرسرعت از طریق هات‌اسپات وای‌فای و ارسال خودکار رمز بین دو گوشی"
    )
}

enum class BluetoothLinkState(val titleFa: String) {
    DISCONNECTED("قطع شده"),
    LISTENING("آماده پذیرش اتصال (میزبان)"),
    CONNECTING("در حال اتصال…"),
    CONNECTED("متصل از طریق بلوتوث"),
    ERROR("خطا در اتصال بلوتوث")
}

enum class CallBridgeState {
    IDLE,
    INCOMING_RINGING,
    DIALING_OUT,
    ACTIVE_BRIDGE
}

enum class BridgePacketType {
    HEARTBEAT,
    CALL_ALERT,
    DIAL_REQUEST,
    CALL_CONTROL_REQUEST,
    SMS_RELAY_REQUEST,
    SMS_FORWARD_TEXT,
    SMS_STATUS_ACK,
    SMS_SYNC_REQUEST,
    INTERNET_MODE_SYNC,
    VOICE_BRIDGE_CONTROL,
    ROLE_ANNOUNCE
}

@JsonClass(generateAdapter = true)
data class BridgePacket(
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val primaryText: String = "",
    val secondaryText: String = "",
    val flag: Boolean = false,
    val mode: String = ""
)

data class PairedDeviceUi(
    val name: String,
    val address: String,
    val isConnected: Boolean = false
)

data class CallEventUi(
    val id: Long = 0L,
    val phoneNumber: String,
    val direction: String, // "INCOMING" | "OUTGOING"
    val status: String,
    val timestamp: Long
)

data class SmsEventUi(
    val id: Long = 0L,
    val peer: String,
    val body: String,
    val direction: String, // "OUTGOING_RELAY" | "INCOMING_SHARED"
    val status: String,
    val timestamp: Long
)

data class NetworkTelemetryUi(
    val isInternetConnected: Boolean = false,
    val transportType: String = "OFFLINE", // "WIFI" | "CELLULAR" | "BLUETOOTH" | "OFFLINE"
    val transportLabelFa: String = "بدون اتصال اینترنت",
    val wifiSsid: String? = null,
    val localIpAddress: String? = null,
    val isBluetoothPanConnected: Boolean = false
)
