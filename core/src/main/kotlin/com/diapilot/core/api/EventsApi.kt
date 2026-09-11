package com.diapilot.core.api

/** What the HTTP layer should send back. [body] is always JSON. */
data class ApiResponse(val status: Int, val reason: String, val body: String) {
    companion object {
        fun ok(body: String) = ApiResponse(200, "OK", body)

        fun badRequest(message: String) =
            ApiResponse(400, "Bad Request", errorBody("bad_request", message))

        fun gone(message: String) =
            ApiResponse(410, "Gone", errorBody("gone", message))

        fun serverError(message: String) =
            ApiResponse(500, "Internal Server Error", errorBody("internal_error", message))

        fun unavailable(message: String) =
            ApiResponse(503, "Service Unavailable", errorBody("unavailable", message))

        /**
         * Error bodies carry a machine code and a message about the REQUEST —
         * never a medical value, and never `glucose: 0`, which a consumer would
         * read as a measured hypo.
         */
        private fun errorBody(code: String, message: String): String =
            "{\"error\":" + jsonString(code) + ",\"message\":" + jsonString(message) + "}"
    }
}

/**
 * `GET /api/v1/events?after=<seq>&limit=<n>` — the read side of the journal.
 *
 * Kept free of any HTTP plumbing so the whole contract can be exercised in
 * `:core:test`; [com.example.diapilot.collect.WatchServer] only supplies the
 * parsed query and writes the status line.
 */
object EventsApi {

    const val PATH = "/api/v1/events"
    const val VERSION = 1
    const val DEFAULT_LIMIT = 500
    const val MAX_LIMIT = 1000

    fun handle(params: Map<String, String>, journal: EventJournal): ApiResponse {
        val afterRaw = params["after"]
        val after = if (afterRaw == null) 0L else afterRaw.toLongOrNull()
            ?: return ApiResponse.badRequest("after must be an integer")
        if (after < 0) return ApiResponse.badRequest("after must be >= 0")

        val limitRaw = params["limit"]
        val limit = if (limitRaw == null) DEFAULT_LIMIT else limitRaw.toIntOrNull()
            ?: return ApiResponse.badRequest("limit must be an integer")
        if (limit < 1 || limit > MAX_LIMIT) {
            return ApiResponse.badRequest("limit must be between 1 and $MAX_LIMIT")
        }

        // Retention dropped the stretch this consumer would resume from: say so
        // loudly instead of returning a page with a hole in it.
        if (journal.isPruned(after)) {
            return ApiResponse.gone("journal entries at or after seq $after are no longer retained")
        }

        val page = journal.page(after, limit)
        return ApiResponse.ok(render(journal.sourceId(), page))
    }

    fun render(sourceId: String, page: Page): String = buildString {
        append("{\"version\":").append(VERSION)
        append(",\"source_id\":").append(jsonString(sourceId))
        append(",\"next_after\":").append(page.nextAfter)
        append(",\"has_more\":").append(page.hasMore)
        append(",\"events\":[")
        page.entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            appendEvent(e)
        }
        append("]}")
    }

    private fun StringBuilder.appendEvent(e: JournalEntry) {
        append("{\"seq\":").append(e.seq)
        append(",\"id\":").append(jsonString(e.id))
        append(",\"revision\":").append(e.revision)
        append(",\"type\":").append(jsonString(e.type))
        // occurred_at is when it happened to the body; received_at is when this
        // app learned of it. They are stored separately and never substituted
        // for one another — a back-filled reading has them minutes apart, and
        // that gap is the only way a consumer can tell we were catching up.
        append(",\"occurred_at\":").append(jsonString(rfc3339Utc(e.occurredAtMs)))
        append(",\"received_at\":").append(jsonString(rfc3339Utc(e.receivedAtMs)))
        append(",\"deleted\":").append(e.deleted)
        if (!e.deleted && e.payload.isNotEmpty()) append(',').append(e.payload)
        append('}')
    }
}
