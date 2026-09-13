package io.github.obdosok.diapilot.collect

import android.content.Context

/**
 * Is xDrip+ on this phone at all?
 *
 * The glucose broadcast it sends carries no permission, and the web service
 * it answers on 127.0.0.1:17580 is a port any app holding `INTERNET` can bind
 * once xDrip is stopped — so "the app that legitimately sends this is
 * installed" is the weakest precondition worth having, and the one that is
 * cheap and certain. It does not prove a given intent or answer came FROM
 * xDrip; it removes the case where nothing on the phone could have sent it
 * honestly. The cache and the manifest `<queries>` rule are in
 * [PackagePresence].
 */
object XdripApp {

    const val PACKAGE = "com.eveningoutpost.dexdrip"

    private val presence = PackagePresence(PACKAGE)

    fun installed(context: Context): Boolean = presence.installed(context)

    /** Tests only: forget what was learned about the package. */
    internal fun reset() = presence.reset()
}
