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
import androidx.core.content.ContextCompat
import com.example.model.BluetoothLinkState
import com.example.model.BridgePacket
import com.example.model.BridgePacketType
import com.example.model.PairedDeviceUi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class BluetoothBridgeManager(private val context: Context) {

    companion object {
        val CONTROL_UUID: UUID = UUID.fromString("8a7c3f10-4b22-4c8d-9f11-2a3b4c5d6e7f")
        val AUDIO_UUID: UUID = UUID.fromString("8a7c3f11-4b22-4c8d-9f11-2a3b4c5d6e7f")
        const val CONTROL_SERVICE_NAME = "SimBridgeControl"
        const val AUDIO_SERVICE_NAME = "SimBridgeAudio"

        private const val SAMPLE_RATE = 16000
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
    private var controlSocket: BluetoothSocket? = null
    private var controlWriter: BufferedWriter? = null

    private var audioServerSocket: BluetoothServerSocket? = null
    private var audioSocket: BluetoothSocket? = null

    private var controlJob: Job? = null
    private var audioServerJob: Job? = null
    private var voiceStreamingJob: Job? = null
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

    @SuppressLint("MissingPermission")
    fun startHostServer() {
        val btAdapter = adapter
        if (btAdapter == null) {
            _errorMessage.value = "این دستگاه از بلوتوث پشتیبانی نمی‌کند."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }
        if (!hasBluetoothPermissions()) {
            _errorMessage.value = "دسترسی بلوتوث برای راه‌اندازی میزبان لازم است."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }
        if (!btAdapter.isEnabled) {
            _errorMessage.value = "لطفاً ابتدا بلوتوث گوشی را روشن کنید."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }

        disconnect()
        isHostSession = true
        _errorMessage.value = null
        _linkState.value = BluetoothLinkState.LISTENING

        controlJob = scope.launch {
            try {
                val srv = btAdapter.listenUsingRfcommWithServiceRecord(
                    CONTROL_SERVICE_NAME,
                    CONTROL_UUID
                )
                serverSocket = srv
                val client = srv.accept()
                try {
                    srv.close()
                } catch (_: IOException) {
                }
                serverSocket = null

                if (client != null) {
                    handleConnectedSocket(client, isHost = true)
                }
            } catch (e: IOException) {
                if (isActive && _linkState.value != BluetoothLinkState.DISCONNECTED) {
                    _errorMessage.value = "اتصال میزبان قطع شد یا با خطا مواجه گردید."
                    _linkState.value = BluetoothLinkState.DISCONNECTED
                }
            } catch (_: SecurityException) {
                _errorMessage.value = "مجوز بلوتوث تایید نشده است."
                _linkState.value = BluetoothLinkState.ERROR
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(address: String) {
        val btAdapter = adapter
        if (btAdapter == null) {
            _errorMessage.value = "بلوتوث روی این گوشی در دسترس نیست."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }
        if (!hasBluetoothPermissions()) {
            _errorMessage.value = "لطفاً مجوز اتصال بلوتوث را صادر کنید."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }
        if (!btAdapter.isEnabled) {
            _errorMessage.value = "بلوتوث خاموش است. لطفاً بلوتوث را روشن کنید."
            _linkState.value = BluetoothLinkState.ERROR
            return
        }

        disconnect()
        isHostSession = false
        lastRemoteAddress = address
        _errorMessage.value = null
        _linkState.value = BluetoothLinkState.CONNECTING

        controlJob = scope.launch {
            try {
                try {
                    if (btAdapter.isDiscovering) {
                        btAdapter.cancelDiscovery()
                    }
                } catch (_: SecurityException) {
                }

                val device: BluetoothDevice = btAdapter.getRemoteDevice(address)
                val socket = try {
                    device.createRfcommSocketToServiceRecord(CONTROL_UUID).apply { connect() }
                } catch (_: IOException) {
                    device.createInsecureRfcommSocketToServiceRecord(CONTROL_UUID).apply { connect() }
                }
                handleConnectedSocket(socket, isHost = false)
            } catch (e: IOException) {
                _errorMessage.value =
                    "امکان اتصال به گوشی میزبان وجود ندارد. مطمئن شوید SimBridge روی گوشی اول در حالت میزبان فعال است."
                _linkState.value = BluetoothLinkState.DISCONNECTED
                refreshPairedDevices()
            } catch (_: IllegalArgumentException) {
                _errorMessage.value = "آدرس بلوتوث معتبر نیست."
                _linkState.value = BluetoothLinkState.ERROR
            } catch (_: SecurityException) {
                _errorMessage.value = "دسترسی بلوتوث صادر نشده است."
                _linkState.value = BluetoothLinkState.ERROR
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleConnectedSocket(socket: BluetoothSocket, isHost: Boolean) {
        controlSocket = socket
        lastRemoteAddress = socket.remoteDevice?.address
        val deviceName = try {
            socket.remoteDevice?.name?.takeIf { it.isNotBlank() } ?: "گوشی متصل"
        } catch (_: SecurityException) {
            "گوشی متصل"
        }
        _connectedDeviceName.value = deviceName
        _connectedDeviceAddress.value = socket.remoteDevice?.address
        _linkState.value = BluetoothLinkState.CONNECTED
        _errorMessage.value = null
        refreshPairedDevices()

        if (isHost) {
            startAudioServerListener()
        }

        try {
            controlWriter = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

            sendPacket(
                BridgePacket(
                    type = BridgePacketType.ROLE_ANNOUNCE.name,
                    primaryText = if (isHost) "HOST" else "COMPANION"
                )
            )

            while (socket.isConnected) {
                val line = reader.readLine() ?: break
                val packet = try {
                    packetAdapter.fromJson(line)
                } catch (_: Exception) {
                    null
                }
                if (packet != null) {
                    _incomingPackets.emit(packet)
                }
            }
        } catch (_: IOException) {
        } finally {
            stopVoiceBridgeInternal(notifyPeer = false)
            closeControlStreams()
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            if (_linkState.value == BluetoothLinkState.CONNECTED) {
                _linkState.value = BluetoothLinkState.DISCONNECTED
            }
            refreshPairedDevices()
        }
    }

    fun sendPacket(packet: BridgePacket) {
        scope.launch {
            val writer = controlWriter ?: return@launch
            try {
                val json = packetAdapter.toJson(packet)
                synchronized(writer) {
                    writer.write(json)
                    writer.newLine()
                    writer.flush()
                }
            } catch (_: IOException) {
                _errorMessage.value = "ارسال فرمان بلوتوث ناموفق بود."
            }
        }
    }

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
        if (_voiceBridgeActive.value) return true

        sendPacket(BridgePacket(type = BridgePacketType.VOICE_BRIDGE_CONTROL.name, flag = true))

        if (!isHostSession) {
            val address = lastRemoteAddress ?: _connectedDeviceAddress.value ?: return false
            val btAdapter = adapter ?: return false
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

            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minRecBuf
                )
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

                val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioMgr?.mode = AudioManager.MODE_IN_COMMUNICATION

                recorder.startRecording()
                player.play()

                val sendJob = launch {
                    val out = socket.outputStream
                    val buf = ByteArray(1024)
                    while (isActive && _voiceBridgeActive.value && socket.isConnected) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read > 0) {
                            out.write(buf, 0, read)
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
                    socket.close()
                } catch (_: IOException) {
                }
                _voiceBridgeActive.value = false
            }
        }
    }

    fun disconnect() {
        stopVoiceBridgeInternal(notifyPeer = false)
        audioServerJob?.cancel()
        audioServerJob = null
        controlJob?.cancel()
        controlJob = null
        closeControlStreams()
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _linkState.value = BluetoothLinkState.DISCONNECTED
        refreshPairedDevices()
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
