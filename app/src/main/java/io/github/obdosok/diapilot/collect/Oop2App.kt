package io.github.obdosok.diapilot.collect

import android.content.Context

/**
 * Is OOPAlgorithm2 on this phone at all?
 *
 * Three receivers listen for its replies — the per-minute decoded stream in
 * [CollectorService], and the FRAM-decode and BLE-enable round trips in
 * `nfc/LibreOop2Bridge` — and all three are exported, because the sender is
 * another app. With OOP2 absent, every intent arriving on those actions comes
 * from something else; the minute stream in particular anchors the forecast
 * whenever it is fresher than the main reading (`forecastAnchor`), so it is
 * the one to refuse first. Same rule as [XdripApp], same cache.
 */
object Oop2App {

    const val PACKAGE = "com.hg4.oopalgorithm.oopalgorithm2"

    private val presence = PackagePresence(PACKAGE)

    fun installed(context: Context): Boolean = presence.installed(context)

    /** Tests only: forget what was learned about the package. */
    internal fun reset() = presence.reset()
}
