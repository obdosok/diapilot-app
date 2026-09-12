package io.github.obdosok.diapilot.collect

import android.content.Context
import android.content.pm.PackageManager

/**
 * Is xDrip+ on this phone at all?
 *
 * The glucose broadcast it sends carries no permission, so "the app that
 * legitimately sends this intent is installed" is the weakest precondition
 * worth having — and the one that is cheap and certain. It does not prove a
 * given intent came FROM xDrip; it removes the case where nothing on the phone
 * could have sent it honestly.
 *
 * The manifest already declares `<queries><package>` for this id, which is what
 * makes the lookup answer truthfully from API 30 on.
 *
 * Cached per process and per answer: an install or an uninstall restarts
 * nothing here, so a negative answer is re-checked on a cadence rather than
 * remembered forever, and a binder call per glucose reading is avoided.
 */
object XdripApp {

    const val PACKAGE = "com.eveningoutpost.dexdrip"

    /** How long a negative answer is reused before asking again. Short: the
     *  user may install xDrip while the collector is running. */
    private const val MISS_TTL_MS = 60_000L

    @Volatile private var known = false

    @Volatile private var checkedAtMs = 0L

    fun installed(context: Context): Boolean {
        if (known) return true
        val now = System.currentTimeMillis()
        if (now - checkedAtMs < MISS_TTL_MS) return false
        checkedAtMs = now
        known = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE, 0) != null
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
