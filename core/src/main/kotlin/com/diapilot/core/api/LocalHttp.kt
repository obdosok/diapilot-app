package com.diapilot.core.api

import java.net.URLDecoder

/** A parsed request target: `/info.json?graph=1` → path + params. */
data class RequestTarget(val path: String, val params: Map<String, String>)

/** The endpoints the loopback server answers. */
enum class LocalRoute {
    /** WatchDrip-compatible watch-face feed. Frozen — a Zepp OS face reads it. */
    INFO_JSON,

    /** Entry FROM the watch (insulin / carbs). */
    ADD_TREATMENTS,

    /** The change journal for a second app on this phone. */
    EVENTS,

    NOT_FOUND,
}

/**
 * Routing and query parsing for the loopback server, kept out of the socket
 * code so both can be tested without a device.
 *
 * Adding a route must not move an existing one: the watch face polls
 * `/info.json` and is not something we can redeploy, so [routeFor] matches
 * exact paths and the new API lives under a versioned prefix of its own.
 */
fun routeFor(path: String): LocalRoute = when (path) {
    "/info.json" -> LocalRoute.INFO_JSON
    "/add_treatments" -> LocalRoute.ADD_TREATMENTS
    EventsApi.PATH -> LocalRoute.EVENTS
    else -> LocalRoute.NOT_FOUND
}

fun parseRequestTarget(target: String): RequestTarget {
    val path = target.substringBefore('?')
    val query = target.substringAfter('?', "")
    if (query.isEmpty()) return RequestTarget(path, emptyMap())
    val params = query.split('&').mapNotNull { p ->
        val kv = p.split('=', limit = 2)
        if (kv.size != 2) null
        else runCatching { kv[0] to URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
    }.toMap()
    return RequestTarget(path, params)
}
