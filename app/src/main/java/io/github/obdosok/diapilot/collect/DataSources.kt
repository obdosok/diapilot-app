package io.github.obdosok.diapilot.collect

// THE COLLECTION CHAIN, ROW BY ROW — the state machine behind the "Data
// sources" screen (audit finding P6).
//
// On the maintainer's phone the chain works because it was built link by link.
// On a stranger's phone it is xDrip, its web service, OOPAlgorithm2, Health
// Connect, a battery policy, a notification permission, exact alarms, an
// overlay grant and an NFC radio — nine ways to collect nothing while the app
// looks healthy. This file turns what the collector already observes into one
// status per link, and nothing else: it reads no Android API, no clock and no
// database, so every row is a pure function of [DataSourceInputs] and is
// pinned by a JVM test rather than by looking at a phone.
//
// It decides nothing about collection. The screen built on it can start the
// collector and open system screens; the rows themselves only report.

/** How bad a row is, which is what the screen colours and orders by. */
enum class DataSourceLevel {
    /** Working. */
    OK,

    /** Working, but late or partial — worth knowing, not yet broken. */
    WARN,

    /** Broken: something the app needs is not arriving or not granted. */
    BAD,

    /**
     * Not in use on this phone — switched off, not configured, or no hardware.
     * Deliberately NOT an error: a user with no lock-screen chip is not
     * misconfigured, and painting that red teaches people to ignore red.
     */
    ABSENT,
}

/** One link in the chain. The order of the entries is the order on screen. */
enum class DataSourceId {
    XDRIP_APP,
    XDRIP_BROADCAST,
    XDRIP_WEB,
    NIGHTSCOUT,

    /** Sensor-direct: the decoded per-minute stream OOPAlgorithm2 broadcasts. */
    OOP2,

    /** Sensor-direct: this app's own BLE link to the sensor. */
    OWN_BLE,
    BATTERY,
    NOTIFICATIONS,
    EXACT_ALARMS,
    COLLECTOR_SERVICE,
    HEALTH_CONNECT,
    OVERLAY,
    NFC,
}

/**
 * What a row says. Each status names one reachable situation and carries its
 * own severity, so the screen never has to re-derive "is this bad" from the
 * text it is about to show.
 */
enum class DataSourceStatus(val level: DataSourceLevel) {
    /** Data arrived recently enough. Carries an age. */
    FRESH(DataSourceLevel.OK),

    /** Data is arriving, but the last one is older than the cadence. Age. */
    LATE(DataSourceLevel.WARN),

    /** Nothing for longer than [StreamStallNotifier.STALL_MIN]. Age. */
    STALLED(DataSourceLevel.BAD),

    /** Nothing has ever arrived on this channel. */
    NEVER(DataSourceLevel.BAD),
    INSTALLED(DataSourceLevel.OK),
    MISSING(DataSourceLevel.BAD),

    /** Answered the poll. Carries the age of that answer. */
    REACHABLE(DataSourceLevel.OK),
    UNREACHABLE(DataSourceLevel.BAD),

    /** The poll has not run yet on this install — not the same as "down". */
    NOT_CHECKED(DataSourceLevel.WARN),
    CONFIGURED(DataSourceLevel.OK),
    NOT_CONFIGURED(DataSourceLevel.ABSENT),
    ALLOWED(DataSourceLevel.OK),
    RESTRICTED(DataSourceLevel.BAD),
    GRANTED(DataSourceLevel.OK),
    DENIED(DataSourceLevel.BAD),

    /** Health Connect: heart rate and sleep granted, steps not. */
    STEPS_MISSING(DataSourceLevel.WARN),
    RUNNING(DataSourceLevel.OK),
    NOT_RUNNING(DataSourceLevel.BAD),
    ON(DataSourceLevel.OK),
    OFF(DataSourceLevel.ABSENT),

    /** Switched on in the app, but the system grant it needs is missing. */
    PERMISSION_MISSING(DataSourceLevel.BAD),
    RADIO_OFF(DataSourceLevel.WARN),
    NO_HARDWARE(DataSourceLevel.ABSENT),

    /** Health Connect is not installed or not supported on this phone. */
    UNAVAILABLE(DataSourceLevel.ABSENT),
}

/**
 * Where the row's "Fix" control goes. [NONE] means no screen on this phone can
 * fix it — the row then carries a sentence saying what to do instead, which is
 * the honest answer for "install another app" and for a source this build does
 * not have yet.
 */
enum class DataSourceFix {
    NONE,
    OPEN_XDRIP,
    BATTERY_SETTINGS,
    NOTIFICATION_SETTINGS,
    EXACT_ALARM_SETTINGS,
    START_COLLECTOR,
    HEALTH_CONNECT_SETTINGS,
    OVERLAY_PERMISSION,
    NFC_SETTINGS,
}

/** Health Connect, as the permission check answered. */
enum class HealthConnectAccess { UNAVAILABLE, DENIED, STEPS_MISSING, GRANTED }

/**
 * Everything the rows are derived from. Assembled by the screen from
 * [DiagState], the preferences and the system services; handed to
 * [dataSourceRows] as plain values so the derivation stays testable.
 */
data class DataSourceInputs(
    val nowMs: Long,
    /** [io.github.obdosok.diapilot.Edition.sensorDirect] — decides which rows exist at all. */
    val sensorDirect: Boolean,
    val xdripInstalled: Boolean,
    val lastBroadcastMs: Long,
    val lastWebProbeMs: Long,
    val lastWebOkMs: Long,
    /** The configured Nightscout base URL; null or blank until a later package adds the source. */
    val nightscoutUrl: String?,
    val lastMinuteReadingMs: Long,
    val ownBleEnabled: Boolean,
    val lastBlePacketMs: Long,
    val batteryUnrestricted: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val serviceStartedMs: Long,
    val serviceStoppedMs: Long,
    val healthConnect: HealthConnectAccess,
    val overlayWanted: Boolean,
    val overlayGranted: Boolean,
    val nfcPresent: Boolean,
    val nfcEnabled: Boolean,
)

/**
 * One row of the screen. [ageMin] is filled only for the statuses that are
 * about time ([DataSourceStatus.FRESH], [DataSourceStatus.LATE],
 * [DataSourceStatus.STALLED], [DataSourceStatus.REACHABLE]) and is null
 * everywhere else, so the text layer cannot print "0 min ago" about a
 * permission.
 */
data class DataSourceRow(
    val id: DataSourceId,
    val status: DataSourceStatus,
    val ageMin: Long? = null,
    val fix: DataSourceFix = DataSourceFix.NONE,
)

/**
 * The xDrip broadcast arrives every ~5 minutes; one missed round is normal
 * enough not to alarm anyone, two is late. The BAD threshold is deliberately
 * [StreamStallNotifier.STALL_MIN] — the same number the stall notification
 * already fires on, so the screen and the notification cannot disagree about
 * when the app has gone blind.
 */
const val DATA_SOURCE_BROADCAST_LATE_MIN = 10L

/** The per-minute streams (OOP2, own BLE) are late after five silent minutes. */
const val DATA_SOURCE_MINUTE_LATE_MIN = 5L

/**
 * The web-service poll runs every 15 minutes and Doze can defer it, so an
 * answer within 35 minutes still means "reachable"; older than that and the
 * recent probes have been failing.
 */
const val DATA_SOURCE_WEB_OK_MAX_MIN = 35L

/**
 * The chain as it stands, in screen order.
 *
 * Sensor-direct rows (OOP2, own BLE) are absent — not greyed out — where
 * [DataSourceInputs.sensorDirect] is false: the store edition has no such
 * source, and a row about a capability the build does not carry is exactly the
 * "screen that opens onto nothing" `docs/editions.md` forbids. The NFC row
 * stays in both editions because the NovoPen scan needs the radio there too.
 */
fun dataSourceRows(inputs: DataSourceInputs): List<DataSourceRow> = buildList {
    add(
        DataSourceRow(
            DataSourceId.XDRIP_APP,
            if (inputs.xdripInstalled) DataSourceStatus.INSTALLED else DataSourceStatus.MISSING,
            // Nothing on the phone can install it, and xDrip is not on Play —
            // so this row advises instead of pointing at a screen.
            fix = DataSourceFix.NONE,
        ),
    )
    add(
        streamRow(
            DataSourceId.XDRIP_BROADCAST,
            lastMs = inputs.lastBroadcastMs,
            nowMs = inputs.nowMs,
            lateMin = DATA_SOURCE_BROADCAST_LATE_MIN,
            // Only xDrip's own settings can turn the local broadcast on, and
            // it has to be installed before it can be opened.
            fix = if (inputs.xdripInstalled) DataSourceFix.OPEN_XDRIP else DataSourceFix.NONE,
        ),
    )
    add(
        webRow(
            inputs,
            fix = if (inputs.xdripInstalled) DataSourceFix.OPEN_XDRIP else DataSourceFix.NONE,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.NIGHTSCOUT,
            if (inputs.nightscoutUrl.isNullOrBlank()) {
                DataSourceStatus.NOT_CONFIGURED
            } else {
                DataSourceStatus.CONFIGURED
            },
            fix = DataSourceFix.NONE,
        ),
    )
    if (inputs.sensorDirect) {
        add(
            streamRow(
                DataSourceId.OOP2,
                lastMs = inputs.lastMinuteReadingMs,
                nowMs = inputs.nowMs,
                lateMin = DATA_SOURCE_MINUTE_LATE_MIN,
                // OOPAlgorithm2 is a separate app whose package this app does
                // not even declare visibility for — advice, not an intent.
                fix = DataSourceFix.NONE,
            ),
        )
        add(
            if (!inputs.ownBleEnabled) {
                DataSourceRow(DataSourceId.OWN_BLE, DataSourceStatus.OFF)
            } else {
                streamRow(
                    DataSourceId.OWN_BLE,
                    lastMs = inputs.lastBlePacketMs,
                    nowMs = inputs.nowMs,
                    lateMin = DATA_SOURCE_MINUTE_LATE_MIN,
                    fix = DataSourceFix.NONE,
                )
            },
        )
    }
    add(
        DataSourceRow(
            DataSourceId.BATTERY,
            if (inputs.batteryUnrestricted) DataSourceStatus.ALLOWED else DataSourceStatus.RESTRICTED,
            fix = DataSourceFix.BATTERY_SETTINGS,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.NOTIFICATIONS,
            if (inputs.notificationsEnabled) DataSourceStatus.GRANTED else DataSourceStatus.DENIED,
            fix = DataSourceFix.NOTIFICATION_SETTINGS,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.EXACT_ALARMS,
            if (inputs.exactAlarmsAllowed) DataSourceStatus.ALLOWED else DataSourceStatus.DENIED,
            fix = DataSourceFix.EXACT_ALARM_SETTINGS,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.COLLECTOR_SERVICE,
            if (inputs.serviceStartedMs > 0 && inputs.serviceStartedMs >= inputs.serviceStoppedMs) {
                DataSourceStatus.RUNNING
            } else {
                DataSourceStatus.NOT_RUNNING
            },
            fix = DataSourceFix.START_COLLECTOR,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.HEALTH_CONNECT,
            when (inputs.healthConnect) {
                HealthConnectAccess.UNAVAILABLE -> DataSourceStatus.UNAVAILABLE
                HealthConnectAccess.DENIED -> DataSourceStatus.DENIED
                HealthConnectAccess.STEPS_MISSING -> DataSourceStatus.STEPS_MISSING
                HealthConnectAccess.GRANTED -> DataSourceStatus.GRANTED
            },
            // Nothing to open where the hub is not installed at all.
            fix = if (inputs.healthConnect == HealthConnectAccess.UNAVAILABLE) {
                DataSourceFix.NONE
            } else {
                DataSourceFix.HEALTH_CONNECT_SETTINGS
            },
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.OVERLAY,
            when {
                !inputs.overlayWanted -> DataSourceStatus.OFF
                inputs.overlayGranted -> DataSourceStatus.ON
                else -> DataSourceStatus.PERMISSION_MISSING
            },
            fix = if (inputs.overlayWanted) DataSourceFix.OVERLAY_PERMISSION else DataSourceFix.NONE,
        ),
    )
    add(
        DataSourceRow(
            DataSourceId.NFC,
            when {
                !inputs.nfcPresent -> DataSourceStatus.NO_HARDWARE
                inputs.nfcEnabled -> DataSourceStatus.ON
                else -> DataSourceStatus.RADIO_OFF
            },
            fix = if (inputs.nfcPresent) DataSourceFix.NFC_SETTINGS else DataSourceFix.NONE,
        ),
    )
}

/** The age ladder every arriving stream is judged by. */
private fun streamRow(
    id: DataSourceId,
    lastMs: Long,
    nowMs: Long,
    lateMin: Long,
    fix: DataSourceFix,
): DataSourceRow {
    if (lastMs <= 0) return DataSourceRow(id, DataSourceStatus.NEVER, fix = fix)
    val ageMin = (nowMs - lastMs) / 60_000
    val status = when {
        ageMin <= lateMin -> DataSourceStatus.FRESH
        ageMin <= StreamStallNotifier.STALL_MIN -> DataSourceStatus.LATE
        else -> DataSourceStatus.STALLED
    }
    return DataSourceRow(id, status, ageMin = ageMin, fix = fix)
}

/**
 * The web service, from the two poll stamps. An answer inside
 * [DATA_SOURCE_WEB_OK_MAX_MIN] is reachable and reports the age of that
 * answer; an older one means the recent probes failed, so the age would only
 * reassure and is dropped. Never answered and never asked are different
 * phones, and the second one is not xDrip's fault.
 */
private fun webRow(inputs: DataSourceInputs, fix: DataSourceFix): DataSourceRow {
    val okAgeMin = if (inputs.lastWebOkMs > 0) (inputs.nowMs - inputs.lastWebOkMs) / 60_000 else null
    return when {
        okAgeMin != null && okAgeMin <= DATA_SOURCE_WEB_OK_MAX_MIN ->
            DataSourceRow(DataSourceId.XDRIP_WEB, DataSourceStatus.REACHABLE, okAgeMin, fix)
        inputs.lastWebProbeMs <= 0 && inputs.lastWebOkMs <= 0 ->
            DataSourceRow(DataSourceId.XDRIP_WEB, DataSourceStatus.NOT_CHECKED, fix = fix)
        else -> DataSourceRow(DataSourceId.XDRIP_WEB, DataSourceStatus.UNREACHABLE, fix = fix)
    }
}

/**
 * Is the glucose stream stalled right now? One definition, shared by the Today
 * banner and by anything else that has to ask.
 *
 * [StreamStallNotifier] owns the threshold and already fires a notification on
 * it; this is the same question asked of state the screen already holds, not a
 * second alert path. [freshestReadingMs] is the newest of the main and the
 * per-minute stream, exactly as the notifier computes it.
 */
fun glucoseStalled(nowMs: Long, freshestReadingMs: Long): Boolean =
    freshestReadingMs <= 0 || (nowMs - freshestReadingMs) / 60_000 >= StreamStallNotifier.STALL_MIN
