package io.github.obdosok.diapilot


import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.relabelMeal
import io.github.obdosok.diapilot.collect.AlertTick
import io.github.obdosok.diapilot.collect.CollectorService
import io.github.obdosok.diapilot.collect.MealNotifier
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import io.github.obdosok.diapilot.data.AskClaude
import io.github.obdosok.diapilot.data.BackupRestore
import io.github.obdosok.diapilot.data.ForecastLedger
import io.github.obdosok.diapilot.data.Forecaster
import io.github.obdosok.diapilot.data.HYBRID_V11_SHADOW_ALGO_VERSION
import io.github.obdosok.diapilot.data.HealthConnectSync
import io.github.obdosok.diapilot.data.Libre2State
import io.github.obdosok.diapilot.data.MeterCalCache
import io.github.obdosok.diapilot.data.MinuteCalCache
import io.github.obdosok.diapilot.data.PhysioRuntime
import io.github.obdosok.diapilot.data.PhysioTuning
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.TwinCache
import io.github.obdosok.diapilot.i18n.AppLocaleFormats
import io.github.obdosok.diapilot.i18n.FoodText
import io.github.obdosok.diapilot.i18n.LibreText
import io.github.obdosok.diapilot.nfc.LibreNfcScanner
import io.github.obdosok.diapilot.nfc.LibreOop2Bridge
import io.github.obdosok.diapilot.nfc.PenDoseSaver
import io.github.obdosok.diapilot.nfc.PenNfcScanner
import io.github.obdosok.diapilot.ui.AnalysisScreen
import io.github.obdosok.diapilot.ui.AnnotationComposer
import io.github.obdosok.diapilot.ui.AskScreen
import io.github.obdosok.diapilot.ui.DataSourcesScreen
import io.github.obdosok.diapilot.ui.ForecastAtPointStrip
import io.github.obdosok.diapilot.ui.GlucoseChart
import io.github.obdosok.diapilot.ui.SettingsScreen
import io.github.obdosok.diapilot.ui.photosDir
import io.github.obdosok.diapilot.ui.theme.DiaPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// AppCompatActivity (not ComponentActivity) only for the per-app language:
// AppCompatDelegate.setApplicationLocales applies to AppCompat activities on
// API < 33. Compose still draws everything.
class MainActivity : AppCompatActivity() {

    /** Set by the UI so a pen scan can refresh the screens immediately. */
    var onPenScanned: (() -> Unit)? = null

    /**
     * The composition root of this activity: one graph, published to the
     * screens through [LocalAppGraph] in [onCreate]. The activity's own
     * background threads read the store from it too, so this file has a single
     * answer to where a screen gets its store.
     */
    private val graph = AppGraph(this)

    private val penScanner = PenNfcScanner()
    private var scanning = false

    private val notifPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    val hcPermissions =
        registerForActivityResult(
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(HealthConnectSync.PERMISSIONS)) {
                TreatmentsPollWorker.pollNow(this)
            }
        }

    override fun onResume() {
        super.onResume()
        // Opening the app acknowledges a pending gentle hypo alert — it won't
        // escalate to the full alarm while the user is already looking.
        Settings.markAppOpened(this)
        // NFC reader mode while the app is visible. Two tag families:
        //  - NovoPen (IsoDep / NFC-A): dose log import — both editions
        //  - Libre 2 (NfcV / ISO-15693): sensor FRAM read — sensor-direct
        //
        // The EDITION decides which families the reader even asks for. Without
        // FLAG_READER_NFC_V the sensor's tag is never dispatched to this app at
        // all, so holding a sensor to a store-edition phone does exactly
        // nothing visible — which is the honest outcome. Refusing it with the
        // "turn it on in Settings" toast would point at a switch that edition
        // does not have.
        val readerFlags = android.nfc.NfcAdapter.FLAG_READER_NFC_A or
            android.nfc.NfcAdapter.FLAG_READER_NFC_B or
            android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            if (Edition.sensorDirect) android.nfc.NfcAdapter.FLAG_READER_NFC_V else 0
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag ->
                if (tag.techList.contains("android.nfc.tech.NfcV")) {
                    // Opt-in: a Libre scan talks to the sensor and to OOP2,
                    // whose reply every listening app receives.
                    if (Settings.libreNfcEnabled(this)) handleLibreTag(tag)
                    else toast(getString(R.string.main_activity_libre_nfc_off))
                } else handlePenTag(tag)
            },
            readerFlags,
            null,
        )
    }

    override fun onPause() {
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    private val libreScanner = LibreNfcScanner()

    private fun handleLibreTag(tag: android.nfc.Tag) {
        if (scanning) return
        scanning = true
        Thread {
            try {
                val now = System.currentTimeMillis()
                val raw = libreScanner.scan(tag)
                if (raw == null) {
                    toast(getString(R.string.main_activity_libre_scan_failed))
                    return@Thread
                }
                val serial = com.diapilot.core.libre.decodeLibreSerial(raw.uid)
                val decoded = LibreOop2Bridge.decode(this, raw, serial, now)
                if (decoded == null) {
                    toast(getString(R.string.main_activity_libre_oop2_no_response, serial))
                    return@Thread
                }
                if (!com.diapilot.core.libre.verifyFramCrc(decoded.fram)) {
                    toast(getString(R.string.main_activity_libre_data_corrupt, serial))
                    return@Thread
                }
                val parse = com.diapilot.core.libre.parseFram(decoded.fram, decoded.captureMs)
                if (parse == null) {
                    toast(getString(R.string.main_activity_libre_parse_failed, serial))
                    return@Thread
                }
                val trend = com.diapilot.core.libre.attachBg(parse.trend, decoded.trendBg)
                val history = com.diapilot.core.libre.attachBg(parse.history, decoded.historyBg)

                // The raw OOP2 points land in minute_readings (display stream,
                // 12h window) — freshness for the header and the grey trace.
                val store = graph.store
                val nfcPts = (history + trend).filter { it.bgMgdl != null && it.bgMgdl!! > 20 }
                var saved = 0
                nfcPts.forEach { s ->
                    store.upsertMinuteReading(
                        com.diapilot.core.collector.Reading(
                            s.tsMs, s.bgMgdl!!,
                            s.bgMgdl!! / com.diapilot.core.analysis.MGDL_PER_MMOL_F,
                            trend = null, source = "libre_nfc",
                        ),
                    )
                    saved++
                }
                // Backfill the MAIN line + training history. The main graph and
                // the twin read glucose_readings (xDrip-calibrated scale), NOT
                // minute_readings — so after a long BLE/xDrip gap the sensor's
                // own stored history (up to ~8h) must be PROMOTED there through
                // the frozen minute→main calibration, or the hole never fills.
                // Never overwrites an existing point (real 5-min data wins).
                var mainFilled = 0
                var mainSkipped = 0
                val minCal = MinuteCalCache.get(store, this)
                if (minCal != null) {
                    nfcPts.forEach { s ->
                        val mmol = minCal.apply(s.bgMgdl!! / com.diapilot.core.analysis.MGDL_PER_MMOL_F)
                        val ok = store.backfillMainReading(
                            com.diapilot.core.collector.Reading(
                                s.tsMs, mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F, mmol,
                                trend = null, source = "libre_nfc",
                            ),
                            toleranceMs = 150_000L,   // half the 5-min grid
                        )
                        if (ok) mainFilled++ else mainSkipped++
                    }
                }
                android.util.Log.i(
                    "LibreNfc",
                    "backfill: hist=${history.size} trend=${trend.size} " +
                        "bgOk=${nfcPts.size} minute=$saved " +
                        "main+=$mainFilled main~=$mainSkipped " +
                        "cal=${if (minCal != null) "yes" else "no(minute-only)"}",
                )
                // New history landed in the main trace — rebuild the twin
                // instead of riding the cached kernel/corridor for up to 6h.
                if (mainFilled > 0) TwinCache.invalidate()
                // A scan can close a stream gap that ended a minute ago, so
                // the alert set is judged against what just landed — the same
                // entry point every other source calls (docs/audit.md, P7).
                AlertTick.fire(this, AlertTick.Source.SENSOR_SCAN)

                // Sensor registry: a serial change starts a new calibration
                // era (meter checks from the old sensor stop applying).
                val prevSerial = Settings.libreSensorSerial(this)
                val isNew = prevSerial != null && prevSerial != serial
                Settings.setLibreSensor(this, serial, parse.sensorStartMs)
                // The cached lens belongs to the previous physical sensor.
                // The old coefficients remain persisted for historical charts;
                // the new sensor starts its own calibration era.
                MeterCalCache.invalidate()
                if (isNew) {
                    store.addAnnotation(
                        com.diapilot.core.collector.Annotation(
                            parse.sensorStartMs, "text", com.diapilot.core.analysis.NoteTag.NEW_SENSOR.key,
                        ),
                    )
                }

                // Own-BLE mode: the same scan re-arms streaming and captures
                // the sensor's MAC. NOTE: this takes the BLE link away from
                // xDrip — reverting = re-scanning the sensor in xDrip.
                var bleNote = ""
                if (Settings.ownBleEnabled(this)) {
                    // The NFC enable command RESETS the sensor's nonce — every
                    // (re)scan starts the ladder at 1, exactly like xDrip's
                    // setLibre2SensorData does.
                    val nextIndex = 1
                    val unlock = LibreOop2Bridge.enableStreaming(
                        this, raw.uid, raw.patchInfo, nextIndex,
                    )
                    bleNote = if (unlock == null) {
                        " · " + getString(R.string.main_activity_ble_no_keys)
                    } else {
                        val mac = libreScanner.enableStreaming(tag, unlock.nfcUnlock)
                        if (mac == null) {
                            " · " + getString(R.string.main_activity_ble_rejected)
                        } else {
                            Libre2State.save(
                                this,
                                Libre2State.State(
                                    uid = raw.uid,
                                    patchInfo = raw.patchInfo,
                                    serial = serial,
                                    mac = mac,
                                    deviceName = unlock.deviceName,
                                    connectionIndex = nextIndex,
                                    unlockArray = unlock.unlockArray,
                                    unlockStartIndex = nextIndex,
                                ),
                            )
                            // Restart the collector so the BLE client picks
                            // up the fresh credentials.
                            stopService(
                                android.content.Intent(
                                    this, CollectorService::class.java,
                                ),
                            )
                            CollectorService.start(this)
                            " · " + getString(R.string.main_activity_ble_streaming, mac)
                        }
                    }
                }

                val cur = trend.lastOrNull { it.bgMgdl != null }?.bgMgdl
                toast(
                    getString(R.string.main_activity_libre_prefix, serial) + " " +
                        (cur?.let { getString(R.string.main_activity_libre_mgdl, it) + " · " } ?: "") +
                        getString(
                            R.string.main_activity_libre_status_day,
                            LibreText.status(this@MainActivity, parse.status),
                            parse.sensorTimeMin / 1440.0,
                        ) +
                        " · " + getString(R.string.main_activity_libre_stream, saved) +
                        (if (minCal != null) {
                            " · " + if (mainSkipped > 0) {
                                getString(R.string.main_activity_libre_backfill_with_skipped, mainFilled, mainSkipped)
                            } else {
                                getString(R.string.main_activity_libre_backfill, mainFilled)
                            }
                        } else " · " + getString(R.string.main_activity_libre_no_calibration)) +
                        (if (isNew) " · " + getString(R.string.main_activity_libre_new_sensor_flag) else "") +
                        bleNote,
                )
                runOnUiThread { onPenScanned?.invoke() }
            } finally {
                scanning = false
            }
        }.start()
    }

    private fun toast(text: String) = runOnUiThread {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun handlePenTag(tag: android.nfc.Tag) {
        if (scanning) return
        scanning = true
        Thread {
            try {
                val result = penScanner.scan(tag)
                // Partial catches count: doses are valid on their own and the
                // uuid ledger dedups across attempts, so several shaky taps
                // converge to the full log.
                val text = if (result == null || (result.doses.isEmpty() && !result.completed)) {
                    getString(R.string.main_activity_pen_scan_failed)
                } else {
                    val saved = PenDoseSaver.save(
                        graph.store, result,
                    )
                    val suffix = if (!result.completed) {
                        " · " + getString(R.string.main_activity_pen_scan_incomplete)
                    } else ""
                    val serial = saved.serial ?: ""
                    when {
                        saved.newDoses > 0 -> {
                            val doses = resources.getQuantityString(
                                R.plurals.main_activity_pen_new_doses, saved.newDoses, saved.newDoses,
                            )
                            val priming = if (saved.priming > 0) {
                                ", " + resources.getQuantityString(
                                    R.plurals.main_activity_pen_primes, saved.priming, saved.priming,
                                )
                            } else ""
                            getString(R.string.main_activity_pen_result, serial, doses + priming) + suffix
                        }
                        else -> getString(R.string.main_activity_pen_no_new_doses, serial) + suffix
                    }
                }
                runOnUiThread {
                    android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
                    onPenScanned?.invoke()
                }
            } finally {
                scanning = false
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A language switch recreates this activity; number formatting in
        // :core follows the language from here on.
        AppLocaleFormats.sync(this)
        // WHAT THIS PHONE IS ACTUALLY DRAWING WITH, printed once per launch.
        //
        // The settings screen can import a triangle set that overrides the
        // shipped defaults, and nothing outside the phone could tell whether it
        // had. That gap once let a measured change to the shipped triangles be
        // silently inert on the one device that matters, and neither the twin
        // snapshot nor the database records the pref. Discipline #7 in its
        // most direct form — the instrument has to be able to say which model
        // is running.
        // TWO LINES, BECAUSE THEY ANSWER TWO DIFFERENT QUESTIONS.
        //
        // This used to be one line labelled "applied" that printed
        // `PhysioTuning.read(...)` — i.e. what is SET in preferences, not what
        // reached the model. For insulin those differ: the tuning's landmarks
        // enter `InsulinParameterResolverV1` as the PRIOR, the lowest tier, so a
        // ready measured curve overrides them and the log would still have
        // claimed they were applied. Found by a wiring audit; a log
        // that cannot tell configured from applied is discipline #7 aimed at the
        // one instrument that is supposed to settle it.
        runCatching {
            android.util.Log.i(
                "PhysioTuning",
                "configured: ${PhysioTuning.identity(
                    PhysioTuning.read(this),
                )} · triangles ${PhysioTuning
                    .effectiveTriangles(this)
                    .joinToString(" ") { "%.2f".format(java.util.Locale.ROOT, it) }}" +
                    " · generation ${com.diapilot.core.hybrid.CarbTrianglesV1.GENERATION}",
            )
        }
        // THE LAST BACKGROUND MODEL BUILD ON THE LAUNCH PATH, and it exists to
        // report the FORECAST model: which ISF and which insulin landmarks
        // reached the line, and whether the ledger's last screen run matches
        // the arm being drawn. Both questions are meaningless in an edition
        // that draws no line, and the build behind them is not free — so the
        // store edition does not start this thread.
        if (Edition.prospective) {
            Thread {
                runCatching {
                    val store = graph.store
                    val artifact = PhysioRuntime.artifact(
                        store, System.currentTimeMillis(),
                    ) ?: return@runCatching
                    val ins = artifact.personModelAt(12.0, emptySet()).insulin
                    android.util.Log.i(
                        "PhysioTuning",
                        ("APPLIED to model: onset %.0f · peak %.0f · tail %.0f · ISF %.3f")
                            .format(
                                java.util.Locale.ROOT,
                                ins.onsetMin, ins.peakMin, ins.tailDurationMin, ins.isf,
                            ) +
                            // WHERE THAT ISF CAME FROM, split three ways. The applied
                            // number is base × circadian × promoted contracts, and
                            // with only the product printed a session cannot tell a
                            // promotion that stopped applying from a base that moved.
                            " · base %.3f · daily at 12h %.3f · pinned by hand %b · global %.3f · learned alternative %s · modifiers %d".format(
                                java.util.Locale.ROOT,
                                artifact.baseMechanics.insulin.isf, artifact.effectiveIsf(12.0),
                                artifact.isfPinnedByHand, artifact.globalIsf.median,
                                artifact.learnedIsfAlternative?.median
                                    ?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "none",
                                artifact.promotedModifiers.size,
                            ) + " · artifact ${artifact.artifactId.take(80)}",
                    )
                    // WHAT THE LEDGER LAST STORED FOR THE SCREEN, and it is here
                    // because a test could not answer it. There was a window where
                    // the app recorded the BASE TWIN under `main` while drawing the
                    // physio line, and nothing on the device said so:
                    // rows kept appearing, at the right cadence, with plausible
                    // numbers, and only their CONTENT was the wrong arm. One line
                    // at startup makes the next such divergence visible without a
                    // database pull, which the release build does not allow.
                    runCatching {
                        (store as SqliteCollectorStore).readableDatabase.rawQuery(
                            "SELECT algo_version, anchor_ts_ms FROM forecast_runs " +
                                "WHERE consumer='main' ORDER BY created_at_ms DESC LIMIT 1",
                            null,
                        ).use { c ->
                            if (!c.moveToFirst()) {
                                android.util.Log.w("PhysioTuning", "ledger: no runs for the screen")
                            } else {
                                val tag = c.getString(0)
                                val ageMin = (System.currentTimeMillis() - c.getLong(1)) / 60_000
                                val live = HYBRID_V11_SHADOW_ALGO_VERSION
                                android.util.Log.i(
                                    "PhysioTuning",
                                    "ledger: last screen run $tag, anchor $ageMin min ago" +
                                        if (tag == live) " — matches the shown model"
                                        else " — DOES NOT MATCH the shown model ($live)",
                                )
                            }
                        }
                    }
                }.onFailure {
                    android.util.Log.w("PhysioTuning", "could not read the applied model: ${it.message}")
                }
            }.start()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !MealNotifier.canNotify(this)) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Load user concept-alias overrides into the fingerprint model before
        // history renders, so hand-assigned mappings take effect immediately.
        Thread {
            (graph.store as? SqliteCollectorStore)?.let {
                com.diapilot.core.analysis.setUserConceptAliases(it.conceptAliases())
                // …and the user's edits to the dictionary itself (v24). Both must
                // land BEFORE anything prices a portion or builds a fingerprint,
                // or the first render uses the built-ins and then silently
                // disagrees with the second.
                com.diapilot.core.analysis.setUserConceptOverrides(it.conceptOverrides())
            }
        }.start()
        // Daily silent DB copy into Downloads — a dead phone must not mean
        // a dead model. No-op when today's copy already exists.
        Thread { BackupRestore.autoBackupIfDue(this) }.start()
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                DiaPilotTheme {
                    MainApp(onConnectHc = {
                        hcPermissions.launch(HealthConnectSync.PERMISSIONS)
                    })
                }
            }
        }
        // WorkManager's first initialization and starting the collector can
        // touch disk/package services.  Doing both before setContent cost the
        // cold launch ~100 frames on the test phone.  None of this is needed to
        // draw the persisted chart, so start it immediately after the first
        // composition, off the UI thread.
        Thread({
            TreatmentsPollWorker.schedule(this)
            // Activity recreation / quick reopen must not trigger another full
            // xDrip + treatments + Health Connect pass.
            TreatmentsPollWorker.pollIfStale(this, maxAgeMin = 5)
            CollectorService.start(this)
        }, "startup-services").start()
    }
}

private enum class Tab {
    TODAY,
    LABEL,
    ANALYSIS,
    ASK,
    SETTINGS,
}

@Composable
private fun tabLabel(tab: Tab): String = when (tab) {
    Tab.TODAY -> stringResource(R.string.main_activity_tab_today)
    Tab.LABEL -> stringResource(R.string.main_activity_tab_history)
    Tab.ANALYSIS -> stringResource(R.string.main_activity_tab_analysis)
    Tab.ASK -> stringResource(R.string.main_activity_tab_chat)
    Tab.SETTINGS -> stringResource(R.string.main_activity_tab_more)
}

@Composable
private fun AppNavIcon(tab: Tab, selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = tabLabel(tab)
    Canvas(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = label },
    ) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val stroke = 1.9f * sx
        when (tab) {
            Tab.TODAY -> {
                val points = listOf(p(2f, 17f), p(7f, 12f), p(11f, 15f), p(17f, 6f), p(22f, 9f))
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(color, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
            Tab.LABEL -> listOf(6f, 12f, 18f).forEach { y ->
                drawCircle(color, 1.4f * sx, p(4f, y))
                drawLine(color, p(8f, y), p(22f, y), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            Tab.ANALYSIS -> {
                drawLine(color, p(3f, 21f), p(22f, 21f), strokeWidth = stroke)
                drawLine(color, p(6f, 19f), p(6f, 13f), strokeWidth = 3.5f * sx, cap = StrokeCap.Round)
                drawLine(color, p(12f, 19f), p(12f, 8f), strokeWidth = 3.5f * sx, cap = StrokeCap.Round)
                drawLine(color, p(18f, 19f), p(18f, 4f), strokeWidth = 3.5f * sx, cap = StrokeCap.Round)
            }
            Tab.ASK -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(2f, 3f),
                    size = Size(20f * sx, 15f * sy),
                    cornerRadius = CornerRadius(5f * sx),
                    style = Stroke(stroke),
                )
                drawLine(color, p(8f, 18f), p(6f, 22f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            Tab.SETTINGS -> listOf(
                Triple(6f, 8f, 16f),
                Triple(6f, 12f, 11f),
                Triple(6f, 16f, 6f),
            ).forEach { (x1, knob, y) ->
                drawLine(color, p(x1, y), p(20f, y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color, 2.2f * sx, p(knob, y))
            }
        }
    }
}

@Composable
private fun AddNavIcon() {
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.main_activity_add_event)
    Canvas(
        Modifier
            .size(26.dp)
            .semantics { contentDescription = label },
    ) {
        val stroke = size.width / 10f
        drawLine(
            color,
            Offset(size.width / 2, size.height * .22f),
            Offset(size.width / 2, size.height * .78f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * .22f, size.height / 2),
            Offset(size.width * .78f, size.height / 2),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun MainApp(onConnectHc: () -> Unit = {}) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    // A LEAF, NOT A TAB. "Data sources" is reached from More and from the
    // Today banner, and it has to come back to whichever of the two the user
    // arrived from — so it is a flag over the tab content rather than a sixth
    // nav slot, which is also the one thing the nav bar has no room for.
    var showDataSources by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf(UiState()) }

    // Refresh while the app is open: broadcasts land every ~5 min, the
    // one-shot poll within seconds of launch.
    LaunchedEffect(Unit) {
        // A LaunchedEffect survives Activity.onStop while its composition is
        // retained.  The old infinite refresh loop therefore kept rebuilding
        // the Twin and History with the screen off.  On this phone that was
        // charged to the collector foreground-service process for hours.
        // Cancel all screen work at STOP and restart it from fresh stamps only
        // when the Activity is visible again.
        (context as androidx.activity.ComponentActivity).lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        // First frame: render from the cached model as-is (null on cold start)
        // so the chart and current sugar appear at once — no synchronous twin
        // build blocking the open. The loop below then does the real build in
        // the background and the forecast fills in a moment later.
        // FIRST: the number and the chart, from two queries. Coming back after
        // a while used to show the state Compose remembered from last time until
        // the whole analytical pass finished — the readings were never late, the
        // DISPLAY was, against a database that already had them.
        val tResume = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("MainStatePerf", "STARTED: block restarted")
        runCatching {
            withContext(Dispatchers.IO) { loadLivePreview(state, graph.store, context) }
        }.onSuccess {
            state = it
            android.util.Log.i(
                "MainStatePerf",
                "preview applied in ${android.os.SystemClock.elapsedRealtime() - tResume} ms " +
                    "· forecast ${it.prediction.size}",
            )
        }
            .onFailure { android.util.Log.w("MainRefresh", "live preview failed", it) }
        runCatching {
            withContext(Dispatchers.IO) {
                loadState(
                    graph.store, context,
                    allowBuild = false, includeHistory = false,
                )
            }
            // KEEP THE HISTORY THAT IS ALREADY ON SCREEN. This assignment used
            // to replace the state wholesale, and `loadState(includeHistory =
            // false)` returns empty history lists — so returning to the app and
            // opening History showed a blank journal for the seconds it took the
            // next pass to refill it. The data was in the previous state all
            // along.
        }.onSuccess {
            state = it.withHistoryFrom(state).stabiliseHistoryAgainst(state)
            android.util.Log.i(
                "MainStatePerf",
                "state applied in ${android.os.SystemClock.elapsedRealtime() - tResume} ms " +
                    "· forecast ${it.prediction.size}",
            )
        }
            .onFailure { android.util.Log.e("MainRefresh", "cold state load failed; retrying", it) }
        // Event-driven refresh. A cheap stamp check runs while this activity
        // is visible; the full model is rebuilt only for a new reading.
        // Slow-path data gets a five-minute safety refresh. Data mutations
        // (food, bolus, edits) already reload explicitly in their handlers.
        var lastMainStamp = -1L
        var lastMinuteStamp = -1L
        var lastFullMs = 0L
        var lastLoadedTab: Tab? = null
        // WHAT THE HISTORY TAB ACTUALLY DEPENDS ON.
        //
        // The five-minute safety refresh re-read the same seven days over and
        // over and handed the screen fresh List instances with identical
        // contents, which invalidated every `remember` in `LabelScreen` and
        // rebuilt the whole journal mid-read. A timer is the wrong trigger for
        // a projection over facts that only change when the user logs
        // something, so the loop now watches the facts instead.
        // `historyFactsStamp()` is ONE cheap query of counts and maxima
        // against a full re-read plus a main-thread rebuild of the journal.
        var lastHistoryStamp: String? = null
        // Let the already-loaded chart reach the display before starting the
        // first full history/model pass. This changes perceived cold-start
        // latency without delaying data collection or alarms.
        delay(1_200)
        // The poll settles into 15 s, but the first minute after the screen comes
        // back is when the user is actually looking at it and when the app is
        // furthest behind. Polling fast then slow costs a handful of stamp
        // queries and removes a noticeable startup wait users reported.
        var ticks = 0
        // A REBUILD MUST NOT BE RE-ENTERED FASTER THAN IT COMPLETES.
        //
        // The fast first-minute poll was written for the cheap STAMP check, but
        // what follows a changed stamp is `loadState`, which can take several
        // seconds on device. Polling every 2.5 s meant the next rebuild started
        // before the last had finished — the CPU never rested and the screen
        // stayed behind. A live check showed `loadState allowBuild=false`
        // taking multiple seconds.
        //
        // So the loop waits at least as long as the last rebuild took. Fast
        // while the work is fast, patient while it is slow, and it needs no
        // constant to be kept in step with the code's real cost.
        var lastBuildMs = 0L
        while (true) {
            ticks++
            val activeTab = Tab.entries[tab]
            if (activeTab != Tab.TODAY && activeTab != Tab.LABEL) {
                delay(5_000)
                continue
            }
            val stamps = withContext(Dispatchers.IO) {
                val s = graph.store
                val main = s.lastSensorReading()?.tsMs ?: 0
                main to (s.lastMinuteReading()?.tsMs ?: 0)
            }
            val historyStamp = if (activeTab != Tab.LABEL) null else withContext(Dispatchers.IO) {
                val s = graph.store as? SqliteCollectorStore
                // The window the user is looking at is part of the identity: paging in
                // another week must rebuild even though no fact changed.
                s?.let { "${it.historyFactsStamp()}|${HistoryNav.days}|${HistoryNav.anchorMs ?: 0L}" }
            }
            val historyChanged = historyStamp != null && historyStamp != lastHistoryStamp
            val mainChanged=stamps.first!=lastMainStamp
            val minuteChanged=activeTab==Tab.TODAY&&stamps.second!=lastMinuteStamp
            val tabChanged=activeTab!=lastLoadedTab
            val fullDue=System.currentTimeMillis()-lastFullMs>5*60_000
            if(mainChanged||tabChanged||fullDue){
                // History is rebuilt when its own facts moved, when the user
                // arrives on the tab, or when there is nothing to show —
                // never merely because five minutes passed.
                val includeHistory =
                    activeTab == Tab.LABEL &&
                        (tabChanged || historyChanged || state.historyNotes.isEmpty())
                if(includeHistory&&(tabChanged||state.historyNotes.isEmpty())){
                    // Publish persisted facts first. Detailed Physio receipts
                    // continue below, but an interrupted/slow projection can
                    // no longer leave History as a blank screen.
                    val preview=withContext(Dispatchers.IO){
                        loadHistoryPreview(state,graph.store)
                    }
                    state=preview
                }
                // History is an audit projection over raw persisted facts.  It
                // must never wait for a full Twin rebuild (or disappear when
                // model migration fails).  Model-dependent receipts can fill
                // in on a later Today refresh; food/insulin/notes render now.
                val buildStartedMs = System.currentTimeMillis()
                val loaded=runCatching {
                    withContext(Dispatchers.IO) {
                        loadState(
                            graph.store,context,
                            allowBuild=activeTab==Tab.TODAY,
                            includeHistory=includeHistory,
                        )
                    }
                }.onFailure {
                    android.util.Log.e("MainRefresh", "state load failed for $activeTab; retrying", it)
                }.getOrNull()
                if(loaded!=null){
                    val merged = if (includeHistory) {
                        state.withHistoryFrom(loaded)
                    } else {
                        loaded.withHistoryFrom(state)
                    }
                    // Keep the instances the screen is already holding whenever
                    // the contents match, so `remember` in LabelScreen survives
                    // and the journal is not rebuilt under the reader.
                    state = merged.stabiliseHistoryAgainst(state)
                    lastBuildMs = System.currentTimeMillis() - buildStartedMs
                    lastFullMs = System.currentTimeMillis()
                    lastLoadedTab=activeTab
                    if (includeHistory) lastHistoryStamp = historyStamp
                }
            }else if(minuteChanged){
                // Minute packets only move the live anchor/trend. Reuse the
                // trained model and omit History-only projections instead of
                // rebuilding the whole analytical screen every minute.
                runCatching {
                    withContext(Dispatchers.IO){loadState(graph.store,context,allowBuild=false,includeHistory=false)}
                }.onSuccess { state = it.withHistoryFrom(state).stabiliseHistoryAgainst(state) }
                    .onFailure { android.util.Log.e("MainRefresh", "minute state load failed; retrying", it) }
            }
            lastMainStamp=stamps.first
            lastMinuteStamp=stamps.second
            val base = if (ticks <= 12) 2_500L else 15_000L
            delay(maxOf(base, lastBuildMs))
        }
    }
    }

    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        TreatmentsPollWorker.pollNow(context)
        scope.launch(Dispatchers.IO) {
            // Give the one-shot poll a moment to land before re-reading.
            delay(1_800)
            val store = graph.store
            val immediate = loadState(store, context, allowBuild = false, includeHistory = false)
            val preserved = immediate.withHistoryFrom(state)
            state = if (Tab.entries[tab] == Tab.LABEL) loadHistoryPreview(preserved, store) else preserved
            // Manual refresh is also the explicit "continue learning" action.
            // It may be slow, but the first frame above is already published.
            val rebuilt = loadState(store, context, includeHistory = false)
            state = rebuilt.withHistoryFrom(state)
            refreshing = false
        }
    }

    // Event mutations must redraw the forecast immediately, without waiting
    // for the next CGM stamp. The first pass reuses the already trained model
    // but reads the new food/insulin rows; an optional second pass relearns
    // coefficients for edits of old evidence.
    /** TEMPORARY. The event write itself is the one step on the
     *  add/delete path that nothing times: `reloadAfterEvent` starts AFTER it.
     *  Users report a noticeably longer wait for any new event than the
     *  reload alone measures. */
    fun <T> timedWrite(label: String, body: () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            return body()
        } finally {
            android.util.Log.i(
                "MainStatePerf",
                "write $label: ${android.os.SystemClock.elapsedRealtime() - t0} ms",
            )
        }
    }

    suspend fun reloadAfterEvent(
        store: com.diapilot.core.collector.CollectorStore,
        relearn: Boolean = false,
    ) {
        // Event writes must not synchronously rebuild every historical food
        // receipt. Publish raw History plus the live forecast first.
        //
        // TIMED IN THREE PIECES, because a claim that "adding food takes
        // about 5 seconds" is a claim about ONE of them and there was no way
        // to tell which. The pass, the History preview and the optional
        // relearn are separate costs with separate fixes.
        val t0 = android.os.SystemClock.elapsedRealtime()
        val immediate = loadState(store, context, allowBuild = false, includeHistory = false)
        val tPass = android.os.SystemClock.elapsedRealtime()
        val preserved = immediate.withHistoryFrom(state)
        val visible = if (Tab.entries[tab] == Tab.LABEL) {
            loadHistoryPreview(preserved, store)
        } else preserved
        val tPreview = android.os.SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main.immediate) { state = visible }
        android.util.Log.i(
            "MainStatePerf",
            "reloadAfterEvent: pass ${tPass - t0} ms · history ${tPreview - tPass} ms" +
                " · tab ${Tab.entries[tab]} · relearn=$relearn",
        )
        if (relearn) {
            val tRe0 = android.os.SystemClock.elapsedRealtime()
            TwinCache.invalidate()
            val rebuilt = loadState(store, context, includeHistory = false)
            withContext(Dispatchers.Main.immediate) { state = rebuilt.withHistoryFrom(visible) }
            android.util.Log.i(
                "MainStatePerf",
                "reloadAfterEvent: retrain ${android.os.SystemClock.elapsedRealtime() - tRe0} ms",
            )
        }
    }

    // Pen NFC scans land on the activity; refresh the screens right away.
    androidx.compose.runtime.SideEffect {
        (context as? MainActivity)?.onPenScanned = {
            scope.launch(Dispatchers.IO) {
                reloadAfterEvent(graph.store)
            }
        }
    }

    fun label(meal: MealEvent, name: String) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.setMealLabel(meal.onsetMs, store.getOrCreateLabel(name.trim()))
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    fun relabel(item: LabeledMeal, name: String) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.relabelMeal(item.event.onsetMs, item.labelId, name)
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    fun addFoodWithAnalysis(tsMs: Long, text: String, mediaRef: String?, estCarbs: Double?, analysis: String) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val recordedAtMs = System.currentTimeMillis()
            // ONE history item per meal; the per-component split lives in the
            // cached analysis (composition-marker lines) and feeds the dish dictionary.
            val id = store.addAnnotation(
                com.diapilot.core.collector.Annotation(
                    tsMs, "food", text, mediaRef,
                    estCarbs = estCarbs,
                    // The old free-text marker is legacy context, not
                    // recomputable evidence. Only CarbEvidenceV1 may create a
                    // measured compatibility source for future rows.
                    carbsSource = null,
                    carbsKnownAtMs = recordedAtMs.takeIf { estCarbs != null },
                ),
            )
            timedWrite("food analysis") { store.setAnnotationAnalysis(id, analysis) }
            estCarbs?.takeIf{it>0}?.let{grams->
                val cohort=java.security.MessageDigest.getInstance("SHA-256").digest("food-cohort-v1|${text.trim().lowercase()}".toByteArray()).joinToString(""){"%02x".format(it)}
                store.appendCarbEvidence(id,tsMs,com.diapilot.core.collector.CarbEvidenceInputV1(
                    source=if(mediaRef!=null)com.diapilot.core.collector.CarbEvidenceSourceV1.PHOTO_LLM else com.diapilot.core.collector.CarbEvidenceSourceV1.USER_ESTIMATE,
                    userConfirmed=true,totalCarbsG=grams,
                    amountUncertainty=com.diapilot.core.collector.CarbUncertaintyV1(if(mediaRef!=null)"photo_portion_estimate" else "user_point_estimate"),
                    timingUncertainty=com.diapilot.core.collector.CarbUncertaintyV1("intake_time_point"),evidenceHash=cohort,
                    kineticsV2=analysis.lineSequence().lastOrNull{it.trim().startsWith("KINETICS_V2:",true)}?.trim(),
                ),recordedAtMs,recordedAtMs)
            }
            android.util.Log.i("MainStatePerf", "food write complete")
            reloadAfterEvent(store)
        }
    }


    fun addNote(tsMs: Long, text: String, mediaRef: String?, kind: String, estCarbs: Double? = null) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val recordedAtMs = System.currentTimeMillis()
            val id=store.addAnnotation(
                com.diapilot.core.collector.Annotation(tsMs, kind, text, mediaRef, estCarbs = estCarbs,
                    carbsKnownAtMs = recordedAtMs.takeIf { estCarbs != null }),
            )
            if(kind=="food")estCarbs?.takeIf{it>0}?.let{grams->
                val cohort=java.security.MessageDigest.getInstance("SHA-256").digest("food-cohort-v1|${text.trim().lowercase()}".toByteArray()).joinToString(""){"%02x".format(it)}
                store.appendCarbEvidence(id,tsMs,com.diapilot.core.collector.CarbEvidenceInputV1(
                    source=if(mediaRef!=null)com.diapilot.core.collector.CarbEvidenceSourceV1.PHOTO_LLM else com.diapilot.core.collector.CarbEvidenceSourceV1.USER_ESTIMATE,
                    userConfirmed=true,totalCarbsG=grams,amountUncertainty=com.diapilot.core.collector.CarbUncertaintyV1(if(mediaRef!=null)"photo_portion_estimate" else "user_point_estimate"),
                    timingUncertainty=com.diapilot.core.collector.CarbUncertaintyV1("intake_time_point"),evidenceHash=cohort,
                ),recordedAtMs,recordedAtMs)
            }
            if (kind == "food") reloadAfterEvent(store) else state = loadState(store, context)
        }
    }

    /**
     * "Repeat" — the same dish, logged for NOW.
     *
     * Goes through the same [addFoodWithAnalysis] as a normal add, not
     * through copying the row in the database: a repeat must go through the
     * whole path — CarbEvidence, grams provenance, KINETICS_V2 from the
     * parse — otherwise the repeated meal would look like the original but
     * carry less evidence.
     *
     * The photo is not copied — see `AnnotationEditor.onRepeat`. So the carbs
     * source also becomes text rather than a photo parse: this is honest,
     * since there really is no photo of this meal.
     */
    fun repeatFoodNow(note: com.diapilot.core.collector.Annotation) {
        val analysis = note.analysis
        if (analysis.isNullOrBlank()) {
            addNote(System.currentTimeMillis(), note.content, null, "food", note.estCarbs)
        } else {
            addFoodWithAnalysis(
                System.currentTimeMillis(), note.content, null, note.estCarbs, analysis,
            )
        }
    }

    fun quickDextrose() {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val nowMs = System.currentTimeMillis()
            val existing = store.annotations(nowMs - 30 * 60_000, nowMs)
                .firstOrNull { com.diapilot.core.analysis.isRescueNote(it.content) }
            if (existing != null) {
                val n = parseDextroseCount(existing.content) + 1
                store.updateAnnotation(existing.id, existing.tsMs, com.diapilot.core.analysis.rescueNote(n), existing.mediaRef)
                store.setAnnotationCarbs(
                    existing.id,
                    n * DEXTROSE_TABLET_G,
                    "preset",
                )
            } else {
                store.addAnnotation(
                    com.diapilot.core.collector.Annotation(
                        nowMs, "food", com.diapilot.core.analysis.rescueNote(1), estCarbs = DEXTROSE_TABLET_G,
                        carbsSource = "preset", carbsKnownAtMs = nowMs,
                    ),
                )
            }
            reloadAfterEvent(store)
        }
    }

    fun tagBolus(tsMs: Long, purpose: String?) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.setBolusPurpose(tsMs, purpose)
            reloadAfterEvent(store, relearn = tsMs < System.currentTimeMillis() - 6L * 3_600_000)
        }
    }

    fun deleteNoteById(id: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.deleteAnnotation(id)
            reloadAfterEvent(store, relearn = true)
        }
    }

    fun addBasal(tsMs: Long, units: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            timedWrite("basal") { store.upsertBasal(tsMs, units) }
            reloadAfterEvent(store)
        }
    }

    fun addManualBolus(tsMs: Long, units: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            timedWrite("bolus") {
                store.upsertInsulin(
                    com.diapilot.core.collector.InsulinEvent(tsMs, units, "bolus", source = "manual"),
                )
            }
            reloadAfterEvent(store)
        }
    }

    // Bolus + purpose from ONE command (voice/LLM): a single transaction and
    // a single reload — the old two-coroutine path could tag before inserting
    // and silently drop the purpose.
    fun addBolusWithPurpose(tsMs: Long, units: Double, purpose: String?) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            timedWrite("bolus+purpose") { store.addBolusWithPurpose(tsMs, units, purpose) }
            reloadAfterEvent(store)
        }
    }

    fun editBolusUnits(tsMs: Long, units: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.setBolusUnits(tsMs, units)
            reloadAfterEvent(store, relearn = tsMs < System.currentTimeMillis() - 6L * 3_600_000)
        }
    }

    fun deleteBolus(tsMs: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            timedWrite("delete bolus") { store.deleteInsulinForever(tsMs) }
            reloadAfterEvent(store, relearn = tsMs < System.currentTimeMillis() - 6L * 3_600_000)
        }
    }

    fun addMeter(tsMs: Long, mmol: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.upsertReading(
                com.diapilot.core.collector.Reading(
                    tsMs, mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F, mmol,
                    trend = null, source = "meter",
                ),
            )
            MeterCalCache.invalidate()
            // The calibration is baked into the model (corridor, food scale) —
            // a new check must rebuild it, or the header shifts to the new
            // lens while the model keeps the old one for up to 6h.
            TwinCache.invalidate()
            // A FINGERSTICK IS AN ALERT INPUT IN BOTH DIRECTIONS: an in-range
            // one newer than the sensor anchor silences the low side, a low one
            // confirms it, and the new lens moves every judged value. It is the
            // one source that must never be de-duplicated — it does not advance
            // the sensor clock the tick's key is built from.
            AlertTick.fire(context, AlertTick.Source.MANUAL)
            state = loadState(store, context)
        }
    }

    fun updateMeter(oldTsMs: Long, tsMs: Long, mmol: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.deleteReading(oldTsMs)
            store.upsertReading(
                com.diapilot.core.collector.Reading(
                    tsMs, mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F, mmol,
                    trend = null, source = "meter",
                ),
            )
            MeterCalCache.invalidate()
            TwinCache.invalidate()
            AlertTick.fire(context, AlertTick.Source.MANUAL)
            state = loadState(store, context)
        }
    }

    fun deleteMeter(tsMs: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.deleteReading(tsMs)
            MeterCalCache.invalidate()
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    fun deleteBasal(tsMs: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.deleteBasal(tsMs)
            reloadAfterEvent(store, relearn = true)
        }
    }

    fun updateBasal(oldTs: Long, newTs: Long, units: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            if (oldTs != newTs) store.deleteBasal(oldTs)
            store.upsertBasal(newTs, units)
            reloadAfterEvent(store, relearn = true)
        }
    }

    fun dismissMeal(onsetMs: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.dismissMeal(onsetMs)
            // Dismissing is the ONLY way to stop the forecast projecting a
            // phantom rise as absorption, so it must reach the twin at once —
            // label/relabel invalidate, this one never did, and the dismissed
            // meal kept feeding the forecast until the cache expired.
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    /**
     * The user's answer about an anomalous meal.
     *
     * A LAYER, never an edit: nothing here touches the note, its grams or the detected
     * event. `addMealMark` revokes any previous answer inside one transaction and appends
     * the new one, so the sequence of what the user thought survives and can be taken back.
     *
     * `invalidate()` is not optional — the mark changes what may be learned, and a stale
     * twin would make the answer look inert. That is the class of defect this project has
     * hit four times: built and not wired.
     *
     * The `loadState` call after it is load-bearing too, for a reason that is easy to miss:
     * `invalidate()` clears the in-memory twin but NOT `diskRestored`, and background
     * consumers — the hypo alert among them — read the disk snapshot with no key check. So
     * until something REBUILDS, the alarm would keep running the pre-mark model. `loadState`
     * builds and rewrites the snapshot in this same coroutine, which closes that window;
     * dropping the disk copy instead would leave the alarm with no model at all, which is
     * far worse than a briefly stale one.
     */
    fun markMeal(onsetMs: Long, kind: com.diapilot.core.analysis.MarkKind, comment: String?) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.addMealMark(
                com.diapilot.core.analysis.MealMark(
                    onsetMs = onsetMs,
                    kind = kind,
                    comment = comment,
                    author = com.diapilot.core.analysis.MARK_AUTHOR_HUMAN,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    /** Take an answer back. Nothing is deleted; the row is revoked and the meal returns
     *  to the triage list with its full case file. */
    fun unmarkMeal(onsetMs: Long) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            store.revokeMealMark(onsetMs, System.currentTimeMillis())
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    fun deleteNote(note: com.diapilot.core.collector.Annotation) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            timedWrite("delete note") { store.deleteAnnotation(note.id) }
            note.mediaRef?.let { java.io.File(photosDir(context), it).delete() }
            if (note.kind == "food") reloadAfterEvent(store, relearn = true)
            else state = loadState(store, context)
        }
    }

    // Reconcile: compute the label-fix plan (read-only — for the dry-run
    // preview) and apply the ones the user approved. Bulk cleanup instead of
    // hand-editing every history row.
    suspend fun reconcilePlan(): List<com.diapilot.core.analysis.LabelFix> =
        withContext(Dispatchers.IO) {
            val store = graph.store
            val now = System.currentTimeMillis()
            com.diapilot.core.analysis.reconcileLabels(
                store.annotations(0, now), store.labeledMeals(limit = 2000),
            )
        }

    // Batch-decompose: propose composition splits for composite meals (from their
    // name), review, apply. Enriches the component substrate without touching
    // the human name.
    suspend fun decomposePlan(): List<DishDecomp> = withContext(Dispatchers.IO) {
        val store = graph.store
        val gramsByNorm = (store as? SqliteCollectorStore)
            ?.foodLibrary().orEmpty()
            .mapNotNull { e ->
                e.grams?.takeIf { it > 0 }
                    ?.let { com.diapilot.core.analysis.normalizeFoodName(e.name) to it }
            }.toMap()
        computeDecompPlan(store.annotations(0, System.currentTimeMillis()), gramsByNorm)
    }

    // LLM decompose: the messy free-text the lexical parser can't read
    // (e.g. two named items with a combined weight and one more item, written
    // as one run-on sentence, gets split into the individual dishes).
    // One API call per composite meal without a split; reuses the same review
    // dialog + apply as the lexical 🧩.
    suspend fun llmDecomposePlan(): List<DishDecomp> = withContext(Dispatchers.IO) {
        val key = AskClaude.apiKey(context)
        if (key == null) {
            android.util.Log.w("Decompose", "no API key")
            return@withContext emptyList()
        }
        val store = graph.store
        val notes = store.annotations(0, System.currentTimeMillis()).filter {
            it.kind == "food" && it.content.isNotBlank() &&
                !com.diapilot.core.analysis.isContextNote(it.content) &&
                com.diapilot.core.analysis.parseComponents(it.analysis ?: "").isEmpty() &&
                looksComposite(it.content)
        }.take(40)  // safety cap on one-time API usage
        android.util.Log.i("Decompose", "LLM candidates: ${notes.size}")
        var ok = 0
        var failed = 0
        var single = 0
        val out = notes.mapNotNull { note ->
            try {
                val analysis = AskClaude.estimateCarbs(key, note.content, context = context)
                val comps = com.diapilot.core.analysis.parseComponents(analysis)
                if (comps.size < 2) {
                    single++
                    android.util.Log.d("Decompose", "single/none «${note.content.take(30)}»: ${comps.size}")
                    return@mapNotNull null
                }
                ok++
                DishDecomp(
                    note.id, note.tsMs, note.content, note.analysis,
                    comps.map { Triple(it.name, it.count, it.unitGrams) },
                )
            } catch (e: Exception) {
                failed++
                android.util.Log.w("Decompose", "LLM fail «${note.content.take(30)}»: ${e.message}")
                null
            } finally {
                Thread.sleep(250)  // gentle on the API rate limit
            }
        }
        android.util.Log.i("Decompose", "LLM done: ok=$ok single=$single failed=$failed")
        out
    }

    /**
     * One economical LLM request for up to 60 UNIQUE historical portions.
     * Repeated identical dishes share one estimate, then fan back out to all
     * matching annotation ids. Photos are deliberately not resent.
     */
    suspend fun nutritionPlan(): List<NutritionFill> = withContext(Dispatchers.IO) {
        val key = AskClaude.apiKey(context)
            ?: return@withContext emptyList()
        val store = graph.store
        val notes = store.annotations(0, System.currentTimeMillis()).filter { note ->
            note.kind == "food" && note.content.isNotBlank() &&
                !com.diapilot.core.analysis.isContextNote(note.content) &&
                com.diapilot.core.analysis.parseFoodNutrition(note.analysis).let {
                    it.proteinG == null || it.fatG == null || it.kcal == null
                }
        }
        data class Group(
            val notes: MutableList<com.diapilot.core.collector.Annotation>,
            val dish: String,
            val carbs: Double?,
            val composition: String?,
            val nutrition: com.diapilot.core.analysis.FoodNutrition,
        )
        val groups = linkedMapOf<String, Group>()
        notes.forEach { note ->
            val nutrition = com.diapilot.core.analysis.parseFoodNutrition(note.analysis)
            val components = com.diapilot.core.analysis.parseComponents(note.analysis.orEmpty())
            val comp = components.takeIf { it.isNotEmpty() }?.joinToString("; ") {
                "${it.name} ×${it.count}: ${"%.1f".format(java.util.Locale.ROOT, it.totalGrams)} g carbs"
            }
            val signature = listOf(
                com.diapilot.core.analysis.normalizeFoodName(note.content),
                note.estCarbs?.let { "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty(),
                comp.orEmpty(),
                nutrition.proteinG?.toString().orEmpty(),
                nutrition.fatG?.toString().orEmpty(),
                nutrition.kcal?.toString().orEmpty(),
            ).joinToString("|")
            groups.getOrPut(signature) {
                Group(mutableListOf(), note.content, note.estCarbs, comp, nutrition)
            }.notes.add(note)
        }
        val selected = groups.values.take(60)
        val inputs = selected.mapIndexed { i, g ->
            AskClaude.NutritionBatchInput(
                id = "n$i",
                dish = g.dish,
                carbsG = g.carbs,
                composition = g.composition,
                missingFields = buildSet {
                    if (g.nutrition.proteinG == null) add("protein_g")
                    if (g.nutrition.fatG == null) add("fat_g")
                    if (g.nutrition.kcal == null) add("kcal")
                },
            )
        }
        val result = AskClaude
            .estimateNutritionBatch(key, inputs)
            .associateBy { it.id }
        selected.mapIndexedNotNull { i, g ->
            result["n$i"]?.let {
                val protein = g.nutrition.proteinG ?: it.proteinG
                val fat = g.nutrition.fatG ?: it.fatG
                val kcal = g.nutrition.kcal ?: it.kcal
                if (protein == null || fat == null || kcal == null) return@let null
                NutritionFill(
                    annotationIds = g.notes.map { n -> n.id },
                    dish = g.dish,
                    proteinG = protein,
                    fatG = fat,
                    kcal = kcal,
                )
            }
        }
    }

    fun addFoodEvidence(
        tsMs: Long,
        text: String,
        mediaRef: String?,
        estCarbs: Double?,
        analysis: String,
        evidence: com.diapilot.core.collector.CarbEvidenceInputV1,
    ) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val now = System.currentTimeMillis()
            store.addFoodWithCarbEvidence(
                com.diapilot.core.collector.Annotation(
                    tsMs, "food", text, mediaRef,
                    estCarbs = estCarbs,
                    carbsKnownAtMs = now,
                    carbsSource = null,
                ),
                analysis,
                evidence,
                now,
            )
            reloadAfterEvent(store)
        }
    }

    fun applyNutrition(plans: List<NutritionFill>) {
        if (plans.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val byId = store.annotations(0, System.currentTimeMillis()).associateBy { it.id }
            plans.forEach { plan ->
                plan.annotationIds.forEach { id ->
                    val note = byId[id] ?: return@forEach
                    val old = com.diapilot.core.analysis.parseFoodNutrition(note.analysis)
                    val merged = com.diapilot.core.analysis.FoodNutrition(
                        proteinG = old.proteinG ?: plan.proteinG,
                        fatG = old.fatG ?: plan.fatG,
                        kcal = old.kcal ?: plan.kcal,
                    )
                    store.setAnnotationAnalysis(
                        id,
                        com.diapilot.core.analysis.withFoodNutrition(note.analysis, merged),
                    )
                }
            }
            state = loadState(store, context, allowBuild = false)
        }
    }

    fun applyDecompose(plans: List<DishDecomp>) {
        if (plans.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            plans.forEach { d ->
                val merged = listOfNotNull(
                    d.existingAnalysis?.takeIf { it.isNotBlank() }, decompSostav(d),
                ).joinToString("\n")
                store.setAnnotationAnalysis(d.annotationId, merged)
            }
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    fun applyReconcile(fixes: List<com.diapilot.core.analysis.LabelFix>) {
        if (fixes.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            fixes.forEach { store.relabelMeal(it.onsetMs, it.labelId, it.to) }
            TwinCache.invalidate()
            state = loadState(store, context)
        }
    }

    /**
     * Half portion / double portion — one record, rescaled as a whole.
     *
     * The scaling rule lives in [FoodPortionScalingV1] and is tested there:
     * grams are scaled, the fast/medium/slow shares are not. Here it is
     * only the record: text, grams and the parse update together, or the
     * record would contradict itself.
     */
    fun scaleFoodPortion(note: com.diapilot.core.collector.Annotation, factor: Double) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val scaled = com.diapilot.core.analysis.FoodPortionScalingV1.scale(
                note.content, note.estCarbs, note.analysis, factor,
                portionWord = FoodText.portionWord(context),
            )
            store.updateAnnotation(note.id, note.tsMs, scaled.text, note.mediaRef)
            store.setAnnotationCarbs(note.id, scaled.estCarbsG, source = "portion-scaled")
            scaled.analysis?.let { store.setAnnotationAnalysis(note.id, it) }
            reloadAfterEvent(store)
        }
    }

    fun updateNote(note: com.diapilot.core.collector.Annotation, tsMs: Long, text: String, mediaRef: String?) {
        scope.launch(Dispatchers.IO) {
            val store = graph.store
            val oldContent = note.content
            store.updateAnnotation(note.id, tsMs, text, mediaRef)
            // Photo replaced — remove the orphaned old file.
            if (note.mediaRef != null && note.mediaRef != mediaRef) {
                java.io.File(photosDir(context), note.mediaRef!!).delete()
            }
            // Renaming a food note must follow through to the LABELED EPISODE it
            // owns — otherwise the detected rise keeps the old name, the library
            // reports it as unused and the model learns the wrong dish. Move the
            // nearest episode that still carries the old name (precise: the one
            // this note labeled), not just any neighbour.
            val oldNorm = com.diapilot.core.analysis.normalizeFoodName(oldContent)
            val newName = text.trim()
            if (note.kind == "food" && oldNorm.isNotEmpty() &&
                oldNorm != com.diapilot.core.analysis.normalizeFoodName(newName)
            ) {
                store.labeledMeals(limit = 800)
                    .filter {
                        kotlin.math.abs(it.event.onsetMs - note.tsMs) < 120L * 60_000 &&
                            com.diapilot.core.analysis.normalizeFoodName(it.labelName) == oldNorm
                    }
                    .minByOrNull { kotlin.math.abs(it.event.onsetMs - note.tsMs) }
                    ?.let {
                        store.relabelMeal(it.event.onsetMs, it.labelId, newName)
                    }
            }
            if (note.kind == "food") reloadAfterEvent(store, relearn = true)
            else state = loadState(store, context)
        }
    }

    var showComposer by remember { mutableStateOf(false) }
    // History → chart jump: the Today chart centers on this moment once.
    var chartFocus by remember { mutableStateOf<Long?>(null) }
    // Long-press on the chart: what the app forecast at that minute. See
    // [ForecastAtPointDialog].
    var inspectAtMs by remember { mutableStateOf<Long?>(null) }
    var inspectedRun by remember {
        mutableStateOf<ForecastLedger.InspectedRun?>(null)
    }
    var inspectReplayPoints by remember {
        mutableStateOf<List<com.diapilot.core.twin.PredictedPoint>>(emptyList())
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    if (i == 2) {
                        // The composer is the most frequent action — it earns
                        // a thumb-reach slot in the middle of the nav bar.
                        // Icon-only: the ➕ explains itself, and its label was
                        // the one that wrapped and broke the whole row.
                        NavigationBarItem(
                            selected = false,
                            onClick = { showComposer = true },
                            icon = {
                                AddNavIcon()
                            },
                        )
                    }
                    // Label only under the active tab — six always-on labels
                    // read as visual noise on a 6-slot bar.
                    NavigationBarItem(
                        selected = tab == i,
                        // Leaving the Data sources leaf too: without this the
                        // nav bar would highlight a tab whose content the leaf
                        // is still covering.
                        onClick = { tab = i; showDataSources = false },
                        icon = { AppNavIcon(t, selected = tab == i) },
                        label = { Text(tabLabel(t), maxLines = 1) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { innerPadding ->
        if (showComposer) {
            ComposerSheet(
                state = state,
                onDismiss = { showComposer = false },
                onAddNote = ::addNote,
                onAddFoodWithAnalysis = ::addFoodWithAnalysis,
                onAddFoodEvidence = ::addFoodEvidence,
                onAddBasal = ::addBasal,
                onAddBolus = ::addManualBolus,
                onAddMeter = ::addMeter,
                onTagBolus = ::tagBolus,
                onAddBolusWithPurpose = ::addBolusWithPurpose,
            )
        }
        val mod = Modifier.padding(innerPadding)
        // System back leaves the leaf screen instead of the app.
        BackHandler(enabled = showDataSources) { showDataSources = false }
        if (showDataSources) {
            DataSourcesScreen(onBack = { showDataSources = false }, modifier = mod)
        } else when (Tab.entries[tab]) {
            Tab.TODAY -> Refreshable(refreshing, ::refresh, mod) {
                TodayScreen(
                    state,
                    onAddNote = ::addNote,
                    onAddFoodWithAnalysis = ::addFoodWithAnalysis,
                    onQuickDextrose = ::quickDextrose,
                    onConnectHc = onConnectHc,
                    onOpenLabel = { tab = Tab.LABEL.ordinal },
                    onOpenDataSources = { showDataSources = true },
                    onAddBasal = ::addBasal,
                    onTagBolus = ::tagBolus,
                    onEditBolusUnits = ::editBolusUnits,
                    onDeleteBolus = ::deleteBolus,
                    onUpdateBasal = ::updateBasal,
                    onDeleteNoteById = ::deleteNoteById,
                    onDeleteBasal = ::deleteBasal,
                    onUpdateNote = ::updateNote,
                    onDeleteNote = ::deleteNote,
                    focusTs = chartFocus,
                    onFocusHandled = { chartFocus = null },
                    inspectStored = inspectedRun?.points.orEmpty().map {
                        com.diapilot.core.twin.PredictedPoint(it.targetTsMs, it.predictedMmol, it.lo, it.hi)
                    },
                    inspectReplay = inspectReplayPoints,
                    onInspectForecast = { ts ->
                        inspectAtMs = ts
                        scope.launch(Dispatchers.IO) {
                            val store = graph.store
                            val run = runCatching {
                                (store as? SqliteCollectorStore)?.let {
                                    ForecastLedger.inspectAt(
                                        it.readableDatabase, ts,
                                        // Same lens the forecast lived on —
                                        // comparing it against a RAW fact is
                                        // the corridor mistake found before.
                                        cal = MeterCalCache.get(it, context),
                                    )
                                }
                            }.getOrNull()
                            // TODAY'S MODEL, PUT BACK IN THAT MOMENT.
                            //
                            // `knowledgeTsMs` is the anchor, so this pass is
                            // blind to everything logged afterwards — the same
                            // information the stored line had. That is a
                            // deliberate design choice, and it is what
                            // makes the comparison about the MODEL rather than
                            // about how promptly the meal was written down.
                            //
                            // `recordAs = null` keeps it out of the ledger and
                            // out of the What-if profile: a preview must not
                            // overwrite what the screen is showing.
                            val replay = run?.let { r ->
                                runCatching {
                                    TwinCache
                                        .getForForecast(store, context)
                                        ?.let { m ->
                                            Forecaster.forecast(
                                                store, m,
                                                nowMs = r.anchorTsMs,
                                                anchorTsMs = r.anchorTsMs,
                                                anchorMmol = r.anchorMmol,
                                                recordAs = null,
                                            )
                                        }
                                }.getOrNull()?.points.orEmpty()
                            }.orEmpty()
                            withContext(Dispatchers.Main.immediate) {
                                inspectedRun = run
                                inspectReplayPoints = replay
                            }
                        }
                    },
                    onRefresh = { refresh() },
                    onHistoryNav = { date ->
                        ChartNav.anchorMs = date
                        scope.launch(Dispatchers.IO) {
                            state = loadState(graph.store, context)
                        }
                    },
                )
                // A STRIP AT THE BOTTOM, NOT A DIALOG. The two forecasts are
                // drawn on the chart behind it, and a modal that covers the
                // chart hides exactly what it is describing.
                inspectAtMs?.let { ts ->
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                    ) {
                        ForecastAtPointStrip(
                            run = inspectedRun,
                            requestedTsMs = ts,
                            mgdl = state.mgdl,
                            replayPoints = inspectReplayPoints.size,
                            onDismiss = {
                                inspectAtMs = null
                                inspectedRun = null
                                inspectReplayPoints = emptyList()
                            },
                        )
                    }
                }
            }
            Tab.LABEL -> Refreshable(refreshing, ::refresh, mod) {
                LabelScreen(
                    state,
                    onLabel = ::label,
                    onRelabel = ::relabel,
                    onDismissMeal = ::dismissMeal,
                    onUpdateNote = ::updateNote,
                    onDeleteNote = ::deleteNote,
                    onRepeatFood = ::repeatFoodNow,
                    onScaleFoodPortion = ::scaleFoodPortion,
                    onDeleteBasal = ::deleteBasal,
                    onUpdateBasal = ::updateBasal,
                    onTagBolus = ::tagBolus,
                    onAddNote = ::addNote,
                    onShowOnChart = { ts ->
                        // A history event may be months old while Today normally
                        // loads only the live 72-hour chart window. Move the data
                        // window first; focusing an unloaded timestamp only
                        // changed tabs and left the user on today's graph.
                        ChartNav.anchorMs = ts
                        tab = Tab.TODAY.ordinal
                        scope.launch {
                            state = withContext(Dispatchers.IO) {
                                loadState(graph.store, context, allowBuild = false)
                            }
                            chartFocus = ts
                        }
                    },
                    onEditBolusUnits = ::editBolusUnits,
                    onDeleteBolus = ::deleteBolus,
                    onUpdateMeter = ::updateMeter,
                    onDeleteMeter = ::deleteMeter,
                    onRefreshRequest = ::refresh,
                    onReconcilePlan = ::reconcilePlan,
                    onApplyReconcile = ::applyReconcile,
                    onDecomposePlan = ::decomposePlan,
                    onLlmDecomposePlan = ::llmDecomposePlan,
                    onApplyDecompose = ::applyDecompose,
                    onNutritionPlan = ::nutritionPlan,
                    onApplyNutrition = ::applyNutrition,
                    onSearchStart = {
                        if (HistoryNav.days < 60) {
                            HistoryNav.days = 60
                            scope.launch(Dispatchers.IO) {
                                state = loadState(graph.store, context, allowBuild = false)
                            }
                        }
                    },
                    onMarkMeal = ::markMeal,
                    onUnmarkMeal = ::unmarkMeal,
                    onLoadMoreHistory = {
                        if (HistoryNav.days < 60) {
                            HistoryNav.days += 7
                            scope.launch(Dispatchers.IO) {
                                state = loadState(graph.store, context)
                            }
                        }
                    },
                    onHistoryJump = { date ->
                        // -1 = return to the live edge; a real date anchors a
                        // fresh 7-day window ending just after it.
                        HistoryNav.anchorMs = date.takeIf { it > 0 }
                        HistoryNav.days = 7
                        scope.launch(Dispatchers.IO) {
                            state = loadState(graph.store, context)
                        }
                    },
                )
            }
            Tab.ANALYSIS -> AnalysisScreen(modifier = mod)
            Tab.ASK -> AskScreen(modifier = mod)
            Tab.SETTINGS -> SettingsScreen(
                modifier = mod,
                onOpenDataSources = { showDataSources = true },
                // The auto-fit lists the episodes it used; each is tappable and
                // lands on the chart at that moment. Same path History already
                // takes, and for the same reason — an episode months back is
                // outside Today's live window, so the DATA window has to move
                // before the focus does, or the tap only changes tabs.
                onShowOnChart = { ts ->
                    ChartNav.anchorMs = ts
                    tab = Tab.TODAY.ordinal
                    scope.launch {
                        state = withContext(Dispatchers.IO) {
                            loadState(graph.store, context, allowBuild = false)
                        }
                        chartFocus = ts
                    }
                },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        content()
    }
}

/** The context composer as a bottom sheet — opened by the ＋ nav item. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ComposerSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onAddNote: (Long, String, String?, String, Double?) -> Unit,
    onAddFoodWithAnalysis: (Long, String, String?, Double?, String) -> Unit,
    onAddFoodEvidence: (Long, String, String?, Double?, String, com.diapilot.core.collector.CarbEvidenceInputV1) -> Unit,
    onAddBasal: (Long, Double) -> Unit,
    onAddBolus: (Long, Double) -> Unit,
    onAddMeter: (Long, Double) -> Unit,
    onTagBolus: (Long, String) -> Unit,
    onAddBolusWithPurpose: (Long, Double, String?) -> Unit,
) {
    val now = System.currentTimeMillis()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        // Zero out the sheet's own insets and pad the content by hand:
        // otherwise the sheet swallows the IME inset and the keyboard
        // covers the input fields.
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        // Scrollable wrapper: with the keyboard up the content must
        // scroll, not get vertically squeezed (clipped text fields).
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AnnotationComposer(
                frequentTexts = state.frequentNotes,
                recentBoluses = state.chartBoluses.filter { it.tsMs > now - 6L * 3_600_000 },
                foodLabels = state.foodLabels,
                foodMemories = state.foodMemory,
                similarFood = state.similarFood,
                carbsByFood = state.carbsByFood,
                mealComponents = state.mealComponents,
                compositeParts = state.compositeParts,
                recentFoodTexts = state.recentFoodTexts,
                recentFoodCarbs = state.recentFoodCarbs,
                compositionByFood = state.compositionByFood,
                photoByFood = state.photoByFood,
                typicalMeals = state.typicalMeals,
                bgAtShot = state.bgAtShot,
                onAdd = onAddNote,
                onAddFoodWithAnalysis = onAddFoodWithAnalysis,
                onAddFoodEvidence = onAddFoodEvidence,
                onAddBasal = onAddBasal,
                onAddBolus = onAddBolus,
                onAddMeter = onAddMeter,
                movingFast = state.movingFast,
                onTagBolus = onTagBolus,
                onAddBolusWithPurpose = onAddBolusWithPurpose,
                alwaysOpen = true,
                onSubmitted = onDismiss,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

// ------------------------------------------------------------- Label tab

/** One merged history row: a meal, a bolus or a note. */
