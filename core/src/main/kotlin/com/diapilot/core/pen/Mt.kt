/**
 * IEEE 11073-20601 message types used by the NovoPen exchange: association
 * request/response, data APDUs, attributes, the segment store metadata and
 * the dose log entries themselves.
 *
 * Part of the NovoPen protocol port from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0 — see Bytes.kt.
 */
package com.diapilot.core.pen

import java.nio.ByteBuffer

// ---------------------------------------------------------------- APDU ----

enum class ApduType(val value: Int) {
    AarqApdu(0xE200),
    AareApdu(0xE300),
    RlrqApdu(0xE400),
    RlreApdu(0xE500),
    AbrtApdu(0xE600),
    PrstApdu(0xE700);

    companion object {
        fun findByValue(v: Int): ApduType? = entries.firstOrNull { it.value == v }
    }
}

class Apdu private constructor(val apduType: ApduType, val choiceLength: Int) {
    val isError get() = apduType == ApduType.AbrtApdu
    val wantsRelease get() = apduType == ApduType.RlrqApdu

    companion object {
        fun parse(buffer: ByteBuffer): Apdu? {
            val at = buffer.u16()
            val type = ApduType.findByValue(at) ?: return null
            return Apdu(type, buffer.u16())
        }

        fun encode(type: ApduType, choicePayload: ByteArray): ByteArray {
            val b = ByteBuffer.allocate(choicePayload.size + 4)
            b.putU16(type.value)
            b.putIndexedBytes(choicePayload)
            return b.array()
        }
    }
}

class DataApdu private constructor(val invokeId: Int, val dchoice: Int) {
    companion object {
        fun parse(buffer: ByteBuffer): DataApdu {
            /* olen */ buffer.u16()
            val invokeId = buffer.u16()
            val dchoice = buffer.u16()
            /* dlen */ buffer.u16()
            return DataApdu(invokeId, dchoice)
        }

        fun encode(invokeId: Int, dchoice: Int, dataPayload: ByteArray): ByteArray {
            val b = ByteBuffer.allocate(dataPayload.size + 8)
            b.putU16(b.capacity() - 2)
            b.putU16(invokeId)
            b.putU16(dchoice)
            b.putIndexedBytes(dataPayload)
            return b.array()
        }
    }
}

// -------------------------------------------------------- association ----

class ApoepElement {
    companion object {
        const val APOEP = 20601
        const val SYS_TYPE_MANAGER = 0x80000000L

        fun parse(bytes: ByteArray): ApoepElement {
            val b = ByteBuffer.wrap(bytes)
            val r = ApoepElement()
            r.version = b.u32()
            r.encoding = b.u16()
            r.nomenclature = b.u32()
            r.functional = b.u32()
            r.systemType = b.u32()
            r.systemId = b.indexedBytes()
            r.configId = b.u16()
            r.recMode = b.u32()
            r.olistCount = b.u16()
            r.olistLen = b.u16()
            return r
        }
    }

    var version = -1L
    var encoding = -1
    var nomenclature = -1L
    var functional = -1L
    var systemType = -1L
    var systemId: ByteArray = ByteArray(0)
    var configId = -1
    var recMode = -1L
    var olistCount = -1
    var olistLen = -1

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(30 + systemId.size)
        b.putU32(version)
        b.putU16(encoding)
        b.putU32(nomenclature)
        b.putU32(functional)
        b.putU32(systemType)
        b.putIndexedBytes(systemId)
        b.putU16(configId)
        b.putU32(recMode)
        b.putU16(olistCount)
        b.putU16(olistLen)
        return b.array()
    }
}

class ARequest private constructor() {
    var protocol = -1
        private set
    var apoep: ApoepElement? = null
        private set

    fun valid(): Boolean =
        protocol == ApoepElement.APOEP && apoep?.systemId?.size == 8

    companion object {
        fun parse(buffer: ByteBuffer): ARequest {
            val ar = ARequest()
            /* version */ buffer.u32()
            val elements = buffer.u16()
            /* len */ buffer.u16()
            repeat(elements) {
                ar.protocol = buffer.u16()
                val bytes = buffer.indexedBytes()
                if (ar.protocol == ApoepElement.APOEP) {
                    ar.apoep = ApoepElement.parse(bytes)
                }
            }
            return ar
        }
    }
}

object AResponse {
    /** Manager's acceptance built from the agent's own APOEP element. */
    fun encode(request: ARequest): ByteArray {
        val src = requireNotNull(request.apoep) { "no apoep in request" }
        val a = ApoepElement()
        a.version = src.version
        a.encoding = src.encoding
        a.nomenclature = src.nomenclature
        a.functional = src.functional
        a.systemId = src.systemId
        a.recMode = 0
        a.configId = 0
        a.systemType = ApoepElement.SYS_TYPE_MANAGER
        a.olistCount = 0
        a.olistLen = 0
        val encoded = a.encode()
        val b = ByteBuffer.allocate(6 + encoded.size)
        b.putU16(3)                       // result
        b.putU16(ApoepElement.APOEP)
        b.putIndexedBytes(encoded)
        return b.array()
    }
}

// ---------------------------------------------------------- attributes ----

enum class Atype(val value: Int) {
    MDC_ATTR_ID_MODEL(2344),
    MDC_ATTR_ID_PROD_SPECN(2349),
    MDC_ATTR_METRIC_STORE_CAPAC_CNT(2369),
    MDC_ATTR_METRIC_STORE_USAGE_CNT(2372),
    MDC_ATTR_NUM_SEG(2385),
    MDC_ATTR_SEG_USAGE_CNT(2427),
    MDC_ATTR_TIME_REL(2447),
    MDC_ATTR_UNIT_CODE(2454),
    MDC_ATTR_PM_SEG_MAP(2638),
    MDC_ATTR_ATTRIBUTE_VAL_MAP(2645);

    companion object {
        fun findByValue(v: Int): Atype? = entries.firstOrNull { it.value == v }
    }
}

class Attribute private constructor(
    val atype: Atype?,
    val bytes: ByteArray,
    val ivalue: Long,
) {
    companion object {
        fun parse(buffer: ByteBuffer): Attribute {
            val type = buffer.u16()
            val len = buffer.u16()
            val bytes = ByteArray(len)
            buffer.get(bytes)
            return Attribute(Atype.findByValue(type), bytes, ivalue(bytes))
        }
    }
}

// ------------------------------------------------------- configuration ----

class Configuration private constructor() {
    var id = -1
        private set
    var handle = -1
        private set
    var numberOfSegments = -1L
        private set
    var totalStoredEntries = -1L
        private set
    var unitCode = -1L
        private set

    /** International Units — the only sane unit for an insulin pen. */
    val isAsExpected: Boolean
        get() = unitCode == 5472L && numberOfSegments >= 0 && totalStoredEntries >= 0

    companion object {
        fun parse(buffer: ByteBuffer): Configuration? {
            val id = buffer.u16()
            val count = buffer.u16()
            /* len */ buffer.u16()
            var configuration: Configuration? = null
            repeat(count) {
                /* cls */ buffer.u16()
                val handle = buffer.u16()
                val acount = buffer.u16()
                /* alen */ buffer.u16()
                repeat(acount) {
                    if (configuration == null) {
                        configuration = Configuration().also {
                            it.id = id
                            it.handle = handle
                        }
                    }
                    val a = Attribute.parse(buffer)
                    when (a.atype) {
                        Atype.MDC_ATTR_NUM_SEG -> configuration!!.numberOfSegments = a.ivalue
                        Atype.MDC_ATTR_METRIC_STORE_USAGE_CNT -> configuration!!.totalStoredEntries = a.ivalue
                        Atype.MDC_ATTR_UNIT_CODE -> configuration!!.unitCode = a.ivalue
                        else -> { /* capacity, value maps — not needed */ }
                    }
                }
            }
            return configuration
        }
    }
}

// ------------------------------------------------------------ id blobs ----

class Specification private constructor() {
    var serial: String? = null
        private set

    companion object {
        fun parse(bytes: ByteArray): Specification {
            val buffer = ByteBuffer.wrap(bytes)
            val r = Specification()
            val scount = buffer.u16()
            /* ssize */ buffer.u16()
            repeat(scount) {
                val specType = buffer.u16()
                /* componentId */ buffer.u16()
                val s = buffer.indexedString()
                if (specType == 1) r.serial = s
            }
            return r
        }
    }
}

class IdModel private constructor() {
    var model = ""
        private set

    companion object {
        fun parse(bytes: ByteArray): IdModel {
            val buffer = ByteBuffer.wrap(bytes)
            val rt = IdModel()
            val parts = mutableListOf<String>()
            while (buffer.hasRemaining()) parts.add(buffer.indexedString())
            rt.model = parts.joinToString(" ")
            return rt
        }
    }
}

// ------------------------------------------------------------ segments ----

class SegmentEntry private constructor() {
    var otype = 0
        private set
    var metricType = 0L
        private set
    var val1 = 0
        private set

    companion object {
        fun parse(buffer: ByteBuffer): SegmentEntry {
            val se = SegmentEntry()
            /* classId */ buffer.u16()
            se.metricType = buffer.u16().toLong()
            se.otype = buffer.u16()
            /* handle */ buffer.u16()
            /* amcount */ buffer.u16()
            val amlen = buffer.u16()
            if (amlen == 4) {
                se.val1 = buffer.u16()
                /* val2 */ buffer.u16()
            } else {
                buffer.get(ByteArray(amlen))
            }
            return se
        }
    }
}

class SegmentInfoMap private constructor() {
    private val items = mutableListOf<SegmentEntry>()

    /** The known Dose Log layout: value + two enum bit strings. */
    val isTypical: Boolean
        get() = items.size == 3 &&
            items[0].otype == 13313 && items[0].metricType == 130L && items[0].val1 == 2646 &&
            items[1].otype == 13314 && items[1].metricType == 130L && items[1].val1 == 2662 &&
            items[2].otype == 61440 && items[2].metricType == 130L && items[2].val1 == 2662

    companion object {
        fun parse(bytes: ByteArray): SegmentInfoMap {
            val buffer = ByteBuffer.wrap(bytes)
            val r = SegmentInfoMap()
            /* bits */ buffer.u16()
            val acount = buffer.u16()
            /* alength */ buffer.u16()
            repeat(acount) { r.items.add(SegmentEntry.parse(buffer)) }
            return r
        }
    }
}

class SegmentInfo private constructor() {
    var instnum = -1
        private set
    var usage = -1L
        private set
    var processed = false
    private var map: SegmentInfoMap? = null

    val isTypical: Boolean get() = usage >= 0 && map?.isTypical == true

    companion object {
        fun parse(buffer: ByteBuffer): SegmentInfo {
            val r = SegmentInfo()
            r.instnum = buffer.u16()
            val acount = buffer.u16()
            /* alength */ buffer.u16()
            repeat(acount) {
                val a = Attribute.parse(buffer)
                when (a.atype) {
                    Atype.MDC_ATTR_PM_SEG_MAP -> r.map = SegmentInfoMap.parse(a.bytes)
                    Atype.MDC_ATTR_SEG_USAGE_CNT -> r.usage = a.ivalue
                    else -> {}
                }
            }
            return r
        }
    }
}

class SegmentInfoList private constructor() {
    val items = mutableListOf<SegmentInfo>()

    val isTypical: Boolean
        get() = items.size == 1 && items.all { it.isTypical }

    fun nextUnprocessedId(): Int = items.firstOrNull { !it.processed }?.instnum ?: -1
    fun nextUnprocessedCount(): Long = items.firstOrNull { !it.processed }?.usage ?: -1
    fun hasUnprocessed(): Boolean = nextUnprocessedId() >= 0
    fun markProcessed(which: Int) {
        items.filter { it.instnum == which }.forEach { it.processed = true }
    }

    companion object {
        fun parse(buffer: ByteBuffer): SegmentInfoList {
            val r = SegmentInfoList()
            val scount = buffer.u16()
            /* slength */ buffer.u16()
            repeat(scount) { r.items.add(SegmentInfo.parse(buffer)) }
            return r
        }
    }
}

class TrigSegmDataXfer private constructor(val segmentId: Int, val responseCode: Int) {
    val isOkay: Boolean get() = segmentId != 0 && responseCode == 0

    companion object {
        fun parse(buffer: ByteBuffer): TrigSegmDataXfer =
            TrigSegmDataXfer(buffer.u16(), buffer.u16())
    }
}

// ----------------------------------------------------------- dose data ----

class InsulinDose private constructor(
    val relativeTime: Long,
    val absoluteTime: Long,
    val units: Double,
    private val flags: Long,
    private val nowMs: Long,
) {
    /** Sanity gate mirroring the reference implementation. */
    val isValid: Boolean
        get() = units > 0 && units < 100 &&
            flags == 0x08000000L &&
            absoluteTime < nowMs &&
            absoluteTime > nowMs - 366L * 24 * 3_600_000

    /** Stable identity across rescans (same scheme as xDrip's, for parity). */
    val hash: String get() = "Open$relativeTime:$units"

    companion object {
        private const val MAX_UNIT_VALUE = 60.0

        fun parse(buffer: ByteBuffer, reportRelativeTime: Long, nowMs: Long): InsulinDose {
            val rel = buffer.u32()
            val absolute = nowMs - (reportRelativeTime - rel) * 1000L
            val rawUnits = buffer.u32()
            var units = -1.0
            if ((rawUnits and 0xFFFF0000L) == 0xFF000000L) {
                units = (rawUnits and 0xFFFFL) / 10.0
                if (units > MAX_UNIT_VALUE) units = -1.0
            }
            val flags = buffer.u32()
            return InsulinDose(rel, absolute, units, flags, nowMs)
        }
    }
}

class EventReport private constructor() {
    var handle = -1
        private set
    var instance = -1
        private set
    var index = -1L
        private set
    var count = -1L
        private set
    val doses = mutableListOf<InsulinDose>()
    var configuration: Configuration? = null
        private set

    companion object {
        const val MDC_NOTI_CONFIG = 3356
        const val MDC_NOTI_SEGMENT_DATA = 3361

        fun parse(buffer: ByteBuffer, nowMs: Long): EventReport? {
            val handle = buffer.u16()
            val relativeTime = buffer.u32()
            val eventType = buffer.u16()
            /* len */ buffer.u16()
            val er = EventReport()
            er.handle = handle
            when (eventType) {
                MDC_NOTI_SEGMENT_DATA -> {
                    er.instance = buffer.u16()
                    er.index = buffer.u32()
                    er.count = buffer.u32()
                    /* status */ buffer.u16()
                    /* bcount */ buffer.u16()
                    repeat(er.count.toInt()) {
                        er.doses.add(InsulinDose.parse(buffer, relativeTime, nowMs))
                    }
                    return er
                }
                MDC_NOTI_CONFIG -> {
                    er.configuration = Configuration.parse(buffer)
                    return er
                }
            }
            return null
        }
    }
}

class EventRequest private constructor() {
    var handle = -1
        private set
    var type = -1
        private set
    var reportId = -1
        private set

    companion object {
        fun parse(buffer: ByteBuffer): EventRequest {
            val e = EventRequest()
            e.handle = buffer.u16()
            /* currentTime */ buffer.u32()
            e.type = buffer.u16()
            val replyLen = buffer.u16()
            if (replyLen == 4) {
                e.reportId = buffer.u16()
                /* reportResult */ buffer.u16()
            }
            return e
        }

        fun encodeConfigReply(reportId: Int): ByteArray {
            val b = ByteBuffer.allocate(14)
            b.putU16(0)                          // handle
            b.putU32(0)                          // currentTime
            b.putU16(EventReport.MDC_NOTI_CONFIG)
            b.putU16(4)                          // replyLen
            b.putU16(reportId)
            b.putU16(0)                          // result ok
            return b.array()
        }

        fun encodeSegmentConfirm(handle: Int, instance: Int, index: Int, count: Int): ByteArray {
            val b = ByteBuffer.allocate(22)
            b.putU16(handle)
            b.putU32(0xFFFFFFFFL)                // currentTime = -1
            b.putU16(EventReport.MDC_NOTI_SEGMENT_DATA)
            b.putU16(12)                         // replyLen
            b.putU16(instance)
            b.putU16(0)
            b.putU16(index)
            b.putU16(0)
            b.putU16(count)
            b.putU8(0x00)                        // middle block
            b.putU8(0x80)                        // confirmed mark
            return b.array()
        }
    }
}

object ConfirmedActionCodec {
    fun encodeAllSegments(handle: Int, type: Int): ByteArray {
        val inner = ByteBuffer.allocate(6)
        inner.putU16(0x0001)                     // ALL_SEGMENTS
        inner.putU16(2)
        inner.putU16(0)
        return encode(handle, type, inner.array())
    }

    fun encodeSegment(handle: Int, type: Int, segment: Int): ByteArray {
        val inner = ByteBuffer.allocate(2)
        inner.putU16(segment)
        return encode(handle, type, inner.array())
    }

    private fun encode(handle: Int, type: Int, bytes: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(6 + bytes.size)
        b.putU16(handle)
        b.putU16(type)
        b.putIndexedBytes(bytes)
        return b.array()
    }
}

object ArgumentsSimple {
    fun encode(handle: Int): ByteArray {
        val b = ByteBuffer.allocate(6)
        b.putU16(handle)
        b.putU16(0)
        b.putU16(0)
        return b.array()
    }
}
