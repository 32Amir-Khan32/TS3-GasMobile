package com.halla.mobile

import android.content.Context
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
    fun isConnected(): Boolean = connected

    fun connect(
        context: Context,
        host: String,
        port: Int,
        nickname: String,
        password: String,
        onStatus: (ConnectionStatus) -> Unit,
        onSnapshot: (SessionSnapshot) -> Unit
    ) {
        val appContext = context.applicationContext
        val client = Ts3jSessionClient()
        synchronized(this) {
            session?.close()
            session = client
            connected = false
        }
        thread(name = "ts3-connect") {
            try {
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
                        override fun onSnapshotChanged(snapshot: SessionSnapshot) = onSnapshot(snapshot)
                        override fun onVoiceFrame(frame: VoiceFrame) {
                            // The Opus capture/playback pipeline still needs wiring.
                        }
                    }
                )
            } catch (error: Throwable) {
                connected = false
                onStatus(ConnectionStatus(ConnectionPhase.ERROR, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun joinChannel(channelId: Int, password: String = "") { session?.joinChannel(channelId, password) }

    fun disconnect() {
        val current = session
        session = null
        connected = false
        thread(name = "ts3-disconnect") {
            try { current?.disconnect("Disconnected by user") } catch (_: Throwable) { }
            try { current?.close() } catch (_: Throwable) { }
        }
    }
}
