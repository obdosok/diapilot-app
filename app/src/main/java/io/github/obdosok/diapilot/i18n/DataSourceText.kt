package io.github.obdosok.diapilot.i18n

import android.content.Context
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.DataSourceFix
import io.github.obdosok.diapilot.collect.DataSourceId
import io.github.obdosok.diapilot.collect.DataSourceLevel
import io.github.obdosok.diapilot.collect.DataSourceRow
import io.github.obdosok.diapilot.collect.DataSourceStatus

/**
 * Renders the Data sources rows ([io.github.obdosok.diapilot.collect.dataSourceRows])
 * as text. The derivation returns enums and the screen shows strings, the same
 * split the `:core` renderers next to this file use — so the state machine can
 * be pinned by a JVM test that knows nothing about resources.
 */
object DataSourceText {

    fun title(context: Context, id: DataSourceId): String = context.localized().getString(
        when (id) {
            DataSourceId.ALERTS -> R.string.data_sources_row_alerts_title
            DataSourceId.XDRIP_APP -> R.string.data_sources_row_xdrip_app_title
            DataSourceId.XDRIP_BROADCAST -> R.string.data_sources_row_xdrip_broadcast_title
            DataSourceId.XDRIP_WEB -> R.string.data_sources_row_xdrip_web_title
            DataSourceId.NIGHTSCOUT -> R.string.data_sources_row_nightscout_title
            DataSourceId.OOP2 -> R.string.data_sources_row_oop2_title
            DataSourceId.OWN_BLE -> R.string.data_sources_row_own_ble_title
            DataSourceId.BATTERY -> R.string.data_sources_row_battery_title
            DataSourceId.NOTIFICATIONS -> R.string.data_sources_row_notifications_title
            DataSourceId.EXACT_ALARMS -> R.string.data_sources_row_exact_alarms_title
            DataSourceId.COLLECTOR_SERVICE -> R.string.data_sources_row_collector_service_title
            DataSourceId.HEALTH_CONNECT -> R.string.data_sources_row_health_connect_title
            DataSourceId.OVERLAY -> R.string.data_sources_row_overlay_title
            DataSourceId.NFC -> R.string.data_sources_row_nfc_title
        },
    )

    /**
     * What breaks while the row is red. NFC is the one line that differs by
     * edition: the NovoPen scan needs the radio in both, a sensor scan only
     * where [io.github.obdosok.diapilot.Edition.sensorDirect] is on, and the
     * store edition must not be told about a scan it cannot perform.
     */
    fun why(context: Context, id: DataSourceId, sensorDirect: Boolean): String =
        context.localized().getString(
            when (id) {
                DataSourceId.ALERTS -> R.string.data_sources_row_alerts_why
                DataSourceId.XDRIP_APP -> R.string.data_sources_row_xdrip_app_why
                DataSourceId.XDRIP_BROADCAST -> R.string.data_sources_row_xdrip_broadcast_why
                DataSourceId.XDRIP_WEB -> R.string.data_sources_row_xdrip_web_why
                DataSourceId.NIGHTSCOUT -> R.string.data_sources_row_nightscout_why
                DataSourceId.OOP2 -> R.string.data_sources_row_oop2_why
                DataSourceId.OWN_BLE -> R.string.data_sources_row_own_ble_why
                DataSourceId.BATTERY -> R.string.data_sources_row_battery_why
                DataSourceId.NOTIFICATIONS -> R.string.data_sources_row_notifications_why
                DataSourceId.EXACT_ALARMS -> R.string.data_sources_row_exact_alarms_why
                DataSourceId.COLLECTOR_SERVICE -> R.string.data_sources_row_collector_service_why
                DataSourceId.HEALTH_CONNECT -> R.string.data_sources_row_health_connect_why
                DataSourceId.OVERLAY -> R.string.data_sources_row_overlay_why
                DataSourceId.NFC ->
                    if (sensorDirect) {
                        R.string.data_sources_row_nfc_why_sensor_direct
                    } else {
                        R.string.data_sources_row_nfc_why
                    }
            },
        )

    fun status(context: Context, row: DataSourceRow): String {
        val text = context.localized()
        val age = row.ageMin ?: 0L
        return when (row.status) {
            DataSourceStatus.ARMED -> text.getString(R.string.data_sources_status_armed)
            DataSourceStatus.FRESH -> text.getString(R.string.data_sources_status_fresh, age)
            DataSourceStatus.LATE -> text.getString(R.string.data_sources_status_late, age)
            DataSourceStatus.STALLED -> text.getString(R.string.data_sources_status_stalled, age)
            DataSourceStatus.NEVER -> text.getString(R.string.data_sources_status_never)
            DataSourceStatus.INSTALLED -> text.getString(R.string.data_sources_status_installed)
            DataSourceStatus.MISSING -> text.getString(R.string.data_sources_status_missing)
            DataSourceStatus.REACHABLE -> text.getString(R.string.data_sources_status_reachable, age)
            DataSourceStatus.UNREACHABLE -> text.getString(R.string.data_sources_status_unreachable)
            DataSourceStatus.NOT_CHECKED -> text.getString(R.string.data_sources_status_not_checked)
            DataSourceStatus.CONFIGURED -> text.getString(R.string.data_sources_status_configured)
            DataSourceStatus.NOT_CONFIGURED -> text.getString(R.string.data_sources_status_not_configured)
            DataSourceStatus.ALLOWED -> text.getString(R.string.data_sources_status_allowed)
            DataSourceStatus.RESTRICTED -> text.getString(R.string.data_sources_status_restricted)
            DataSourceStatus.GRANTED -> text.getString(R.string.data_sources_status_granted)
            DataSourceStatus.DENIED -> text.getString(R.string.data_sources_status_denied)
            DataSourceStatus.STEPS_MISSING -> text.getString(R.string.data_sources_status_steps_missing)
            DataSourceStatus.RUNNING -> text.getString(R.string.data_sources_status_running)
            DataSourceStatus.NOT_RUNNING -> text.getString(R.string.data_sources_status_not_running)
            DataSourceStatus.ON -> text.getString(R.string.data_sources_status_on)
            DataSourceStatus.OFF -> text.getString(R.string.data_sources_status_off)
            DataSourceStatus.PERMISSION_MISSING ->
                text.getString(R.string.data_sources_status_permission_missing)
            DataSourceStatus.RADIO_OFF -> text.getString(R.string.data_sources_status_radio_off)
            DataSourceStatus.NO_HARDWARE -> text.getString(R.string.data_sources_status_no_hardware)
            DataSourceStatus.UNAVAILABLE -> text.getString(R.string.data_sources_status_unavailable)
        }
    }

    /** The Fix control's label, or null where no screen can fix the row. */
    fun fixLabel(context: Context, fix: DataSourceFix): String? {
        val id = when (fix) {
            DataSourceFix.NONE -> return null
            DataSourceFix.OPEN_XDRIP -> R.string.data_sources_fix_open_xdrip
            DataSourceFix.BATTERY_SETTINGS -> R.string.data_sources_fix_battery
            DataSourceFix.NOTIFICATION_SETTINGS -> R.string.data_sources_fix_notifications
            DataSourceFix.EXACT_ALARM_SETTINGS -> R.string.data_sources_fix_exact_alarms
            DataSourceFix.START_COLLECTOR -> R.string.data_sources_fix_start_collector
            DataSourceFix.HEALTH_CONNECT_SETTINGS -> R.string.data_sources_fix_health_connect
            DataSourceFix.OVERLAY_PERMISSION -> R.string.data_sources_fix_overlay
            DataSourceFix.NFC_SETTINGS -> R.string.data_sources_fix_nfc
        }
        return context.localized().getString(id)
    }

    /**
     * What to do where the row has no Fix control: install another app, wait
     * for a source this build does not carry yet, or act on the sensor itself.
     *
     * Not for a row that is working, and not for a switch the user turned off
     * themselves ([DataSourceStatus.OFF]) — telling someone how to fix a
     * feature they declined is how a diagnostics screen starts nagging. A
     * source that is merely missing (Nightscout not configured, Health Connect
     * not on the phone) does get its sentence: that is the one thing the user
     * cannot work out from the row alone.
     */
    fun advice(context: Context, row: DataSourceRow): String? {
        if (row.fix != DataSourceFix.NONE) return null
        if (row.status.level == DataSourceLevel.OK || row.status == DataSourceStatus.OFF) return null
        val id = when (row.id) {
            DataSourceId.ALERTS -> R.string.data_sources_advice_alerts
            DataSourceId.XDRIP_APP -> R.string.data_sources_advice_install_xdrip
            DataSourceId.XDRIP_BROADCAST, DataSourceId.XDRIP_WEB ->
                R.string.data_sources_advice_needs_xdrip
            DataSourceId.NIGHTSCOUT -> R.string.data_sources_advice_nightscout
            DataSourceId.OOP2 -> R.string.data_sources_advice_oop2
            DataSourceId.OWN_BLE -> R.string.data_sources_advice_own_ble
            DataSourceId.HEALTH_CONNECT -> R.string.data_sources_advice_health_connect
            else -> return null
        }
        return context.localized().getString(id)
    }
}
