/**
 * FreeStyle Libre 1/2 FRAM parsing — the decrypted 344-byte sensor memory
 * image read over NFC. Pure functions, no Android.
 *
 * Ported from xDrip+ (https://github.com/NightscoutFoundation/xDrip), GPL-3.0:
 * NFCReaderX.parseData / LibreUtils. FRAM structure documented by the
 * LibreMonitor wiki (Uwe Petersen); sensor status semantics by @keencave.
 *
 * Layout (bytes):
 *   [4]        sensor status
 *   [26]       trend ring index, records 28..123 (16 × 6 bytes, per minute)
 *   [27]       history ring index, records 124..315 (32 × 6, per 15 min)
 *   [316..317] sensor time, minutes since start (little-endian)
 *   CRC16 over three regions: (0,24), (24,296), (320,24); stored LE in the
 *   first two bytes of each region.
 */
package com.diapilot.core.libre

const val FRAM_SIZE = 344
private const val RECORD_SIZE = 6
private const val TREND_START = 28
private const val HISTORY_START = 124
private const val MINUTE_MS = 60_000L

// CRC16 lookup table as used by Abbott (reflected CCITT) — from xDrip LibreUtils.
private val CRC16_TABLE = longArrayOf(
    0, 4489, 8978, 12955, 17956, 22445, 25910, 29887, 35912,
    40385, 44890, 48851, 51820, 56293, 59774, 63735, 4225, 264,
    13203, 8730, 22181, 18220, 30135, 25662, 40137, 36160, 49115,
    44626, 56045, 52068, 63999, 59510, 8450, 12427, 528, 5017,
    26406, 30383, 17460, 21949, 44362, 48323, 36440, 40913, 60270,
    64231, 51324, 55797, 12675, 8202, 4753, 792, 30631, 26158,
    21685, 17724, 48587, 44098, 40665, 36688, 64495, 60006, 55549,
    51572, 16900, 21389, 24854, 28831, 1056, 5545, 10034, 14011,
    52812, 57285, 60766, 64727, 34920, 39393, 43898, 47859, 21125,
    17164, 29079, 24606, 5281, 1320, 14259, 9786, 57037, 53060,
    64991, 60502, 39145, 35168, 48123, 43634, 25350, 29327, 16404,
    20893, 9506, 13483, 1584, 6073, 61262, 65223, 52316, 56789,
    43370, 47331, 35448, 39921, 29575, 25102, 20629, 16668, 13731,
    9258, 5809, 1848, 65487, 60998, 56541, 52564, 47595, 43106,
    39673, 35696, 33800, 38273, 42778, 46739, 49708, 54181, 57662,
    61623, 2112, 6601, 11090, 15067, 20068, 24557, 28022, 31999,
    38025, 34048, 47003, 42514, 53933, 49956, 61887, 57398, 6337,
    2376, 15315, 10842, 24293, 20332, 32247, 27774, 42250, 46211,
    34328, 38801, 58158, 62119, 49212, 53685, 10562, 14539, 2640,
    7129, 28518, 32495, 19572, 24061, 46475, 41986, 38553, 34576,
    62383, 57894, 53437, 49460, 14787, 10314, 6865, 2904, 32743,
    28270, 23797, 19836, 50700, 55173, 58654, 62615, 32808, 37281,
    41786, 45747, 19012, 23501, 26966, 30943, 3168, 7657, 12146,
    16123, 54925, 50948, 62879, 58390, 37033, 33056, 46011, 41522,
    23237, 19276, 31191, 26718, 7393, 3432, 16371, 11898, 59150,
    63111, 50204, 54677, 41258, 45219, 33336, 37809, 27462, 31439,
    18516, 23005, 11618, 15595, 3696, 8185, 63375, 58886, 54429,
    50452, 45483, 40994, 37561, 33584, 31687, 27214, 22741, 18780,
    15843, 11370, 7921, 3960,
)

/** CRC over [start+2, start+size); the stored CRC is LE at [start], [start+1]. */
fun computeCrc16(data: ByteArray, start: Int, size: Int): Long {
    var crc = 0xffffL
    for (i in start + 2 until start + size) {
        crc = (crc shr 8) xor CRC16_TABLE[((crc xor (data[i].toLong() and 0xFF)) and 0xff).toInt()]
    }
    var reverse = 0L
    repeat(16) {
        reverse = (reverse shl 1) or (crc and 1L)
        crc = crc shr 1
    }
    return reverse
}

private fun crcRegionOk(data: ByteArray, start: Int, size: Int): Boolean =
    computeCrc16(data, start, size) ==
        (data[start + 1].toLong() and 0xFF) * 256 + (data[start].toLong() and 0xFF)

/** Write a valid CRC into a region — for building test fixtures. */
fun stampCrc16(data: ByteArray, start: Int, size: Int) {
    val crc = computeCrc16(data, start, size)
    data[start] = (crc and 0xFF).toByte()
    data[start + 1] = ((crc shr 8) and 0xFF).toByte()
}

fun verifyFramCrc(data: ByteArray): Boolean =
    data.size >= FRAM_SIZE &&
        crcRegionOk(data, 0, 24) && crcRegionOk(data, 24, 296) && crcRegionOk(data, 320, 24)

/** Little-endian LSB-first bit extractor (xDrip LibreOOPAlgorithm.readBits). */
fun readBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int): Int {
    if (bitCount == 0) return 0
    var res = 0
    for (i in 0 until bitCount) {
        val total = byteOffset * 8 + bitOffset + i
        val b = total / 8
        val bit = total % 8
        if (total >= 0 && ((buffer[b].toInt() shr bit) and 0x1) == 1) {
            res = res or (1 shl i)
        }
    }
    return res
}

/** Sensor state from the FRAM header; the app renders its label (i18n.LibreText). */
enum class LibreStatus(val code: Int, val usable: Boolean) {
    NOT_STARTED(0x01, false),
    STARTING(0x02, true),
    READY(0x03, true),
    EXPIRED(0x04, false),
    SHUTDOWN(0x05, false),
    FAILURE(0x06, false),
    UNKNOWN(-1, false);

    companion object {
        fun of(code: Int): LibreStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class LibreSample(
    val sensorTimeMin: Int,
    val tsMs: Long,
    val raw: Int,           // 13-bit factory raw
    val bgMgdl: Double? = null,  // filled by the external algorithm when present
)

data class LibreFramParse(
    val status: LibreStatus,
    val sensorTimeMin: Int,      // sensor age in minutes
    val sensorStartMs: Long,
    val trend: List<LibreSample>,    // ascending, per-minute, up to 16
    val history: List<LibreSample>,  // ascending, per-15-min, up to 32
)

private fun rawAt(data: ByteArray, offset: Int): Int =
    (256 * (data[offset + 1].toInt() and 0xFF) + (data[offset].toInt() and 0xFF)) and 0x1FFF

/** Parse a DECRYPTED 344-byte FRAM captured at [captureMs]. Null on junk. */
fun parseFram(data: ByteArray, captureMs: Long): LibreFramParse? {
    if (data.size < FRAM_SIZE) return null
    val sensorTime = 256 * (data[317].toInt() and 0xFF) + (data[316].toInt() and 0xFF)
    if (sensorTime < 0 || sensorTime > 24 * 60 * 30) return null
    val sensorStart = captureMs - sensorTime * MINUTE_MS

    val trendIdx = data[26].toInt() and 0xFF
    val trend = (0 until 16).mapNotNull { index ->
        var i = trendIdx - index - 1
        if (i < 0) i += 16
        val o = i * RECORD_SIZE + TREND_START
        if (o + RECORD_SIZE > data.size) return@mapNotNull null
        val time = maxOf(0, sensorTime - index)
        LibreSample(time, sensorStart + time * MINUTE_MS, rawAt(data, o))
    }.sortedBy { it.sensorTimeMin }

    val histIdx = data[27].toInt() and 0xFF
    val history = (0 until 32).mapNotNull { index ->
        var i = histIdx - index - 1
        if (i < 0) i += 32
        val o = i * RECORD_SIZE + HISTORY_START
        if (o + RECORD_SIZE > data.size) return@mapNotNull null
        val time = maxOf(0, kotlin.math.abs((sensorTime - 3) / 15) * 15 - index * 15)
        LibreSample(time, sensorStart + time * MINUTE_MS, rawAt(data, o))
    }.sortedBy { it.sensorTimeMin }

    return LibreFramParse(
        status = LibreStatus.of(data[4].toInt()),
        sensorTimeMin = sensorTime,
        sensorStartMs = sensorStart,
        trend = trend,
        history = history,
    )
}

/**
 * Attach external-algorithm glucose values (mg/dl). Both lists are ascending
 * (oldest→newest). When OOP2 returns a different count than we parsed, align
 * at the NEWEST end and salvage the overlap — a partial OOP2 result is its
 * most-recent values, the ones worth keeping — instead of dropping the whole
 * batch, which left the main line with a hole after a long BLE gap (review).
 * Equal sizes (the normal case) attach one-to-one, unchanged.
 */
fun attachBg(samples: List<LibreSample>, bg: IntArray?): List<LibreSample> {
    if (bg == null || bg.isEmpty() || samples.isEmpty()) return samples
    val k = minOf(samples.size, bg.size)
    val sOff = samples.size - k
    val bOff = bg.size - k
    return samples.mapIndexed { i, s ->
        if (i >= sOff) s.copy(bgMgdl = bg[bOff + (i - sOff)].toDouble()) else s
    }
}

/** Length of an ISO-15693 UID — the only id a Libre sensor presents. */
const val LIBRE_UID_BYTES = 8

/**
 * Serial number from the 8-byte NFC UID (xDrip decodeSerialNumberKey), or
 * null when [uid] is not [LIBRE_UID_BYTES] long.
 *
 * Null rather than a throw because the app's reader mode also dispatches
 * NFC-A and NFC-B tags (the pen, a transit card, anything in a wallet), whose
 * ids are 4, 7 or 10 bytes; copying eight bytes out of one of those threw an
 * exception inside the scan thread. A wrong-sized id is not a Libre.
 */
fun decodeLibreSerial(uid: ByteArray): String? {
    if (uid.size != LIBRE_UID_BYTES) return null
    val lookup = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"
    val input = ByteArray(11).also { uid.copyInto(it, 3, 0, 8) }
    val short = ByteArray(8)
    for (i in 2 until 8) short[i - 2] = input[(2 + 8) - i]
    val binary = StringBuilder()
    for (i in 0 until 8) {
        binary.append(
            Integer.toBinaryString(short[i].toInt() and 0xFF).padStart(8, '0'),
        )
    }
    val sb = StringBuilder("0")
    for (i in 0 until 10) {
        var value = 0
        for (k in 0 until 5) value = value * 2 + (binary[5 * i + k] - '0')
        sb.append(lookup[value])
    }
    return sb.toString()
}
