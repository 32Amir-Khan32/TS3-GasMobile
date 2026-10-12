package io.github.ts3mobile.protocol

interface Ts3SessionListener {
    fun onStatusChanged(status: ConnectionStatus)
    fun onSnapshotChanged(snapshot: SessionSnapshot)
    fun onVoiceFrame(frame: VoiceFrame)
    fun onTextMessage(fromUserId: Int, fromName: String, targetMode: String, targetClientId: Int, message: String) {}
}

interface Ts3SessionClient : AutoCloseable {
    fun connect(config: ServerConfig, identityMaterial: String, listener: Ts3SessionListener)
    fun setVoiceSource(source: EncodedVoiceSource?)
    fun joinChannel(channelId: Int, password: String = "")
    fun sendChannelMessage(channelId: Int, message: String)
    fun sendServerMessage(message: String)
    fun sendPrivateMessage(clientId: Int, message: String)
    fun disconnect(reason: String = "Client disconnected")
    override fun close()
}
