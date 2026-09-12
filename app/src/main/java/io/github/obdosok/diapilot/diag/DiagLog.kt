package io.github.obdosok.diapilot.diag

/**
 * THE APP'S OWN LOG LINES, IN MEMORY — because `adb logcat` is not available
 * to a tester on the other side of a border.
 *
 * The collection chain already narrates itself (link up, packet arrived,
 * broadcast refused, poll failed) and until now that narration existed only
 * in logcat, where reading it means a cable, a laptop and a bug report that
 * carries hours of everything else on the phone as well. This ring keeps the
 * same lines for the last [WINDOW_MS] so the diagnostics export can carry
 * them alone.
 *
 * WHAT GOES THROUGH IT: the collection chain and the alerts — the components
 * whose lines answer "why did no reading arrive". The model build ledger and
 * the UI timing lines stay on `android.util.Log`; they are the maintainer's
 * own bench instrument and would flood a 24 h window with numbers that say
 * nothing about collection.
 *
 * ALL FOUR LEVELS ARE KEPT, and what bounds the ring is [MAX_LINES] plus the
 * dropped count the export prints. The per-packet lines — a BLE chunk every
 * few seconds, one per decoded minute in own-BLE mode — deliberately stay on
 * `android.util.Log` AT THE CALL SITE instead: a single one of them is more
 * than a day's worth of the cap, so routing it here would empty the ring of
 * everything a collection problem is read with.
 *
 * The ring holds what the call site wrote. It is NOT the redaction boundary —
 * [DiagnosticsBundle] scrubs every line on the way into the file, and the
 * call sites that used to log a glucose value or a dose were changed to stop
 * doing so at the source (`docs/audit.md`, S10).
 */
object DiagLog {

    /** The export's window: one day of a tester's phone. */
    const val WINDOW_MS: Long = 24L * 3_600_000

    /**
     * A cap under the window, so a reconnect storm cannot turn the ring into
     * a megabyte of the same line. When it bites, the export says how many
     * lines it lost rather than pretending the day was quiet.
     */
    const val MAX_LINES: Int = 800

    data class Line(val atMs: Long, val level: Char, val tag: String, val message: String)

    private val ring = java.util.ArrayDeque<Line>()

    @Volatile
    private var droppedToCap: Int = 0

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        record('I', tag, message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        record('W', tag, message)
    }

    fun w(tag: String, message: String, t: Throwable) {
        android.util.Log.w(tag, message, t)
        record('W', tag, "$message (${t.javaClass.simpleName})")
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        record('E', tag, message)
    }

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        record('D', tag, message)
    }

    /**
     * The ring's only writer, and the part that does not touch Android — the
     * tests drive it directly, with their own clock.
     */
    @Synchronized
    fun record(level: Char, tag: String, message: String, atMs: Long = System.currentTimeMillis()) {
        ring.addLast(Line(atMs, level, tag, message))
        while (ring.size > MAX_LINES) {
            ring.pollFirst()
            droppedToCap++
        }
    }

    /** Oldest first, everything inside [WINDOW_MS] of [nowMs]. */
    @Synchronized
    fun lines(nowMs: Long): List<Line> =
        ring.filter { nowMs - it.atMs <= WINDOW_MS }

    /** How many lines the cap threw away since the process started. */
    fun dropped(): Int = droppedToCap

    @Synchronized
    fun clear() {
        ring.clear()
        droppedToCap = 0
    }
}
