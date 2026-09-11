package com.example.diapilot.collect

/**
 * Live diagnostics shared with the Settings card — plain volatile fields,
 * written by the collector components, read by the UI.
 */
object DiagState {
    @Volatile var bleStatus: String = "выключен"
    @Volatile var bleLastPacketMs: Long = 0
    @Volatile var serviceStartedMs: Long = 0
    @Volatile var watchServerUp: Boolean = false
    @Volatile var lastXdripBroadcastMs: Long = 0
}
