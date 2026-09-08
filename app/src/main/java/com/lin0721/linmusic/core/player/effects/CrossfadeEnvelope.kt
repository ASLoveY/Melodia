package com.lin0721.linmusic.core.player.effects

import kotlin.math.cos
import kotlin.math.PI

object CrossfadeEnvelope {
    fun duration(requestedMs: Long, outgoingMs: Long, incomingMs: Long): Long =
        if (outgoingMs <= 0 || incomingMs <= 0) 0 else minOf(requestedMs, outgoingMs / 2, incomingMs / 2)

    fun incoming(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 1f
        val fraction = (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return ((1.0 - cos(PI * fraction)) / 2.0).toFloat()
    }
}
