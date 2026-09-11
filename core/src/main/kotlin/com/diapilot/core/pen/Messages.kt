/**
 * Message-level parsing and response building — the manager side of the
 * 11073 conversation with the pen.
 *
 * Part of the NovoPen protocol port from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0 — see Bytes.kt.
 */
package com.diapilot.core.pen

import java.nio.ByteBuffer

class PenContext(val nowMs: Long) {
    var specification: Specification? = null
    var model: IdModel? = null
    var eventReport: EventReport? = null
    var trigSegmDataXfer: TrigSegmDataXfer? = null
    var segmentInfoList: SegmentInfoList? = null
    var aRequest: ARequest? = null
    var apdu: Apdu? = null
    var invokeId = -1

    private var cachedConfiguration: Configuration? = null

    val configuration: Configuration?
        get() {
            cachedConfiguration?.let { return it }
            val fromReport = eventReport?.configuration
            if (fromReport != null) cachedConfiguration = fromReport
            return cachedConfiguration
        }

    val isError: Boolean get() = apdu?.isError ?: true
    val wantsRelease: Boolean get() = apdu?.wantsRelease ?: false
}

class PenMessage private constructor(val context: PenContext) {
    var invokeId = -1
        private set
    var closed = false
        private set
    var length = -1
        private set

    companion object {
        private const val CONFIRMED_EVENT_REPORT_CHOSEN = 0x0101
        private const val SCONFIRMED_EVENT_REPORT_CHOSEN = 0x0201
        private const val GET_CHOSEN = 0x0203
        private const val SGET_CHOSEN = 0x0103
        private const val CONFIRMED_ACTION = 0x0107
        private const val CONFIRMED_ACTION_CHOSEN = 0x0207
        private const val MDC_ACT_SEG_GET_INFO = 0x0C0D
        private const val MDC_ACT_SEG_TRIG_XFER = 0x0C1C
        private const val STORE_HANDLE = 0x100

        fun parse(context: PenContext, payload: ByteArray): PenMessage? {
            val m = PenMessage(context)
            m.length = payload.size
            if (m.length < 4) return m

            val buffer = ByteBuffer.wrap(payload)
            val apdu = Apdu.parse(buffer) ?: return null
            context.apdu = apdu

            when (apdu.apduType) {
                ApduType.AarqApdu -> context.aRequest = ARequest.parse(buffer)
                ApduType.AareApdu -> { /* we never receive these as manager */ }
                ApduType.RlrqApdu -> { /* release requested; state machine reacts */ }
                ApduType.RlreApdu -> m.closed = true
                ApduType.AbrtApdu -> { /* error flagged via context.isError */ }
                ApduType.PrstApdu -> {
                    val dpdu = DataApdu.parse(buffer)
                    m.invokeId = dpdu.invokeId
                    context.invokeId = m.invokeId
                    when (dpdu.dchoice) {
                        CONFIRMED_ACTION_CHOSEN -> {
                            /* handle */ buffer.u16()
                            val actionType = buffer.u16()
                            /* actionLen */ buffer.u16()
                            when (actionType) {
                                MDC_ACT_SEG_GET_INFO ->
                                    context.segmentInfoList = SegmentInfoList.parse(buffer)
                                MDC_ACT_SEG_TRIG_XFER ->
                                    context.trigSegmDataXfer = TrigSegmDataXfer.parse(buffer)
                            }
                        }
                        CONFIRMED_EVENT_REPORT_CHOSEN -> {
                            EventReport.parse(buffer, context.nowMs)?.let { context.eventReport = it }
                        }
                        SCONFIRMED_EVENT_REPORT_CHOSEN -> {
                            EventRequest.parse(buffer)
                        }
                        GET_CHOSEN, SGET_CHOSEN -> {
                            /* handle */ buffer.u16()
                            val count = buffer.u16()
                            /* len */ buffer.u16()
                            repeat(count) {
                                val a = Attribute.parse(buffer)
                                when (a.atype) {
                                    Atype.MDC_ATTR_ID_PROD_SPECN ->
                                        context.specification = Specification.parse(a.bytes)
                                    Atype.MDC_ATTR_ID_MODEL ->
                                        context.model = IdModel.parse(a.bytes)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
            return m
        }
    }

    private fun prst(dchoice: Int, payload: ByteArray): ByteArray =
        Apdu.encode(ApduType.PrstApdu, DataApdu.encode(context.invokeId, dchoice, payload))

    fun aResponse(): ByteArray =
        Apdu.encode(ApduType.AareApdu, AResponse.encode(requireNotNull(context.aRequest)))

    fun acceptConfig(): ByteArray? {
        val config = context.configuration ?: return null
        return prst(
            SCONFIRMED_EVENT_REPORT_CHOSEN,
            EventRequest.encodeConfigReply(config.id),
        )
    }

    fun askInformation(): ByteArray? {
        val report = context.eventReport ?: return null
        return prst(SGET_CHOSEN, ArgumentsSimple.encode(report.handle))
    }

    fun confirmedAction(): ByteArray? {
        if (context.configuration == null) return null
        return prst(
            CONFIRMED_ACTION,
            ConfirmedActionCodec.encodeAllSegments(STORE_HANDLE, MDC_ACT_SEG_GET_INFO),
        )
    }

    fun xferAction(segment: Int): ByteArray? {
        if (context.configuration == null) return null
        return prst(
            CONFIRMED_ACTION,
            ConfirmedActionCodec.encodeSegment(STORE_HANDLE, MDC_ACT_SEG_TRIG_XFER, segment),
        )
    }

    fun confirmedXfer(instance: Int, index: Int, count: Int): ByteArray? {
        if (context.configuration == null) return null
        return prst(
            SCONFIRMED_EVENT_REPORT_CHOSEN,
            EventRequest.encodeSegmentConfirm(STORE_HANDLE, instance, index, count),
        )
    }

    fun closeDown(): ByteArray =
        Apdu.encode(ApduType.RlrqApdu, ByteArray(2))
}
