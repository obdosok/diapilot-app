/**
 * The manager-side state machine driving the pen exchange: accept the
 * association, accept the configuration, ask for identity, enumerate the
 * dose-log segments and pull them all, then release. Pure logic — payloads
 * in, actions out; the app layer moves bytes over NFC.
 *
 * Part of the NovoPen protocol port from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0 — see Bytes.kt.
 */
package com.diapilot.core.pen

class Fsa(val action: Action, val payload: ByteArray?) {
    enum class Action { WRITE_READ, READ, DONE }

    val doRead: Boolean get() = action == Action.READ || action == Action.WRITE_READ

    companion object {
        fun done() = Fsa(Action.DONE, null)
        fun read() = Fsa(Action.READ, null)
        fun writeRead(payload: ByteArray?) = Fsa(Action.WRITE_READ, payload)
        fun writeNull() = writeRead(ByteArray(0))
    }
}

class PenMachine(
    private val context: PenContext,
    private val onDoses: (List<InsulinDose>, PenContext) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private enum class State {
        AWAIT_ASSOCIATION_REQ,
        AWAIT_CONFIGURATION,
        ASK_INFORMATION,
        AWAIT_INFORMATION,
        AWAIT_STORAGE_INFO,
        AWAIT_XFER_CONFIRM,
        AWAIT_LOG_DATA,
        AWAIT_CLOSE_DOWN;

        fun next(): State = entries[(ordinal + 1) % entries.size]
    }

    private var state = State.AWAIT_ASSOCIATION_REQ
    private var requestCounter = 0
    private var currentSegmentCount = -1L

    fun processPayload(payload: ByteArray?): Fsa {
        if (payload == null) return Fsa.done()
        val msg = PenMessage.parse(context, payload) ?: return Fsa.done()
        if (msg.context.isError) {
            log("error response while in $state")
            return Fsa.done()
        }
        return processState(msg)
    }

    private fun processState(msg: PenMessage): Fsa {
        log("state: $state len=${msg.length}")

        if (msg.context.wantsRelease) {
            return closeDown(msg)
        }

        when (state) {
            State.AWAIT_ASSOCIATION_REQ -> {
                if (context.aRequest?.valid() == true) {
                    state = state.next()
                    return Fsa.writeRead(msg.aResponse())
                }
            }

            State.AWAIT_CONFIGURATION -> {
                val config = context.configuration
                if (config != null && config.isAsExpected) {
                    state = state.next()
                    return Fsa.writeRead(msg.acceptConfig())
                }
                log("configuration missing or unexpected")
            }

            State.ASK_INFORMATION -> {
                state = state.next()
                return Fsa.writeRead(msg.askInformation())
            }

            State.AWAIT_INFORMATION -> {
                if (context.specification == null) {
                    log("no specification yet — asking again")
                    return Fsa.writeRead(msg.askInformation())
                }
                state = state.next()
                return Fsa.writeRead(msg.confirmedAction())
            }

            State.AWAIT_STORAGE_INFO -> return handleNextSegment(msg)

            State.AWAIT_XFER_CONFIRM -> {
                if (msg.length != 0) {
                    val xfer = context.trigSegmDataXfer
                    if (xfer != null && xfer.isOkay) {
                        context.trigSegmDataXfer = null
                        state = state.next()
                    } else {
                        return handleNextSegment(msg)
                    }
                }
                return Fsa.writeNull()
            }

            State.AWAIT_LOG_DATA -> {
                if (msg.length == 0) return Fsa.writeNull()
                val er = context.eventReport ?: return Fsa.writeNull()
                if (er.doses.isNotEmpty()) {
                    onDoses(er.doses, context)
                }
                val sil = context.segmentInfoList ?: return Fsa.done()
                if (currentSegmentCount == er.index + er.count) {
                    log("segment ${er.instance} complete @ $currentSegmentCount")
                    sil.markProcessed(er.instance)
                    if (sil.hasUnprocessed()) {
                        return handleNextSegment(msg)
                    }
                }
                return Fsa.writeRead(
                    msg.confirmedXfer(er.instance, er.index.toInt(), er.count.toInt()),
                )
            }

            State.AWAIT_CLOSE_DOWN -> {
                log(if (msg.closed) "closed down" else "close-down not confirmed")
                return Fsa.done()
            }
        }
        return Fsa.done()
    }

    private fun closeDown(msg: PenMessage): Fsa {
        state = State.AWAIT_CLOSE_DOWN
        return Fsa.writeRead(msg.closeDown())
    }

    private fun handleNextSegment(msg: PenMessage): Fsa {
        requestCounter++
        if (requestCounter > 100) {
            log("exceeded max segment requests")
            return Fsa.done()
        }
        val sil = context.segmentInfoList
        if (sil == null || !sil.isTypical) {
            log("segment info missing or non-typical")
            return Fsa.done()
        }
        val segment = sil.nextUnprocessedId()
        state = State.AWAIT_XFER_CONFIRM
        return if (segment >= 0) {
            currentSegmentCount = sil.nextUnprocessedCount()
            log("requesting segment $segment ($currentSegmentCount entries)")
            Fsa.writeRead(msg.xferAction(segment))
        } else {
            closeDown(msg)
        }
    }
}
