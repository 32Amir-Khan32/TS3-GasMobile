package com.halla.mobile

import android.content.Context
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.audio.opus.OpusMicrophoneCapture
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3jSessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import kotlin.concurrent.thread

/** Real TS3 session transport based on the ts3j client used by ts3-mobile. */
object Ts3ConnectionManager {
    @Volatile private var session: Ts3SessionClient? = null
    @Volatile private var connected = false
    @Volatile private var microphone: OpusMicrophoneCapture? = null
    @Volatile private var audioPlayer: OpusAudioPlayer? = null
    @Volatile private var microphoneMuted = false
    @Volatile private var speakersMuted = false
    @Volatile private var transmissionMode = 0
    @Volatile private var voiceSensitivity = 50
    @Volatile private var microphoneGainDb = 0
    @Volatile private var noiseSuppressionEnabled = true
    @Volatile private var echoCancellationEnabled = true
    @Volatile private var pttPressed = false
    @Volatile private var currentChannelId = 0
    private val participantVolumeDb = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun isConnected(): Boolean = connected

    fun connect(
        context: Context,
        host: String,
        port: Int,
        nickname: String,
        password: String,
        onStatus: (ConnectionStatus) -> Unit,
        onSnapshot: (SessionSnapshot) -> Unit,
        onChat: (Int, String, String, Int, String) -> Unit = { _, _, _, _, _ -> }
    ) {
        val appContext = context.applicationContext
        val client = Ts3jSessionClient()
        synchronized(this) {
            session?.close()
            microphone?.close()
            audioPlayer?.close()
            session = client
            microphone = null
            audioPlayer = null
            connected = false
        }
        thread(name = "ts3-connect") {
            try {
                val prefs = appContext.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
                transmissionMode = prefs.getInt("transmission_mode", 0).coerceIn(0, 2)
                voiceSensitivity = prefs.getInt("vad_sensitivity", 50).coerceIn(0, 100)
                microphoneGainDb = prefs.getInt("mic_gain_db", 0).coerceIn(0, 30)
                noiseSuppressionEnabled = prefs.getBoolean("noise_suppression", true)
                echoCancellationEnabled = prefs.getBoolean("echo_cancellation", true)
                microphoneMuted = appContext.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                    .getBoolean(HallaService.PREF_MIC_MUTED, false)
                speakersMuted = appContext.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                    .getBoolean(HallaService.PREF_SPK_MUTED, false)
                pttPressed = false
                val player = try {
                    OpusAudioPlayer(appContext).also { it.start(); it.setMuted(speakersMuted) }
                } catch (audioError: Throwable) {
                    android.util.Log.w("TS3-GasMobile", "Audio playback unavailable; connecting without playback", audioError)
                    null
                }
                audioPlayer = player
                val mic = try {
                    OpusMicrophoneCapture(appContext) { error ->
                        android.util.Log.e("TS3-GasMobile", "Microphone capture failed", error)
                    }.also { it.start() }
                } catch (error: Throwable) {
                    android.util.Log.w("TS3-GasMobile", "Microphone unavailable; connecting receive-only", error)
                    null
                }
                microphone = mic
                mic?.voiceActivationEnabled = transmissionMode == 0
                mic?.voiceActivationThreshold = (voiceSensitivity * 3.0).coerceAtLeast(15.0)
                mic?.micGainDb = microphoneGainDb
                mic?.setNoiseSuppressionEnabled(noiseSuppressionEnabled)
                mic?.setEchoCancellationEnabled(echoCancellationEnabled)
                updateMicrophoneGate()
                client.setVoiceSource(mic)
                val key = "ts3-identity-material"
                var identity = HallaCore.readSecret(appContext, key)
                if (identity.isBlank()) {
                    identity = Ts3IdentityCodec.generate()
                    HallaCore.storeSecret(appContext, key, identity)
                }
                client.connect(
                    ServerConfig(host = host, port = port, nickname = nickname, password = password),
                    identity,
                    object : Ts3SessionListener {
                        override fun onStatusChanged(status: ConnectionStatus) {
                            connected = status.phase == ConnectionPhase.CONNECTED
                            onStatus(status)
                        }
                        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
                            snapshot.currentChannelId?.let { currentChannelId = it }
                            onSnapshot(snapshot)
                        }
                        override fun onTextMessage(fromUserId: Int, fromName: String, targetMode: String, targetClientId: Int, message: String) {
                            val scope = when (targetMode.uppercase()) {
                                "SERVER" -> "server"
                                "CLIENT" -> "private"
                                else -> "channel"
                            }
                            onChat(fromUserId, fromName, scope, targetClientId, message)
                        }
                        override fun onVoiceFrame(frame: VoiceFrame) {
                            audioPlayer?.submit(frame)
                        }
                    }
                )
            } catch (error: Throwable) {
                connected = false
                runCatching { client.close() }
                runCatching { microphone?.close() }
                runCatching { audioPlayer?.close() }
                microphone = null
                audioPlayer = null
                if (session === client) session = null
                onStatus(ConnectionStatus(ConnectionPhase.ERROR, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun joinChannel(channelId: Int, password: String = "") { session?.joinChannel(channelId, password) }

    fun sendChat(scope: String, targetClientId: Int, message: String) {
        val current = session ?: error("Not connected to a TeamSpeak server")
        when (scope) {
            "server" -> current.sendServerMessage(message)
            "private" -> current.sendPrivateMessage(targetClientId, message)
            else -> current.sendChannelMessage(currentChannelId, message)
        }
    }

    private fun updateMicrophoneGate() {
        microphone?.let { mic ->
            mic.voiceActivationEnabled = transmissionMode == 0
            mic.voiceActivationThreshold = (voiceSensitivity * 3.0).coerceAtLeast(15.0)
            mic.micGainDb = microphoneGainDb
            mic.transmissionEnabled = !microphoneMuted &&
                (transmissionMode != 1 || pttPressed)
        }
    }

    fun setMicMuted(muted: Boolean) {
        microphoneMuted = muted
        updateMicrophoneGate()
    }

    fun setSpeakersMuted(muted: Boolean) {
        speakersMuted = muted
        audioPlayer?.setMuted(muted)
    }

    fun setUserVolumeDb(userId: Int, volumeDb: Int) {
        if (userId <= 0) return
        val db = volumeDb.coerceIn(-60, 30)
        if (db == 0) participantVolumeDb.remove(userId) else participantVolumeDb[userId] = db
        val gains = participantVolumeDb.mapValues { (_, value) ->
            Math.pow(10.0, value / 20.0).toFloat()
        }
        audioPlayer?.replaceParticipantGains(gains)
    }

    fun setPtt(pressed: Boolean) {
        pttPressed = pressed
        updateMicrophoneGate()
    }

    fun setTransmissionMode(mode: Int) {
        transmissionMode = mode.coerceIn(0, 2)
        if (transmissionMode != 1) pttPressed = false
        updateMicrophoneGate()
    }

    fun setVoiceSensitivity(sensitivity: Int) {
        voiceSensitivity = sensitivity.coerceIn(0, 100)
        updateMicrophoneGate()
    }

    fun setMicGainDb(gainDb: Int) {
        microphoneGainDb = gainDb.coerceIn(0, 30)
        updateMicrophoneGate()
    }

    fun setAudioProcessing(noiseSuppression: Boolean, echoCancellation: Boolean) {
        noiseSuppressionEnabled = noiseSuppression
        echoCancellationEnabled = echoCancellation
        microphone?.setNoiseSuppressionEnabled(noiseSuppression)
        microphone?.setEchoCancellationEnabled(echoCancellation)
    }

    fun disconnect() {
        val current = session
        val mic = microphone
        val player = audioPlayer
        session = null
        microphone = null
        audioPlayer = null
        connected = false
        thread(name = "ts3-disconnect") {
            try { current?.disconnect("Disconnected by user") } catch (_: Throwable) { }
            try { current?.close() } catch (_: Throwable) { }
            try { mic?.close() } catch (_: Throwable) { }
            try { player?.close() } catch (_: Throwable) { }
        }
    }
}
