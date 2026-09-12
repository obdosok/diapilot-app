package io.github.obdosok.diapilot.collect

/**
 * Live diagnostics shared with the Settings card and the Data sources screen —
 * plain volatile fields, written by the collector components, read by the UI.
 *
 * Everything here is an OBSERVATION of collection, never an input to it: a
 * field may be added for a screen, but no collector branch may read one back.
 */
object DiagState {
    @Volatile var bleStatus: String = ""
    @Volatile var bleLastPacketMs: Long = 0
    @Volatile var serviceStartedMs: Long = 0
    /**
     * When the collector service last tore its receivers down. The service
     * lives in the UI's own process, so a value here outliving
     * [serviceStartedMs] is the only way the screen can tell "the service was
     * stopped" from "the service is running": a killed PROCESS resets this
     * whole object, a killed SERVICE does not.
     */
    @Volatile var serviceStoppedMs: Long = 0
    /** True only while WatchServer holds a bound socket (a busy port reads as off). */
    @Volatile var watchServerUp: Boolean = false
    @Volatile var lastXdripBroadcastMs: Long = 0
    /**
     * The xDrip local web service on 127.0.0.1:17580, as the periodic poll
     * last found it: [lastXdripWebProbeMs] is the last request sent,
     * [lastXdripWebOkMs] the last one answered with 200.
     *
     * Two stamps rather than a boolean, because "not reachable" and "not asked
     * yet" break for different reasons and the screen must not confuse them —
     * on a phone where the poll has never run, a single flag would accuse
     * xDrip of being down.
     */
    @Volatile var lastXdripWebProbeMs: Long = 0
    @Volatile var lastXdripWebOkMs: Long = 0
}
