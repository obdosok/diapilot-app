package io.github.obdosok.diapilot.collect

/**
 * Live diagnostics shared with the Settings card — plain volatile fields,
 * written by the collector components, read by the UI.
 */
object DiagState {
    @Volatile var bleStatus: String = ""
    @Volatile var bleLastPacketMs: Long = 0
    @Volatile var serviceStartedMs: Long = 0
    /** True only while WatchServer holds a bound socket (a busy port reads as off). */
    @Volatile var watchServerUp: Boolean = false
    @Volatile var lastXdripBroadcastMs: Long = 0
}
