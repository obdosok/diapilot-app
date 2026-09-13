package io.github.obdosok.diapilot.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diapilot.core.collector.ACTION_BG
import com.diapilot.core.collector.Reading
import io.github.obdosok.diapilot.AppIdentity
import io.github.obdosok.diapilot.Edition
import io.github.obdosok.diapilot.MainActivity
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.MeterCalCache
import io.github.obdosok.diapilot.data.MinuteCalCache
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.diag.DiagLog
import io.github.obdosok.diapilot.diag.Redact
import io.github.obdosok.diapilot.i18n.localized
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service keeping a dynamically-registered receiver alive so the
 * ordinary implicit xDrip broadcast reaches us in the background — without
 * touching xDrip's Identify receiver setting (other consumers on this phone,
 * OOPAlgorithm2 and WatchDrip, depend on the broadcast staying as-is).
 *
 * The mandatory foreground notification earns its place by showing the
 * current BG + trend, xDrip-style.
 */
class CollectorService : Service() {

    private var receiver: XdripBgReceiver? = null
    private var oop2Receiver: android.content.BroadcastReceiver? = null
    /** The "dropped, OOP2 absent" line has been written this process. */
    @Volatile private var oop2AbsentLogged = false
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var watchServer: WatchServer? = null
    private var libreBle: LibreBleClient? = null
    private var bleWakeupReceiver: android.content.BroadcastReceiver? = null
    /**
     * OOP2 is a minute-by-minute broadcast.  Its receiver performs SQLite,
     * forecasting, alert and widget work, so registering it without a Handler
     * used to run that chain on the application's main thread every minute.
     * Keep the ordinary xDrip receiver unchanged (it is deliberately tiny),
     * but give OOP2 a service-owned serial worker and shut it down with the
     * service.
     */
    private var oop2Thread: HandlerThread? = null
    private var oop2Handler: Handler? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            NOTIF_ID,
            notification(localized().getString(R.string.collector_service_starting)),
        )
        receiver = XdripBgReceiver(onReading = ::onReading).also {
            ContextCompat.registerReceiver(
                this, it, IntentFilter(ACTION_BG), ContextCompat.RECEIVER_EXPORTED,
            )
        }
        DiagLog.i(TAG, "Collector service started; listening for $ACTION_BG")
        DiagState.serviceStartedMs = System.currentTimeMillis()

        // Lock-screen BG chip: show while the screen is on AND locked, hide
        // once unlocked or off. TYPE_APPLICATION_OVERLAY over the keyguard.
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON ->
                        if (km?.isKeyguardLocked == true) LockScreenOverlay.show(context)
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF ->
                        LockScreenOverlay.hide(context)
                }
            }
        }.also {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, it, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        // WatchDrip-compatible endpoint for Zepp OS watch faces (loopback
        // only). If WatchDrip — or another installed copy of DiaPilot — holds
        // the port, the server just logs and stays off; stop the other one and
        // restart to take over. Opt-in (Settings -> Watch): off by default, so
        // a second copy never competes for the port. DiagState.watchServerUp is
        // set by WatchServer itself once a socket is actually bound.
        if (Settings.watchServerEnabled(applicationContext)) {
            watchServer = WatchServer(applicationContext).also { it.start() }
        }

        // OOP2 emits a decoded sensor packet every ~minute (vs xDrip's 5-min
        // BgEstimate): string extra "json" with per-minute TrendBg in mg/dL.
        // Raw algorithm scale — stored in the separate minute stream.
        oop2Thread = HandlerThread("diapilot-oop2").also { it.start() }
        oop2Handler = Handler(requireNotNull(oop2Thread).looper)
        oop2Receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Regression tripwire for B3-1.  This must stay true: the
                // body below may touch SQLite, forecast, alerts and widgets.
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) check(Looper.myLooper()!=Looper.getMainLooper()) {
                    "OOP2 minute pipeline must not run on the main thread"
                }
                // NO OOP2, NO MINUTE STREAM. The receiver is exported because
                // the sender is another app, and a package check is all it
                // can do — but it can do that: with OOPAlgorithm2 absent,
                // every intent on these actions comes from something else,
                // and the newest minute value anchors the forecast whenever
                // it is fresher than the main reading (`forecastAnchor`).
                // Once per process on `Log`, not `DiagLog`: this fires per
                // forged minute, and the ring must keep its room.
                if (!Oop2App.installed(context)) {
                    if (!oop2AbsentLogged) {
                        oop2AbsentLogged = true
                        DiagLog.w(OOP2_TAG, "OOP2 broadcast received while OOPAlgorithm2 is not installed — dropped")
                    }
                    return
                }
                val json = intent.getStringExtra("json") ?: return
                try {
                    val obj = org.json.JSONObject(json)
                    // Stage-4 pair log: the decrypted buffer joins its
                    // encrypted twin (matched by capture timestamp).
                    if (Settings.ownBleEnabled(this@CollectorService)) {
                        obj.optString("DecodedBuffer").takeIf { it.isNotEmpty() }?.let { dec ->
                            Libre2PairLog.logDecoded(
                                this@CollectorService,
                                obj.optLong("com.eveningoutpost.dexdrip.Extras.TIMESTAMP", 0),
                                dec,
                            )
                        }
                    }
                    val fields = obj.keys().asSequence().associateWith { k ->
                        when (val v = obj.get(k)) {
                            is org.json.JSONArray -> (0 until v.length()).map { v.get(it) }
                            else -> v
                        }
                    }
                    // SENSOR-DIRECT: the decoded minute stream is OOP2's answer
                    // about the sensor, and consuming it is what
                    // [Edition.sensorDirect] names. The store edition parses
                    // nothing out of the payload and writes no minute reading.
                    //
                    // WHY THE RECEIVER IS STILL REGISTERED IN BOTH EDITIONS.
                    // The tail of this block used to be the app's ONLY alert
                    // driver, which made OOPAlgorithm2 a silent prerequisite
                    // for every alarm (docs/audit.md, P7). It is not any more:
                    // [AlertTick] is the one entry point and every source
                    // calls it. What is left here is one more caller of it,
                    // and the minute cadence is simply the fastest of them.
                    val readings = if (Edition.sensorDirect) {
                        com.diapilot.core.collector.parseOop2Trend(fields, System.currentTimeMillis())
                    } else emptyList()
                    if (readings.isNotEmpty() || !Edition.sensorDirect) {
                        val store = Stores.get(this@CollectorService)
                        readings.forEach(store::upsertMinuteReading)
                        if (readings.isNotEmpty()) {
                            // Count, not values (docs/audit.md, S10): how many
                            // points the stream delivered is the diagnosis; the
                            // newest one was only ever a sanity check by eye.
                            //
                            // ON `Log`, NOT `DiagLog`: one line per decoded
                            // minute is ~1440 a day, nearly twice the ring's
                            // whole cap, so the diagnostics export would carry
                            // this line and nothing else.
                            Log.d(OOP2_TAG, "minute stream: ${readings.size} pts")
                        }

                        // Own-BLE mode: DiaPilot is the primary source now —
                        // promote the newest minute value onto the 5-minute
                        // main grid via the (frozen) minute→main calibration.
                        if (Settings.ownBleEnabled(this@CollectorService)) {
                            val nowP = System.currentTimeMillis()
                            val lastMain = store.lastSensorReading()
                            val cal = MinuteCalCache.get(store, this@CollectorService)
                            val newest = readings.maxByOrNull { it.tsMs }
                            if (newest != null && cal != null &&
                                (lastMain == null || newest.tsMs - lastMain.tsMs >= 270_000)
                            ) {
                                val mmol = cal.apply(newest.mmol)
                                store.upsertReading(
                                    com.diapilot.core.collector.Reading(
                                        newest.tsMs, mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F,
                                        mmol, trend = null, source = "libre_ble",
                                    ),
                                )
                                DiagLog.i(OOP2_TAG, "own-BLE main reading promoted: ${Redact.glucose(mgdl = false)}")
                            }
                            // The status notification used to follow xDrip's
                            // broadcast — in own-BLE mode our stream drives it.
                            if (newest != null && cal != null) {
                                onReading(
                                    com.diapilot.core.collector.Reading(
                                        newest.tsMs,
                                        cal.apply(newest.mmol) * com.diapilot.core.analysis.MGDL_PER_MMOL_F,
                                        cal.apply(newest.mmol), trend = null, source = "libre_ble",
                                    ),
                                )
                            }
                        }

                        // The alert set — low, high, rapid fall, stall — plus
                        // the widget and the companion push, on the fastest
                        // cadence the phone has. Every other source calls the
                        // same entry point after storing its reading, which is
                        // what stopped this branch from being the only one
                        // (docs/audit.md, P7). The tick runs on its own serial
                        // thread, so this receiver returns at once.
                        AlertTick.fire(this@CollectorService, AlertTick.Source.MINUTE_STREAM)
                        // Release any watch long-poll waiting on fresh data.
                        DataPulse.pulse()

                        // Learning advances on the heartbeat, not on attention.
                        //
                        // Closed-episode receipts — and therefore the measured
                        // insulin curve, the ISF evidence and the deviation
                        // series — used to be derived from ONE call site in
                        // MainState, so they only progressed while the screen
                        // was open. Last, after the alert path, and it returns
                        // immediately: the rebuild runs on the runtime's own
                        // executor and is throttled there.
                    }
                } catch (e: Exception) {
                    DiagLog.w(OOP2_TAG, "Unparsed OOP2 payload: ${e.message}")
                }
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(ACTION_OOP2_BLE)
                addAction(ACTION_OOP2_FARM)
            }
            // The scheduler is intentional: all expensive OOP2 work above is
            // serialized off-main, including predictive-alert preparation.
            registerReceiver(it, filter, null, requireNotNull(oop2Handler), Context.RECEIVER_EXPORTED)
        }

        // Experimental own BLE link to the sensor (opt-in in Settings, and
        // sensor-direct — `Settings.ownBleEnabled` is false for the whole store
        // edition, which also has no BLUETOOTH_CONNECT to connect with).
        if (Settings.ownBleEnabled(this)) {
            libreBle = LibreBleClient(this).also { it.start() }
            startBleWakeup()
        }
    }

    /**
     * Doze-proof BLE stall recovery. The client's own watchdog rides a Handler,
     * which never fires in deep sleep (uptimeMillis stops) — measured effect:
     * night stalls stood ~37 min waiting for a Doze maintenance window, while
     * daytime ones recovered in 4-14 min. An RTC_WAKEUP allow-while-idle alarm
     * fires regardless; the client takes a wakelock and reconnects at once.
     */
    private fun startBleWakeup() {
        bleWakeupReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                libreBle?.wakeupCheck()
                scheduleBleWakeup()   // one-shot alarms: re-arm every fire
            }
        }
        ContextCompat.registerReceiver(
            this, bleWakeupReceiver, IntentFilter(ACTION_BLE_WAKEUP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scheduleBleWakeup()
    }

    private fun bleWakeupIntent(): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_BLE_WAKEUP).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleBleWakeup() {
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + BLE_WAKEUP_MS
        // Exact keeps night recovery at ~2 min; without the permission Doze
        // throttles allow-while-idle alarms to ~1 per 9 min — still far better
        // than waiting for a maintenance window.
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, bleWakeupIntent())
            } else {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, bleWakeupIntent())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Stamped BEFORE the teardown, so a screen that reads it while this
        // runs already sees the service as gone. The service shares the UI's
        // process, so without this stamp `serviceStartedMs` alone would keep
        // reporting a collector that has stopped listening.
        DiagState.serviceStoppedMs = System.currentTimeMillis()
        receiver?.let(::unregisterReceiver)
        receiver = null
        oop2Receiver?.let(::unregisterReceiver)
        oop2Receiver = null
        oop2Thread?.quitSafely()
        oop2Thread = null
        oop2Handler = null
        screenReceiver?.let(::unregisterReceiver)
        screenReceiver = null
        bleWakeupReceiver?.let(::unregisterReceiver)
        bleWakeupReceiver = null
        runCatching {
            getSystemService(android.app.AlarmManager::class.java)?.cancel(bleWakeupIntent())
        }
        LockScreenOverlay.hide(applicationContext)
        watchServer?.stop()
        watchServer = null
        libreBle?.stop()
        libreBle = null
        super.onDestroy()
    }

    private fun onReading(r: Reading) {
        if (r.source != "libre_ble") DiagState.lastXdripBroadcastMs = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val mgdl = Units.isMgdl(this)
        // OUR arrow + delta from the FROZEN grid (same as the header), and OUR
        // meter-calibrated value — this ongoing notification is the on-device
        // BG glance, meant to stand in for xDrip's on the lock screen.
        val now = System.currentTimeMillis()
        val store = Stores.get(this)
        val meterCal = try { MeterCalCache.get(store, this) } catch (_: Exception) { null }
        val shownMmol = meterCal?.correctedAt(r.tsMs, r.mmol) ?: r.mmol
        (store as? SqliteCollectorStore)
            ?.rememberPresentedGlucose(r.tsMs, shownMmol, r.source, now)
        val trend = try {
            val gridPts = store.sensorReadings(now - 16L * 60_000, now).map {
                com.diapilot.core.collector.GlucosePoint(it.tsMs, meterCal?.correctedAt(it.tsMs, it.mmol) ?: it.mmol)
            }
            com.diapilot.core.twin.gridTrendReadout(gridPts, now)
        } catch (_: Exception) { null }
        val arrow = com.diapilot.core.trendGlyph(com.diapilot.core.trendName(trend?.delta5Mmol))
        val deltaStr = trend?.delta5Mmol?.let {
            " · " + com.diapilot.core.analysis.fmtBgDelta(it, mgdl)
        } ?: ""
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification(
                "${com.diapilot.core.analysis.fmtBg(shownMmol, mgdl)} $arrow$deltaStr · ${fmt.format(Date(r.tsMs))}",
            ),
        )
        // Keep the lock-screen chip current (visible only while locked).
        LockScreenOverlay.update(this, "${com.diapilot.core.analysis.fmtBg(shownMmol, mgdl)} $arrow$deltaStr")
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Full value on the lock screen — the point is to replace xDrip's
            // glance there, not hide behind "sensitive content".
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // DEFAULT (not LOW): MIUI hides LOW ongoing notifications from the
            // lock screen. Silent + onlyAlertOnce keeps it from buzzing on the
            // per-minute update — it just needs to be VISIBLE there.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Retire the old LOW channel so its locked importance doesn't win.
        // Silence is correct here: the channel is absent on every install
        // that never had the LOW one, and "nothing to delete" is not an event.
        try { nm.deleteNotificationChannel("collector") } catch (_: Exception) {}
        val text = localized()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, text.getString(R.string.collector_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = text.getString(R.string.collector_service_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)          // silent — updates every minute
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "CollectorService"
        private const val OOP2_TAG = "Oop2Recon"
        // v2: recreated at DEFAULT importance so MIUI shows it on the lock
        // screen (the old LOW "collector" channel was hidden there).
        private const val CHANNEL_ID = "collector_v2"
        private const val NOTIF_ID = 1001
        const val ACTION_OOP2_BLE = "com.eveningoutpost.dexdrip.OOP2_DECODE_BLE_RESULT"
        const val ACTION_OOP2_FARM = "com.eveningoutpost.dexdrip.OOP2_DECODE_FARM_RESULT"
        private const val ACTION_BLE_WAKEUP = AppIdentity.ACTION_BLE_WAKEUP
        /** Doze-proof BLE stall check cadence. Cheap: a no-op while the stream
         *  is healthy, so a tight interval costs nothing but bounds a night
         *  stall to ~this instead of a Doze maintenance window (~37 min). */
        private const val BLE_WAKEUP_MS = 2L * 60_000

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, CollectorService::class.java),
                )
            } catch (e: Exception) {
                DiagLog.w(TAG, "Could not start collector service: ${e.message}")
            }
        }
    }
}
