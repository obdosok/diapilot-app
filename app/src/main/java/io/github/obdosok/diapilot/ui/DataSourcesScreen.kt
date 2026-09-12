package io.github.obdosok.diapilot.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.Edition
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.CollectorService
import io.github.obdosok.diapilot.collect.DataSourceFix
import io.github.obdosok.diapilot.collect.DataSourceId
import io.github.obdosok.diapilot.collect.DataSourceInputs
import io.github.obdosok.diapilot.collect.DataSourceLevel
import io.github.obdosok.diapilot.collect.DataSourceRow
import io.github.obdosok.diapilot.collect.DiagState
import io.github.obdosok.diapilot.collect.HealthConnectAccess
import io.github.obdosok.diapilot.collect.LockScreenOverlay
import io.github.obdosok.diapilot.collect.XdripApp
import io.github.obdosok.diapilot.collect.dataSourceRows
import io.github.obdosok.diapilot.data.HealthConnectSync
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.i18n.DataSourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "DATA SOURCES" — the screen that answers "why is nothing arriving?" without
 * a cable and without the maintainer.
 *
 * The dependency chain is fragile on a stranger's phone (audit P6): xDrip, its
 * web service, OOPAlgorithm2, Health Connect, the battery policy, the
 * notification permission, exact alarms, the overlay grant, NFC. Each of those
 * fails quietly and the app keeps drawing the last reading it has, so the
 * screen states every link, says in one line what breaks while that link is
 * red, and offers the system screen that fixes it.
 *
 * The state machine is [dataSourceRows], which is pure and tested on the JVM;
 * everything Android-shaped lives here — reading the live inputs and launching
 * the fix.
 */
@Composable
internal fun DataSourcesScreen(onBack: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    var tick by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<DataSourceRow>>(emptyList()) }
    LaunchedEffect(tick) {
        rows = readDataSources(context, graph.store)
    }
    DataSourcesContent(
        rows = rows,
        sensorDirect = Edition.sensorDirect,
        onFix = { fix ->
            val launched = launchFix(context, fix)
            // A granted permission or a started service only shows up on the
            // next read, and the user comes back to this screen expecting the
            // row to have changed.
            tick++
            launched
        },
        onRecheck = { tick++ },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The screen without its inputs: rows in, taps out. Separate from
 * [DataSourcesScreen] so a Robolectric test can render every row of every
 * status without a phone that has any of them.
 */
@Composable
internal fun DataSourcesContent(
    rows: List<DataSourceRow>,
    sensorDirect: Boolean,
    onFix: (DataSourceFix) -> Boolean = { false },
    onRecheck: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Which row's Fix found no screen on this phone. One at a time: the
    // message belongs under the row that was tapped, not in a banner.
    var noScreenFor by remember { mutableStateOf<DataSourceId?>(null) }
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.data_sources_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.data_sources_back)) }
        }
        Text(
            stringResource(R.string.data_sources_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            DataSourceText.title(context, row.id),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            DataSourceText.status(context, row),
                            style = MaterialTheme.typography.labelMedium,
                            color = levelColor(row.status.level),
                            textAlign = TextAlign.End,
                        )
                    }
                    Text(
                        DataSourceText.why(context, row.id, sensorDirect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DataSourceText.advice(context, row)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // The Fix is offered only where there is something to fix:
                    // a working row with a button next to it invites a tap that
                    // can only make things worse.
                    if (row.status.level != DataSourceLevel.OK) {
                        DataSourceText.fixLabel(context, row.fix)?.let { label ->
                            TextButton(onClick = {
                                noScreenFor = if (onFix(row.fix)) null else row.id
                            }) { Text(label) }
                        }
                    }
                    if (noScreenFor == row.id) {
                        Text(
                            stringResource(R.string.data_sources_fix_no_screen),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onRecheck) { Text(stringResource(R.string.data_sources_recheck)) }
    }
}

@Composable
private fun levelColor(level: DataSourceLevel): Color = when (level) {
    DataSourceLevel.OK -> MaterialTheme.colorScheme.primary
    DataSourceLevel.WARN -> MaterialTheme.colorScheme.tertiary
    DataSourceLevel.BAD -> MaterialTheme.colorScheme.error
    DataSourceLevel.ABSENT -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Every input the rows are derived from, read once. The database query and the
 * Health Connect permission check are the only slow parts and both go to IO;
 * the rest are process-local fields and system-service calls.
 */
private suspend fun readDataSources(context: Context, store: CollectorStore): List<DataSourceRow> {
    val (readings, healthConnect) = withContext(Dispatchers.IO) {
        val minute = store.lastMinuteReading()?.tsMs ?: 0L
        // The newest of the two streams, which is what "the app still has
        // something to judge" means — the same pair [AlertTick] and the stall
        // notification ask about, so the three cannot disagree.
        val freshest = maxOf(minute, store.lastSensorReading()?.tsMs ?: 0L)
        val access = when {
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE ->
                HealthConnectAccess.UNAVAILABLE
            !HealthConnectSync.hasPermissions(context) -> HealthConnectAccess.DENIED
            !HealthConnectSync.hasStepsPermission(context) -> HealthConnectAccess.STEPS_MISSING
            else -> HealthConnectAccess.GRANTED
        }
        (minute to freshest) to access
    }
    val nfc = NfcAdapter.getDefaultAdapter(context)
    val alarms = context.getSystemService(AlarmManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    return dataSourceRows(
        DataSourceInputs(
            nowMs = System.currentTimeMillis(),
            sensorDirect = Edition.sensorDirect,
            xdripInstalled = XdripApp.installed(context),
            lastBroadcastMs = DiagState.lastXdripBroadcastMs,
            lastWebProbeMs = DiagState.lastXdripWebProbeMs,
            lastWebOkMs = DiagState.lastXdripWebOkMs,
            // NO SOURCE TO READ YET. The Nightscout source itself is a later
            // work package (roadmap O-B); until it lands the row reports "not
            // configured", which is the truth about this build, and wiring it
            // up is this one argument.
            nightscoutUrl = null,
            lastMinuteReadingMs = readings.first,
            ownBleEnabled = Settings.ownBleEnabled(context),
            lastBlePacketMs = DiagState.bleLastPacketMs,
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            // Below API 31 an exact alarm needs no grant, so there is nothing
            // to report as missing.
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || alarms?.canScheduleExactAlarms() == true,
            serviceStartedMs = DiagState.serviceStartedMs,
            serviceStoppedMs = DiagState.serviceStoppedMs,
            healthConnect = healthConnect,
            overlayWanted = Settings.overlayEnabled(context),
            overlayGranted = LockScreenOverlay.canDraw(context),
            nfcPresent = nfc != null,
            nfcEnabled = nfc?.isEnabled == true,
            // THE TWO SWITCHES, NOT THE EDITION. Both alerts ship on in both
            // editions; what the store edition loses is the predicted crossing
            // inside the low and high sides, not the sides themselves.
            alertsEnabled = Settings.hypoAlertEnabled(context) ||
                Settings.hyperAlertEnabled(context),
            freshestReadingMs = readings.second,
        ),
    )
}

/**
 * Open the screen that fixes a row, or start the collector. False means this
 * phone has no such screen — which the row then says out loud rather than
 * leaving a tap that appears to do nothing.
 */
private fun launchFix(context: Context, fix: DataSourceFix): Boolean {
    if (fix == DataSourceFix.START_COLLECTOR) {
        CollectorService.start(context)
        return true
    }
    val intent = when (fix) {
        DataSourceFix.NONE, DataSourceFix.START_COLLECTOR -> null
        DataSourceFix.OPEN_XDRIP ->
            context.packageManager.getLaunchIntentForPackage(XdripApp.PACKAGE)
        // The LIST screen, not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:
        // that dialog requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which
        // Play treats as a restricted permission and this app does not declare.
        DataSourceFix.BATTERY_SETTINGS ->
            Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        DataSourceFix.NOTIFICATION_SETTINGS ->
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        DataSourceFix.EXACT_ALARM_SETTINGS ->
            if (Build.VERSION.SDK_INT >= 31) {
                Intent(
                    AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                null
            }
        DataSourceFix.HEALTH_CONNECT_SETTINGS ->
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        DataSourceFix.OVERLAY_PERMISSION ->
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        DataSourceFix.NFC_SETTINGS -> Intent(AndroidSettings.ACTION_NFC_SETTINGS)
    } ?: return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        // No activity answers it on this phone (a vendor ROM without the
        // screen, or Health Connect gone).
        false
    }
}
