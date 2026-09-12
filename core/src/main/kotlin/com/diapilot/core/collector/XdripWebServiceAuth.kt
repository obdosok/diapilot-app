/**
 * The optional credential for xDrip's local Nightscout-compatible web service.
 *
 * Format read off the xDrip+ sources (GPL-3.0, `NightscoutFoundation/xDrip`,
 * `webservices/XdripWebService.java`) rather than guessed:
 *
 *  - the header is `api-secret` (matched case-insensitively there);
 *  - its value is the **SHA-1 of the secret, hex**, which is what the server
 *    compares its own `Hashing.sha1().hashBytes(secret)` against — the
 *    Nightscout convention, not the plaintext secret;
 *  - the comparison is case-insensitive, so lower-case hex is fine.
 *
 * Worth knowing about the other side, and the reason this is optional: xDrip
 * computes `authNeeded = hashedSecret != null && !socket.getInetAddress()
 * .isLoopbackAddress()`, so it does NOT demand the header on a loopback
 * connection — which is the only kind DiaPilot makes. Sending it is therefore
 * for the user who has configured a secret and expects it to be used, and it
 * cannot by itself tell xDrip apart from something else holding port 17580.
 */
package com.diapilot.core.collector

/**
 * The `api-secret` header value for [secret], or null when no secret is
 * configured (blank counts as none, exactly as xDrip's own empty-check does).
 */
fun xdripApiSecretHeader(secret: String?): String? {
    val trimmed = secret?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return java.security.MessageDigest.getInstance("SHA-1")
        .digest(trimmed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** The header name xDrip reads the value above from. */
const val XDRIP_API_SECRET_HEADER = "api-secret"
