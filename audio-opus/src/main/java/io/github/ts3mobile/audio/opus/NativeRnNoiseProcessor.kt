package io.github.ts3mobile.audio.opus

/**
 * Compact GasMobile build: use Android's built-in voice-processing effects
 * rather than bundling the upstream RNNoise model. Opus remains native.
 */
internal class NativeRnNoiseProcessor : AutoCloseable {
    fun processInPlace(pcm: ShortArray): Float {
        require(pcm.isNotEmpty() && pcm.size % 480 == 0) {
            "PCM must contain complete 480-sample frames"
        }
        return 0.0f
    }
    override fun close() = Unit
}
