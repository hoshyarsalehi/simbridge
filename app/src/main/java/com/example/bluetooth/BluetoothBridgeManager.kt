package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.example.model.BluetoothLinkState
import com.example.model.BridgePacket
import com.example.model.BridgePacketType
import com.example.model.PairedDeviceUi
import com.example.security.BridgeCrypto
import com.example.telecom.BridgeTelecom
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class BluetoothBridgeManager(private val context: Context) {

    companion object {
        val CONTROL_UUID: UUID = UUID.fromString("8a7c3f10-4b22-4c8d-9f11-2a3b4c5d6e7f")
        val AUDIO_UUID: UUID = UUID.fromString("8a7c3f11-4b22-4c8d-9f11-2a3b4c5d6e7f")
        const val CONTROL_SERVICE_NAME = "SimBridgeControl"
        const val AUDIO_SERVICE_NAME = "SimBridgeAudio"

        private const val SAMPLE_RATE = 16000
        private const val HEARTBEAT_MS = 30_000L
        private const val RETRY_START_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val HELLO_PREFIX = "HELLO "
        private const val MAX_VOICE_WAKELOCK_MS = 3L * 60L * 60L * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val packetAdapter = moshi.adapter(BridgePacket::class.java)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Suppress("DEPRECATION")
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    /** کد امنیتی مشترک دو گوشی؛ از تنظیمات به‌روز می‌شود. */
    @Volatile
    var securityCode: String = ""

    /** روش ضبط صدای گوشی اول که «تست صدای تماس» انتخاب کرده؛ ۰ یعنی خودکار. */
    @Volatile
    var preferredHostAudioSource: Int = 0

    private val _linkState = MutableStateFlow(BluetoothLinkState.DISCONNECTED)
    val linkState: StateFlow<BluetoothLinkState> = _linkState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDeviceUi>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDeviceUi>> = _pairedDevices.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _voiceBridgeActive = MutableStateFlow(false)
    val voiceBridgeActive: StateFlow<Boolean> = _voiceBridgeActive.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<BridgePacket>(extraBufferCapacity = 32)
    val incomingPackets: SharedFlow<BridgePacket> = _incomingPackets.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var pendingSocket: BluetoothSocket? = null
    private var controlSocket: BluetoothSocket? = null
    private var controlWriter: BufferedWriter? = null

    private var audioServerSocket: BluetoothServerSocket? = null
    private var audioSocket: BluetoothSocket? = null

    private var sessionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var audioServerJob: Job? = null
    private var voiceStreamingJob: Job? = null

    @Volatile
    private var sessionKey: SecretKeySpec? = null

    @Volatile
    private var userStopped = true

    @Volatile
    private var voiceStarting = false

    private var lastRemoteAddress: String? = null
    private var isHostSession = false

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isBluetoothSupported(): Boolean = adapter != null

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val btAdapter = adapter
        if (btAdapter == null || !hasBluetoothPermissions()) {
            _pairedDevices.value = emptyList()
            return
        }
        val activeAddress = _connectedDeviceAddress.value
        val devices = try {
            btAdapter.bondedDevices?.map { dev ->
                PairedDeviceUi(
                    name = dev.name?.takeIf { it.isNotBlank() } ?: "دستگاه بلوتوث",
                    address = dev.address,
                    isConnected = dev.address == activeAddress &&
                        _linkState.value == BluetoothLinkState.CONNECTED
                )
            }?.sortedByDescending { it.isConnected } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        _pairedDevices.value = devices
    }

    // ───────────────────────── نشست اتصال با تلاش مجدد خودکار ─────────────────────────

    fun startHostServer() {
        if (!checkReady(hostMode = true)) return
        beginSession(host = true, address = null)
    }

    fun connectToHost(address: String) {
        if (!checkReady(hostMode = false)) return
        beginSession(host = false, address = address)
    }

    private fun checkReady(hostMode: Boolean): Boolean {
        val btAdapter = adapter
        if (btAdapter == null) {
            _errorMessage.value = "این دستگاه از بلوتوث پشتیبانی نمی‌کند."
            _linkState.value = BluetoothLinkState.ERROR
            return false
        }
        if (!hasBluetoothPermissions()) {
            _errorMessage.value = if (hostMode) {
                "دسترسی بلوتوث برای راه‌اندازی میزبان لازم است."
            } else {
                "لطفاً مجوز اتصال بلوتوث را صادر کنید."
            }
            _linkState.value = BluetoothLinkState.ERROR
            return false
        }
        if (!btAdapter.isEnabled) {
            _errorMessage.value = "لطفاً ابتدا بلوتوث گوشی را روشن کنید."
            _linkState.value = BluetoothLinkState.ERROR
            return false
        }
        return true
    }

    private fun beginSession(host: Boolean, address: String?) {
        stopSessionInternal()
        userStopped = false
        isHostSession = host
        lastRemoteAddress = address
        _errorMessage.value = null
        _linkState.value = if (host) BluetoothLinkState.LISTENING else BluetoothLinkState.CONNECTING

        sessionJob = scope.launch {
            var backoff = RETRY_START_MS
            var attempt = 0
            while (isActive && !userStopped) {
                val established = if (host) {
                    runHostOnce(attempt == 0)
                } else {
                    runCompanionOnce(address.orEmpty(), attempt == 0)
                }
                if (!isActive || userStopped) break
                attempt++
                backoff = if (established) RETRY_START_MS else (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                _linkState.value =
                    if (host) BluetoothLinkState.LISTENING else BluetoothLinkState.CONNECTING
                delay(backoff)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runHostOnce(reportErrors: Boolean): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) {
            if (reportErrors) _errorMessage.value = "لطفاً ابتدا بلوتوث گوشی را روشن کنید."
            return false
        }
        return try {
            val srv = btAdapter.listenUsingRfcommWithServiceRecord(CONTROL_SERVICE_NAME, CONTROL_UUID)
            serverSocket = srv
            val client = srv.accept()
            try {
                srv.close()
            } catch (_: IOException) {
            }
            serverSocket = null
            handleConnectedSocket(client, isHost = true)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            if (reportErrors) _errorMessage.value = "مجوز بلوتوث تایید نشده است."
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runCompanionOnce(address: String, reportErrors: Boolean): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) {
            if (reportErrors) _errorMessage.value = "بلوتوث خاموش است. لطفاً بلوتوث را روشن کنید."
            return false
        }
        return try {
            try {
                if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            val device: BluetoothDevice = btAdapter.getRemoteDevice(address)
            val socket = connectRfcomm(device)
            handleConnectedSocket(socket, isHost = false)
            true
        } catch (_: IOException) {
            if (reportErrors) {
                _errorMessage.value =
                    "امکان اتصال به گوشی میزبان وجود ندارد. مطمئن شوید SimBridge روی گوشی اول در حالت میزبان فعال است. تلاش مجدد به‌صورت خودکار ادامه دارد."
            }
            refreshPairedDevices()
            false
        } catch (_: IllegalArgumentException) {
            _errorMessage.value = "آدرس بلوتوث معتبر نیست."
            userStopped = true
            _linkState.value = BluetoothLinkState.ERROR
            false
        } catch (_: SecurityException) {
            _errorMessage.value = "دسترسی بلوتوث صادر نشده است."
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRfcomm(device: BluetoothDevice): BluetoothSocket {
        return try {
            val first = device.createRfcommSocketToServiceRecord(CONTROL_UUID)
            pendingSocket = first
            first.connect()
            pendingSocket = null
            first
        } catch (e: IOException) {
            try {
                pendingSocket?.close()
            } catch (_: IOException) {
            }
            if (userStopped) throw e
            val second = device.createInsecureRfcommSocketToServiceRecord(CONTROL_UUID)
            pendingSocket = second
            second.connect()
            pendingSocket = null
            second
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleConnectedSocket(socket: BluetoothSocket, isHost: Boolean) {
        controlSocket = socket
        lastRemoteAddress = socket.remoteDevice?.address ?: lastRemoteAddress
        val deviceName = try {
            socket.remoteDevice?.name?.takeIf { it.isNotBlank() } ?: "گوشی متصل"
        } catch (_: SecurityException) {
            "گوشی متصل"
        }

        try {
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

            // تبادل عدد تصادفی برای ساخت کلید رمزنگاری این نشست
            val myNonce = BridgeCrypto.newNonce()
            writer.write(HELLO_PREFIX + Base64.encodeToString(myNonce, Base64.NO_WRAP))
            writer.newLine()
            writer.flush()

            val helloLine = reader.readLine() ?: return
            if (!helloLine.startsWith(HELLO_PREFIX)) {
                _errorMessage.value = "نسخه برنامه روی دو گوشی یکسان نیست؛ هر دو را به‌روزرسانی کنید."
                userStopped = true
                return
            }
            val peerNonce = Base64.decode(helloLine.removePrefix(HELLO_PREFIX), Base64.NO_WRAP)
            val key = if (isHost) {
                BridgeCrypto.deriveKey(securityCode, myNonce, peerNonce)
            } else {
                BridgeCrypto.deriveKey(securityCode, peerNonce, myNonce)
            }
            sessionKey = key
            controlWriter = writer

            _connectedDeviceName.value = deviceName
            _connectedDeviceAddress.value = socket.remoteDevice?.address
            _linkState.value = BluetoothLinkState.CONNECTED
            _errorMessage.value = null
            refreshPairedDevices()

            if (isHost) {
                startAudioServerListener()
            }
            startHeartbeat(socket)

            sendPacket(
                BridgePacket(
                    type = BridgePacketType.ROLE_ANNOUNCE.name,
                    primaryText = if (isHost) "HOST" else "COMPANION"
                )
            )

            var badPackets = 0
            while (true) {
                val line = reader.readLine() ?: break
                val plain = BridgeCrypto.decrypt(key, line)
                if (plain == null) {
                    badPackets++
                    if (badPackets >= 2) {
                        _errorMessage.value =
                            "کد امنیتی دو گوشی یکسان نیست. در هر دو گوشی یک کد وارد کنید و دوباره وصل شوید."
                        userStopped = true
                        break
                    }
                    continue
                }
                val packet = try {
                    packetAdapter.fromJson(plain)
                } catch (_: Exception) {
                    null
                }
                if (packet != null) {
                    badPackets = 0
                    if (packet.type != BridgePacketType.HEARTBEAT.name) {
                        _incomingPackets.emit(packet)
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
            stopVoiceBridgeInternal(notifyPeer = false)
            closeControlStreams()
            sessionKey = null
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            if (_linkState.value == BluetoothLinkState.CONNECTED) {
                _linkState.value = BluetoothLinkState.DISCONNECTED
            }
            refreshPairedDevices()
        }
    }

    /** ارسال پیام خیلی کوچک هر ۳۰ ثانیه تا ارتباط بلوتوث بیکار تشخیص داده نشود و قطع نشود. */
    private fun startHeartbeat(socket: BluetoothSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val ok = writePacketNow(BridgePacket(type = BridgePacketType.HEARTBEAT.name), quiet = true)
                if (!ok) {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    break
                }
            }
        }
    }

    private fun writePacketNow(packet: BridgePacket, quiet: Boolean): Boolean {
        val writer = controlWriter ?: return false
        val key = sessionKey ?: return false
        return try {
            val line = BridgeCrypto.encrypt(key, packetAdapter.toJson(packet))
            synchronized(writer) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
            true
        } catch (_: IOException) {
            if (!quiet) _errorMessage.value = "ارسال فرمان بلوتوث ناموفق بود."
            false
        } catch (_: Exception) {
            false
        }
    }

    fun sendPacket(packet: BridgePacket) {
        scope.launch {
            writePacketNow(packet, quiet = false)
        }
    }

    // ───────────────────────────── پل صوتی ─────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAudioServerListener() {
        val btAdapter = adapter ?: return
        if (!hasBluetoothPermissions()) return
        audioServerJob?.cancel()
        audioServerJob = scope.launch {
            while (isActive && _linkState.value == BluetoothLinkState.CONNECTED) {
                try {
                    val srv = btAdapter.listenUsingRfcommWithServiceRecord(
                        AUDIO_SERVICE_NAME,
                        AUDIO_UUID
                    )
                    audioServerSocket = srv
                    val incomingAudio = srv.accept()
                    try {
                        srv.close()
                    } catch (_: IOException) {
                    }
                    audioServerSocket = null
                    if (incomingAudio != null) {
                        startVoiceStreamingOnSocket(incomingAudio)
                    }
                } catch (_: IOException) {
                    break
                } catch (_: SecurityException) {
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startVoiceBridge(): Boolean {
        if (_linkState.value != BluetoothLinkState.CONNECTED) {
            _errorMessage.value = "ابتدا دو گوشی را از طریق بلوتوث متصل کنید."
            return false
        }
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _errorMessage.value = "برای پل صوتی بلوتوث، مجوز میکروفون لازم است."
            return false
        }
        if (_voiceBridgeActive.value || voiceStarting) return true

        sendPacket(BridgePacket(type = BridgePacketType.VOICE_BRIDGE_CONTROL.name, flag = true))

        if (!isHostSession) {
            val address = lastRemoteAddress ?: _connectedDeviceAddress.value ?: return false
            val btAdapter = adapter ?: return false
            voiceStarting = true
            scope.launch {
                try {
                    val dev = btAdapter.getRemoteDevice(address)
                    val sock = dev.createRfcommSocketToServiceRecord(AUDIO_UUID)
                    sock.connect()
                    startVoiceStreamingOnSocket(sock)
                } catch (_: IOException) {
                    _errorMessage.value = "برقراری کانال صوتی بلوتوث ممکن نشد."
                    _voiceBridgeActive.value = false
                } catch (_: SecurityException) {
                    _voiceBridgeActive.value = false
                } finally {
                    voiceStarting = false
                }
            }
        }
        return true
    }

    fun stopVoiceBridge() {
        stopVoiceBridgeInternal(notifyPeer = true)
    }

    private fun stopVoiceBridgeInternal(notifyPeer: Boolean) {
        if (notifyPeer && _linkState.value == BluetoothLinkState.CONNECTED) {
            sendPacket(BridgePacket(type = BridgePacketType.VOICE_BRIDGE_CONTROL.name, flag = false))
        }
        _voiceBridgeActive.value = false
        voiceStreamingJob?.cancel()
        voiceStreamingJob = null
        try {
            audioSocket?.close()
        } catch (_: IOException) {
        }
        audioSocket = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startVoiceStreamingOnSocket(socket: BluetoothSocket) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            return
        }

        audioSocket = socket
        _voiceBridgeActive.value = true
        voiceStreamingJob?.cancel()
        val hostSide = isHostSession

        voiceStreamingJob = scope.launch {
            val minRecBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val previousSpeaker = audioMgr?.isSpeakerphoneOn
            var modeChanged = false

            // فقط در طول مکالمه CPU بیدار نگه داشته می‌شود (برای مصرف کم باتری)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimBridge:voice")
            try {
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(MAX_VOICE_WAKELOCK_MS)
            } catch (_: Exception) {
            }

            try {
                recorder = createRecorder(hostSide, minRecBuf)
                    ?: throw IllegalStateException("recorder_unavailable")
                player = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minPlayBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (hostSide) {
                    // گوشی اول: صدا از بلندگو پخش و با میکروفون گرفته می‌شود
                    audioMgr?.isSpeakerphoneOn = true
                } else if (!BridgeTelecom.hasActiveConnection()) {
                    // اگر صفحه تماس سیستم فعال باشد، خود سیستم حالت صدا را مدیریت می‌کند
                    audioMgr?.mode = AudioManager.MODE_IN_COMMUNICATION
                    modeChanged = true
                }

                player.play()

                val sendJob = launch {
                    val out = socket.outputStream
                    val buf = ByteArray(1024)
                    var checkedBuffers = 0
                    var peak = 0
                    while (isActive && _voiceBridgeActive.value && socket.isConnected) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read > 0) {
                            out.write(buf, 0, read)
                            // گوشی اول: اگر در ~۲ ثانیه اول هیچ صدایی ضبط نشود، به کاربر خبر بده
                            if (hostSide && checkedBuffers < 60) {
                                var i = 0
                                while (i + 1 < read) {
                                    val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                                    val amp = if (sample < 0) -sample else sample
                                    if (amp > peak) peak = amp
                                    i += 2
                                }
                                checkedBuffers++
                                if (checkedBuffers == 60 && peak < 8) {
                                    _errorMessage.value =
                                        "میکروفون گوشی اول در طول تماس بی‌صدا است؛ اندروید این گوشی اجازه انتقال صدای تماس را به برنامه نمی‌دهد."
                                }
                            }
                        }
                    }
                }

                val receiveJob = launch {
                    val input = socket.inputStream
                    val buf = ByteArray(1024)
                    while (isActive && _voiceBridgeActive.value && socket.isConnected) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        player.write(buf, 0, read)
                    }
                }

                receiveJob.join()
                sendJob.cancel()
            } catch (_: Exception) {
                if (recorder == null) {
                    _errorMessage.value =
                        "شروع ضبط صدا ممکن نشد؛ میکروفون در اختیار تماس است یا مجوز میکروفون داده نشده."
                }
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {
                }
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
                try {
                    if (hostSide) {
                        previousSpeaker?.let { audioMgr?.isSpeakerphoneOn = it }
                    } else if (modeChanged) {
                        audioMgr?.mode = AudioManager.MODE_NORMAL
                    }
                } catch (_: Exception) {
                }
                try {
                    wakeLock?.let { if (it.isHeld) it.release() }
                } catch (_: Exception) {
                }
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                _voiceBridgeActive.value = false
            }
        }
    }

    /**
     * گوشی اول: اول صدای طرف مقابل تماس مستقیم گرفته می‌شود (اگر اندروید گوشی اجازه دهد)،
     * وگرنه میکروفون معمولی. گوشی دوم: میکروفون مخصوص مکالمه.
     */
    private fun createRecorder(hostSide: Boolean, bufferSize: Int): AudioRecord? {
        val sources = if (hostSide) {
            val defaults = listOf(
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            val preferred = preferredHostAudioSource
            if (preferred != 0) {
                listOf(preferred) + defaults.filter { it != preferred }
            } else {
                defaults
            }
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return candidate
                    }
                }
            } catch (_: Exception) {
            }
            try {
                candidate?.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ───────────────────────────── قطع اتصال ─────────────────────────────

    /** قطع اتصال به درخواست کاربر؛ تلاش مجدد خودکار متوقف می‌شود. */
    fun disconnect() {
        stopSessionInternal()
        _linkState.value = BluetoothLinkState.DISCONNECTED
        refreshPairedDevices()
    }

    private fun stopSessionInternal() {
        userStopped = true
        sessionJob?.cancel()
        sessionJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        audioServerJob?.cancel()
        audioServerJob = null
        stopVoiceBridgeInternal(notifyPeer = false)
        try {
            pendingSocket?.close()
        } catch (_: IOException) {
        }
        pendingSocket = null
        closeControlStreams()
        sessionKey = null
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
    }

    private fun closeControlStreams() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null

        try {
            controlWriter?.close()
        } catch (_: IOException) {
        }
        controlWriter = null

        try {
            controlSocket?.close()
        } catch (_: IOException) {
        }
        controlSocket = null

        try {
            audioServerSocket?.close()
        } catch (_: IOException) {
        }
        audioServerSocket = null
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
