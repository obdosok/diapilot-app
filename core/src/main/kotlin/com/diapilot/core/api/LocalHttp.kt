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
    return RequestTarget(path, parseFormEncoded(query))
}

/** `a=1&b=two%20words` → a map. Pairs without a `=` are dropped. */
fun parseFormEncoded(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { p ->
        val kv = p.split('=', limit = 2)
        if (kv.size != 2) null
        else runCatching { kv[0] to URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
    }.toMap()
}

/** Whether a request may proceed, and why not when it may not. */
enum class LocalAccess { OK, METHOD_NOT_ALLOWED, UNAUTHORIZED }

/** The header a client may present the per-installation token in. */
const val LOCAL_TOKEN_HEADER = "x-diapilot-token"

/** The query parameter carrying the same token, for a client that cannot set
 *  headers. */
const val LOCAL_TOKEN_PARAM = "token"

/**
 * The gate in front of the loopback socket.
 *
 * Loopback is not a permission: every app on the phone holding `INTERNET` can
 * reach 127.0.0.1, and Android tells the listener nothing about who connected.
 * So the two endpoints that WRITE insulin or PUBLISH history need a shared
 * secret of their own, and the one endpoint that cannot be changed does not
 * get one:
 *
 *  - `/info.json` — untouched, no method check, no token. A Zepp OS watch face
 *    reads it and cannot be redeployed; it publishes the same live value the
 *    watch already displays, and gating it would silently blank a wrist.
 *  - `/add_treatments` — **POST only**. It writes a dose, and a GET with a side
 *    effect is fetched by anything that merely follows a link. Then the token.
 *  - `/api/v1/events` — the token. It publishes the whole glucose, insulin and
 *    meal history.
 *
 * With no token stored yet (generation failed, or the keystore is unavailable)
 * [expected] is null and both guarded routes answer UNAUTHORIZED: closed, never
 * open by default.
 */
fun localAccess(
    route: LocalRoute,
    method: String,
    presented: String?,
    expected: String?,
): LocalAccess = when (route) {
    LocalRoute.INFO_JSON, LocalRoute.NOT_FOUND -> LocalAccess.OK
    LocalRoute.ADD_TREATMENTS ->
        if (!method.equals("POST", ignoreCase = true)) LocalAccess.METHOD_NOT_ALLOWED
        else if (tokenMatches(presented, expected)) LocalAccess.OK
        else LocalAccess.UNAUTHORIZED
    LocalRoute.EVENTS ->
        if (tokenMatches(presented, expected)) LocalAccess.OK else LocalAccess.UNAUTHORIZED
}

/** The token a request presents: the dedicated header, an `Authorization:
 *  Bearer` and finally the query parameter. Null when it presents none. */
fun presentedToken(headers: Map<String, String>, params: Map<String, String>): String? {
    headers[LOCAL_TOKEN_HEADER]?.trim()?.ifEmpty { null }?.let { return it }
    headers["authorization"]?.trim()?.let { auth ->
        if (auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            auth.substring(7).trim().ifEmpty { null }?.let { return it }
        }
    }
    return params[LOCAL_TOKEN_PARAM]?.trim()?.ifEmpty { null }
}

/**
 * Null-safe, length-safe comparison. `MessageDigest.isEqual` is the JDK's
 * constant-time byte compare — an ordinary `==` on strings returns after the
 * first differing character and hands a caller that can retry a few thousand
 * times a way to read the token out one character at a time.
 */
fun tokenMatches(presented: String?, expected: String?): Boolean {
    if (expected.isNullOrEmpty() || presented.isNullOrEmpty()) return false
    return java.security.MessageDigest.isEqual(
        presented.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
}
