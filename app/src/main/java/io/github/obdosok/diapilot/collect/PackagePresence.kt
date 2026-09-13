package io.github.obdosok.diapilot.collect

import android.content.Context
import android.content.pm.PackageManager

/**
 * "Is the app that legitimately talks to us on this phone at all?" — the one
 * question an exported receiver or a loopback poll can answer about its peer.
 *
 * Neither a broadcast action nor a loopback port carries a permission, so this
 * is the weakest precondition worth having, and the one that is cheap and
 * certain. It does not prove a given intent or answer came FROM that app; it
 * removes the case where nothing on the phone could have sent it honestly.
 *
 * The manifest must declare `<queries><package>` for each id checked this way,
 * which is what makes the lookup answer truthfully from API 30 on.
 *
 * Cached per process and per answer: an install or an uninstall restarts
 * nothing here, so a negative answer is re-checked on a cadence rather than
 * remembered forever, and a binder call per glucose reading is avoided.
 * One instance per package — see [XdripApp] and [Oop2App].
 */
class PackagePresence(val packageName: String) {

    /** How long a negative answer is reused before asking again. Short: the
     *  user may install the peer while the collector is running. */
    private val missTtlMs = 60_000L

    @Volatile private var known = false

    @Volatile private var checkedAtMs = 0L

    fun installed(context: Context): Boolean {
        if (known) return true
        val now = System.currentTimeMillis()
        if (now - checkedAtMs < missTtlMs) return false
        checkedAtMs = now
        known = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return known
    }

    /** Tests only: forget what was learned about the package. */
    internal fun reset() {
        known = false
        checkedAtMs = 0L
    }
}
