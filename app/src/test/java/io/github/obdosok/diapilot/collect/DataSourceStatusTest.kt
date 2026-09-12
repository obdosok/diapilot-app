package io.github.obdosok.diapilot.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Data sources state machine, row by row.
 *
 * The screen it feeds is the answer to "why is nothing arriving on this
 * phone", so the one thing it may never do is report a healthy chain while a
 * link is down — or the reverse, which is worse: a red row nobody can act on
 * teaches the user to stop reading the screen. Everything here is arithmetic
 * over [DataSourceInputs], which is why it is a JVM test and not something
 * checked by looking at a device.
 */
class DataSourceStatusTest {

    private val now = 1_700_000_000_000L

    /** A phone where every link works, so each test can break exactly one. */
    private fun healthy(sensorDirect: Boolean = true) = DataSourceInputs(
        nowMs = now,
        sensorDirect = sensorDirect,
        xdripInstalled = true,
        lastBroadcastMs = now - 2 * 60_000,
        lastWebProbeMs = now - 3 * 60_000,
        lastWebOkMs = now - 3 * 60_000,
        nightscoutUrl = null,
        lastMinuteReadingMs = now - 60_000,
        ownBleEnabled = false,
        lastBlePacketMs = 0,
        batteryUnrestricted = true,
        notificationsEnabled = true,
        exactAlarmsAllowed = true,
        serviceStartedMs = now - 3_600_000,
        serviceStoppedMs = 0,
        healthConnect = HealthConnectAccess.GRANTED,
        overlayWanted = false,
        overlayGranted = false,
        nfcPresent = true,
        nfcEnabled = true,
    )

    private fun row(inputs: DataSourceInputs, id: DataSourceId): DataSourceRow? =
        dataSourceRows(inputs).firstOrNull { it.id == id }

    private fun status(inputs: DataSourceInputs, id: DataSourceId): DataSourceStatus =
        requireNotNull(row(inputs, id)) { "no row for $id" }.status

    @Test fun `the sensor-direct build lists every link once, in screen order`() {
        val ids = dataSourceRows(healthy()).map { it.id }
        assertEquals(DataSourceId.entries, ids)
    }

    // The store edition has no sensor of its own, and docs/editions.md forbids
    // a surface that opens onto nothing: those rows are absent, not greyed out.
    @Test fun `without sensor-direct the OOP2 and own-BLE rows do not exist`() {
        val ids = dataSourceRows(healthy(sensorDirect = false)).map { it.id }
        assertFalse(DataSourceId.OOP2 in ids)
        assertFalse(DataSourceId.OWN_BLE in ids)
        // Everything else is still there — NFC included: the NovoPen scan
        // needs the radio in both editions.
        assertEquals(DataSourceId.entries.size - 2, ids.size)
        assertTrue(DataSourceId.NFC in ids)
    }

    @Test fun `xDrip installed or not`() {
        assertEquals(DataSourceStatus.INSTALLED, status(healthy(), DataSourceId.XDRIP_APP))
        assertEquals(
            DataSourceStatus.MISSING,
            status(healthy().copy(xdripInstalled = false), DataSourceId.XDRIP_APP),
        )
    }

    @Test fun `the broadcast ladder is fresh, late, stalled, never`() {
        fun broadcast(ageMin: Long) =
            row(healthy().copy(lastBroadcastMs = now - ageMin * 60_000), DataSourceId.XDRIP_BROADCAST)!!
        assertEquals(DataSourceStatus.FRESH, broadcast(0).status)
        assertEquals(DataSourceStatus.FRESH, broadcast(DATA_SOURCE_BROADCAST_LATE_MIN).status)
        assertEquals(DataSourceStatus.LATE, broadcast(DATA_SOURCE_BROADCAST_LATE_MIN + 1).status)
        assertEquals(DataSourceStatus.LATE, broadcast(StreamStallNotifier.STALL_MIN).status)
        assertEquals(DataSourceStatus.STALLED, broadcast(StreamStallNotifier.STALL_MIN + 1).status)
        assertEquals(
            DataSourceStatus.NEVER,
            row(healthy().copy(lastBroadcastMs = 0), DataSourceId.XDRIP_BROADCAST)!!.status,
        )
    }

    @Test fun `an age is reported for the time-based statuses and for nothing else`() {
        assertEquals(42L, row(healthy().copy(lastBroadcastMs = now - 42 * 60_000), DataSourceId.XDRIP_BROADCAST)!!.ageMin)
        assertNull(row(healthy().copy(lastBroadcastMs = 0), DataSourceId.XDRIP_BROADCAST)!!.ageMin)
        // A permission has no age, and the text layer must not be able to
        // print "0 min ago" about one.
        assertNull(row(healthy(), DataSourceId.NOTIFICATIONS)!!.ageMin)
        assertNull(row(healthy(), DataSourceId.EXACT_ALARMS)!!.ageMin)
    }

    // Never answered and never asked are different phones, and blaming xDrip
    // for a poll that has not run yet is the accusation this row must not make.
    @Test fun `the web service separates not checked from unreachable`() {
        assertEquals(
            DataSourceStatus.NOT_CHECKED,
            status(healthy().copy(lastWebProbeMs = 0, lastWebOkMs = 0), DataSourceId.XDRIP_WEB),
        )
        assertEquals(
            DataSourceStatus.UNREACHABLE,
            status(healthy().copy(lastWebProbeMs = now - 60_000, lastWebOkMs = 0), DataSourceId.XDRIP_WEB),
        )
        val reachable = row(healthy().copy(lastWebOkMs = now - 20 * 60_000), DataSourceId.XDRIP_WEB)!!
        assertEquals(DataSourceStatus.REACHABLE, reachable.status)
        assertEquals(20L, reachable.ageMin)
        // The poll runs every 15 minutes; an answer older than the allowance
        // means the recent probes failed, and the stale age would only reassure.
        val stale = row(
            healthy().copy(lastWebOkMs = now - (DATA_SOURCE_WEB_OK_MAX_MIN + 1) * 60_000),
            DataSourceId.XDRIP_WEB,
        )!!
        assertEquals(DataSourceStatus.UNREACHABLE, stale.status)
        assertNull(stale.ageMin)
    }

    @Test fun `Nightscout reads as absent until a URL is configured`() {
        val absent = row(healthy(), DataSourceId.NIGHTSCOUT)!!
        assertEquals(DataSourceStatus.NOT_CONFIGURED, absent.status)
        assertEquals(DataSourceLevel.ABSENT, absent.status.level)
        assertEquals(
            DataSourceStatus.CONFIGURED,
            status(healthy().copy(nightscoutUrl = "https://ns.example"), DataSourceId.NIGHTSCOUT),
        )
        assertEquals(
            DataSourceStatus.NOT_CONFIGURED,
            status(healthy().copy(nightscoutUrl = "   "), DataSourceId.NIGHTSCOUT),
        )
    }

    @Test fun `the minute streams are late after five silent minutes`() {
        fun oop2(ageMin: Long) =
            status(healthy().copy(lastMinuteReadingMs = now - ageMin * 60_000), DataSourceId.OOP2)
        assertEquals(DataSourceStatus.FRESH, oop2(DATA_SOURCE_MINUTE_LATE_MIN))
        assertEquals(DataSourceStatus.LATE, oop2(DATA_SOURCE_MINUTE_LATE_MIN + 1))
        assertEquals(DataSourceStatus.STALLED, oop2(StreamStallNotifier.STALL_MIN + 1))
        assertEquals(
            DataSourceStatus.NEVER,
            status(healthy().copy(lastMinuteReadingMs = 0), DataSourceId.OOP2),
        )
    }

    // A link the user never switched on is not a fault, and painting it red is
    // how a diagnostics screen trains people to ignore red.
    @Test fun `own BLE is off rather than broken while the switch is off`() {
        val off = row(healthy().copy(ownBleEnabled = false), DataSourceId.OWN_BLE)!!
        assertEquals(DataSourceStatus.OFF, off.status)
        assertEquals(DataSourceLevel.ABSENT, off.status.level)
        assertEquals(
            DataSourceStatus.NEVER,
            status(healthy().copy(ownBleEnabled = true, lastBlePacketMs = 0), DataSourceId.OWN_BLE),
        )
        assertEquals(
            DataSourceStatus.FRESH,
            status(
                healthy().copy(ownBleEnabled = true, lastBlePacketMs = now - 60_000),
                DataSourceId.OWN_BLE,
            ),
        )
    }

    @Test fun `battery, notifications and exact alarms are granted or not`() {
        assertEquals(DataSourceStatus.ALLOWED, status(healthy(), DataSourceId.BATTERY))
        assertEquals(
            DataSourceStatus.RESTRICTED,
            status(healthy().copy(batteryUnrestricted = false), DataSourceId.BATTERY),
        )
        assertEquals(DataSourceStatus.GRANTED, status(healthy(), DataSourceId.NOTIFICATIONS))
        assertEquals(
            DataSourceStatus.DENIED,
            status(healthy().copy(notificationsEnabled = false), DataSourceId.NOTIFICATIONS),
        )
        assertEquals(DataSourceStatus.ALLOWED, status(healthy(), DataSourceId.EXACT_ALARMS))
        assertEquals(
            DataSourceStatus.DENIED,
            status(healthy().copy(exactAlarmsAllowed = false), DataSourceId.EXACT_ALARMS),
        )
    }

    /**
     * The service shares the UI's process, so a started stamp alone survives
     * the service's own death. The stopped stamp is what makes "not running"
     * observable at all — [DiagState.serviceStoppedMs].
     */
    @Test fun `the collector reads as stopped once the stop stamp is the newer one`() {
        assertEquals(DataSourceStatus.RUNNING, status(healthy(), DataSourceId.COLLECTOR_SERVICE))
        assertEquals(
            DataSourceStatus.NOT_RUNNING,
            status(
                healthy().copy(serviceStartedMs = now - 3_600_000, serviceStoppedMs = now - 60_000),
                DataSourceId.COLLECTOR_SERVICE,
            ),
        )
        // A restart after a stop is running again.
        assertEquals(
            DataSourceStatus.RUNNING,
            status(
                healthy().copy(serviceStartedMs = now - 60_000, serviceStoppedMs = now - 3_600_000),
                DataSourceId.COLLECTOR_SERVICE,
            ),
        )
        // A fresh process has neither stamp: nothing has started yet.
        assertEquals(
            DataSourceStatus.NOT_RUNNING,
            status(
                healthy().copy(serviceStartedMs = 0, serviceStoppedMs = 0),
                DataSourceId.COLLECTOR_SERVICE,
            ),
        )
    }

    @Test fun `Health Connect has four answers, and only one of them is a fault`() {
        fun hc(access: HealthConnectAccess) =
            row(healthy().copy(healthConnect = access), DataSourceId.HEALTH_CONNECT)!!
        assertEquals(DataSourceStatus.GRANTED, hc(HealthConnectAccess.GRANTED).status)
        assertEquals(DataSourceStatus.DENIED, hc(HealthConnectAccess.DENIED).status)
        assertEquals(DataSourceLevel.WARN, hc(HealthConnectAccess.STEPS_MISSING).status.level)
        // Not installed is not a misconfiguration, and there is no screen to
        // send the user to either.
        assertEquals(DataSourceLevel.ABSENT, hc(HealthConnectAccess.UNAVAILABLE).status.level)
        assertEquals(DataSourceFix.NONE, hc(HealthConnectAccess.UNAVAILABLE).fix)
        assertEquals(DataSourceFix.HEALTH_CONNECT_SETTINGS, hc(HealthConnectAccess.DENIED).fix)
    }

    @Test fun `the overlay is only a fault when it is switched on without the grant`() {
        assertEquals(
            DataSourceStatus.OFF,
            status(healthy().copy(overlayWanted = false, overlayGranted = false), DataSourceId.OVERLAY),
        )
        assertEquals(
            DataSourceStatus.ON,
            status(healthy().copy(overlayWanted = true, overlayGranted = true), DataSourceId.OVERLAY),
        )
        val missing = row(
            healthy().copy(overlayWanted = true, overlayGranted = false),
            DataSourceId.OVERLAY,
        )!!
        assertEquals(DataSourceStatus.PERMISSION_MISSING, missing.status)
        assertEquals(DataSourceFix.OVERLAY_PERMISSION, missing.fix)
    }

    @Test fun `NFC separates a switched-off radio from a phone without one`() {
        assertEquals(DataSourceStatus.ON, status(healthy(), DataSourceId.NFC))
        assertEquals(
            DataSourceStatus.RADIO_OFF,
            status(healthy().copy(nfcEnabled = false), DataSourceId.NFC),
        )
        val none = row(healthy().copy(nfcPresent = false, nfcEnabled = false), DataSourceId.NFC)!!
        assertEquals(DataSourceStatus.NO_HARDWARE, none.status)
        assertEquals(DataSourceFix.NONE, none.fix)
    }

    // Opening an app that is not installed is a tap that does nothing, so the
    // two xDrip rows drop their control and advise instead.
    @Test fun `the xDrip rows only offer Open xDrip when xDrip is there`() {
        val installed = dataSourceRows(healthy())
        assertEquals(DataSourceFix.OPEN_XDRIP, installed.first { it.id == DataSourceId.XDRIP_BROADCAST }.fix)
        assertEquals(DataSourceFix.OPEN_XDRIP, installed.first { it.id == DataSourceId.XDRIP_WEB }.fix)
        val without = dataSourceRows(healthy().copy(xdripInstalled = false))
        assertEquals(DataSourceFix.NONE, without.first { it.id == DataSourceId.XDRIP_BROADCAST }.fix)
        assertEquals(DataSourceFix.NONE, without.first { it.id == DataSourceId.XDRIP_WEB }.fix)
    }

    // The banner on Today and the stall notification must not be able to
    // disagree about when the app has gone blind.
    @Test fun `the stall question is the notifier's own threshold`() {
        assertFalse(glucoseStalled(now, now - (StreamStallNotifier.STALL_MIN - 1) * 60_000))
        assertTrue(glucoseStalled(now, now - StreamStallNotifier.STALL_MIN * 60_000))
        // No reading at all is the case a stranger's phone starts in.
        assertTrue(glucoseStalled(now, 0))
    }
}
