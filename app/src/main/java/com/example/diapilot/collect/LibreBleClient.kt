package com.example.diapilot.collect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * DiaPilot's own BLE link to the Libre 2 sensor (experimental, opt-in).
 *
 * Transport ported from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0
 * (DexCollectionService/LibreBluetooth by Tzachi Dar): connect by the MAC
 * the NFC enable-streaming command returned,
 * subscribe to the data characteristic, write the per-connection unlock
 * buffer to the login characteristic, reassemble notification fragments into
 * the 46-byte encrypted packet, and hand it to OOP2 for decryption over its
 * broadcast API. The decoded per-minute values come back through the
 * CollectorService receiver that already feeds the minute stream.
 *
 * The connection counter is the crypto nonce: every (re)connect consumes the
 * next precomputed unlock buffer from Libre2State.
 */
@SuppressLint("MissingPermission")
class LibreBleClient(private val context: Context) {

    companion object {
        private const val TAG = "LibreBle"
        val SERVICE: UUID = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        val LOGIN_CHAR: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        val DATA_CHAR: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PACKET_LEN = 46
        private const val FRAGMENT_GAP_MS = 3_000L
        // Reconnect backoff: 15s → 30s → 1m → 2m → 5m (cap). A fixed 15s
        // retry against an absent sensor / disabled Bluetooth burned battery
        // all night; a productive session resets the ladder.
        private const val RECONNECT_DELAY_MS = 15_000L
        private const val RECONNECT_MAX_MS = 5L * 60_000
        const val ACTION_LIBRE_BLE_DATA = "com.eveningoutpost.dexdrip.LIBRE_BLE_DATA"
        private const val OOP2_PACKAGE = "com.hg4.oopalgorithm.oopalgorithm2"
        // Watchdog: Android BLE fails silently as often as it disconnects —
        // connectGatt that never calls back, a CONNECTED gatt whose
        // notifications stop, a hung service-discovery / login / CCCD write.
        // None of those raise STATE_DISCONNECTED, so without a packet-timeout
        // the stream can stand until a manual NFC scan. The watchdog forces a
        // reconnect when no forward PROGRESS was made within the phase limit.
        private const val WATCHDOG_TICK_MS = 20_000L
        // No packet since the connect began (covers hung connect/discover/login).
        private const val HANDSHAKE_TIMEOUT_MS = 45_000L
        // A live stream went silent (sensor transmits ~1/min; 2.5 min = dead).
        private const val STREAM_SILENCE_MS = 150_000L
        // Grace for a forced disconnect's callback before a hard close (true
        // zombie GATT: even disconnect() never calls back).
        private const val FORCE_CLOSE_GRACE_MS = 5_000L
        // CPU held just long enough for connect→discover→login→CCCD→first
        // packet; the BLE stack's own notifications wake the app afterwards.
        private const val WAKE_MS = 60_000L
        // Armed passive wait (autoConnect) leash: it is SUPPOSED to sit silent
        // until the sensor advertises, so the watchdog gives it a long rope —
        // but not an infinite one (Android's armed autoConnect can die
        // silently), after which the gatt is recycled and re-armed.
        private const val ARMED_RECYCLE_MS = 10L * 60_000
    }

    /** Doze-proof recovery needs the CPU up for the handshake: a foreground
     *  service does NOT keep it awake. Timed acquire = no leak possible. */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "diapilot:ble-recover")
            .apply { setReferenceCounted(false) }
    }

    private fun wake() {
        runCatching { wakeLock.acquire(WAKE_MS) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var running = false

    @Volatile
    var lastPacketMs: Long = 0
        private set

    @Volatile
    var status: String = "выключен"
        private set(value) {
            field = value
            DiagState.bleStatus = value
        }

    private val buffer = ByteArray(PACKET_LEN)
    private var bufferFilled = 0
    private var lastFragmentMs = 0L

    // The sensor repeats each minute's packet (~6-8s apart) — identical
    // ciphertext gets forwarded once.
    private var lastPacket: ByteArray? = null
    private var lastPacketSentMs = 0L

    /** Packets received in the CURRENT session — a silent session means the
     *  login wasn't accepted, so its nonce is retried, not burned. */
    @Volatile
    private var packetsThisSession = 0

    // Any forward step in the connect→stream handshake, for the watchdog. A
    // hung phase stops bumping this and the watchdog forces a reconnect.
    @Volatile
    private var lastProgressMs: Long = 0

    private fun markProgress() {
        lastProgressMs = System.currentTimeMillis()
    }

    // Hybrid connect strategy. A direct connect (autoConnect=false) is a ~30s
    // ACTIVE window — fast when the sensor is free, but when it is unreachable
    // (held by another central, radio-shadowed, an expired sensor pausing its
    // advertising) every window MISSES and the backoff stretches the outage to
    // 9-12 min (measured in practice, status=147 streaks). After an
    // establish-timeout we ARM autoConnect=true instead: the controller waits
    // passively and grabs the sensor the instant it advertises again.
    @Volatile
    private var autoConnectMode = false

    /** Link reached STATE_CONNECTED this session (handshake in progress). */
    @Volatile
    private var linkUp = false

    /** How long "no progress" is tolerated, by phase. */
    private fun progressLimitMs(): Long = when {
        packetsThisSession > 0 -> STREAM_SILENCE_MS      // live stream went quiet
        linkUp -> HANDSHAKE_TIMEOUT_MS                   // connected, mid-handshake
        autoConnectMode -> ARMED_RECYCLE_MS              // armed wait — meant to be silent
        else -> HANDSHAKE_TIMEOUT_MS                     // direct connect window
    }

    // Nonce desync recovery: when the sensor rejects a login (peer
    // disconnect right after, zero packets), the ladder may have slipped a
    // step (double bump on a service restart, a session the sensor counted
    // and we didn't…). Instead of hammering the same index forever, probe
    // the neighborhood; a productive session locks the found index in.
    // Drift is almost always FORWARD (the sensor's counter outruns ours when
    // failed attempts tick it and we don't), so the walk is forward-biased and
    // reaches further ahead than behind.
    private val probeOffsets = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, -1, -2, -3)
    @Volatile
    private var probeIdx = 0
    @Volatile
    private var loginSentThisSession = false
    @Volatile
    private var probedIndex = -1

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_TICK_MS)
        connect()
    }

    fun stop() {
        running = false
        status = "выключен"
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    /** Force a reconnect from a stuck (but not disconnected) state. Reuses the
     *  normal disconnect→reconnect path; a hard close covers the case where
     *  even disconnect() never calls back (true zombie GATT). */
    private fun forceReconnect(reason: String) {
        val g = gatt
        Log.w(TAG, "watchdog: $reason — forcing reconnect")
        status = "поток завис — переподключаюсь"
        loginSentThisSession = false        // not a login reject; don't advance the probe
        reconnectDelayMs = RECONNECT_DELAY_MS  // sensor was reachable — retry fast
        runCatching { g?.disconnect() }
        handler.postDelayed({
            if (running && gatt === g) {    // disconnect callback never came → zombie
                runCatching { g?.close() }
                gatt = null
                connect()
            }
        }, FORCE_CLOSE_GRACE_MS)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val g = gatt
            if (g != null && lastProgressMs > 0) {
                val silence = System.currentTimeMillis() - lastProgressMs
                val limit = progressLimitMs()
                if (silence > limit) {
                    forceReconnect("no progress ${silence / 1000}s (limit ${limit / 1000}s)")
                }
            }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private fun connect() {
        if (!running) return
        val state = com.example.diapilot.data.Libre2State.load(context)
        if (state == null || state.mac.isBlank()) {
            status = "нет MAC — отсканируйте сенсор"
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            status = "Bluetooth выключен"
            scheduleReconnect()
            return
        }
        try {
            val device = adapter.getRemoteDevice(state.mac)
            val auto = autoConnectMode
            status = if (auto) "сенсор молчит — дежурю в эфире" else "подключаюсь к ${state.mac}"
            Log.i(TAG, "connecting to ${state.mac}, connectionIndex=${state.connectionIndex}, auto=$auto")
            bufferFilled = 0
            packetsThisSession = 0
            linkUp = false
            markProgress()   // arm the watchdog for this attempt
            gatt = device.connectGatt(context, auto, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private var reconnectDelayMs = RECONNECT_DELAY_MS

    /** Named so a Doze wake-up can CANCEL a pending (frozen) retry before
     *  kicking its own — otherwise both fire and we double-connect. */
    private val reconnectRunnable = Runnable { connect() }

    private fun scheduleReconnect() {
        if (!running) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
    }

    /**
     * Doze-proof stall check, driven by an RTC_WAKEUP alarm (CollectorService).
     *
     * The Handler watchdog below only ticks while the CPU is awake — in deep
     * sleep `postDelayed` (uptimeMillis) never fires, so BOTH the watchdog and
     * the pending reconnect freeze and a night stall stands until a Doze
     * maintenance window (measured: ~37 min). This runs from the alarm instead,
     * takes the wakelock, and kicks recovery immediately.
     */
    fun wakeupCheck() {
        if (!running) return
        val now = System.currentTimeMillis()
        val g = gatt
        if (g != null) {
            // A link/handshake is in flight — same phase limits as the watchdog
            // (an ARMED autoConnect wait is deliberately silent and must not be
            // killed by the 45s handshake leash).
            val limit = progressLimitMs()
            if (lastProgressMs > 0 && now - lastProgressMs > limit) {
                wake()
                forceReconnect("doze wake: no progress ${(now - lastProgressMs) / 1000}s")
            }
            return
        }
        // No link at all: a Handler-scheduled retry may be frozen. If the stream
        // is stale, cancel the pending retry and reconnect now.
        val silence = now - maxOf(lastPacketMs, lastProgressMs)
        if (silence > STREAM_SILENCE_MS) {
            Log.w(TAG, "doze wake: no link, stream silent ${silence / 1000}s — reconnecting")
            wake()
            handler.removeCallbacks(reconnectRunnable)
            reconnectDelayMs = RECONNECT_DELAY_MS
            connect()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$s packets=$packetsThisSession")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                packetsThisSession = 0
                linkUp = true
                autoConnectMode = false   // reachable again — next cycle starts fast/direct
                markProgress()
                status = "подключён, ищу сервисы"
                if (!g.discoverServices()) {
                    Log.w(TAG, "discoverServices() rejected — reconnecting")
                    g.disconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status = "отключён, переподключение…"
                runCatching { g.close() }
                if (gatt !== g && gatt != null) {
                    // A STALE gatt's callback (client already reconnected) —
                    // it must not touch the nonce bookkeeping.
                    return
                }
                gatt = null
                if (packetsThisSession > 0) {
                    // Productive session: lock in the index that worked, then
                    // advance once for the next connection.
                    com.example.diapilot.data.Libre2State.load(context)?.let { st ->
                        com.example.diapilot.data.Libre2State.save(
                            context, st.copy(connectionIndex = probedIndex + 1),
                        )
                    }
                    probeIdx = 0
                    packetsThisSession = 0
                } else if (loginSentThisSession) {
                    // Login rejected — probe the next neighbor index. The
                    // sensor IS reachable here (it answered and refused), so
                    // the reconnect backoff must NOT slow the probe walk:
                    // reset to the fast rung — nonce probing wants seconds,
                    // the exponential ladder is for an absent sensor.
                    probeIdx = (probeIdx + 1) % probeOffsets.size
                    reconnectDelayMs = RECONNECT_DELAY_MS
                    Log.w(TAG, "login rejected; probing nonce offset ${probeOffsets[probeIdx]}")
                    if (probeIdx == 0) {
                        // Full circle, nothing accepted — the counter is far
                        // out of the probe range (today: sensor at 1, we at
                        // 79). Reconnecting harder won't help; tell the user.
                        StreamStallNotifier.maybeNotify(
                            context, "BLE-логин отвергнут на всех соседних индексах",
                        )
                    }
                } else if (!linkUp) {
                    // The connection was never even ESTABLISHED (status 147
                    // timeout streaks): the sensor isn't answering — held by
                    // another central, radio-shadowed, or an expired-but-alive
                    // sensor pausing its advertising. Direct 30s windows will
                    // keep missing the moment it frees; ARM a passive
                    // autoConnect that grabs it the instant it advertises.
                    if (!autoConnectMode) Log.w(TAG, "establish failed — arming passive autoConnect")
                    autoConnectMode = true
                    reconnectDelayMs = RECONNECT_DELAY_MS   // armed gatt waits; no ladder needed
                }
                loginSentThisSession = false
                linkUp = false
                scheduleReconnect()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            s: Int,
        ) {
            Log.i(TAG, "characteristic write ${c.uuid} status=$s")
            if (c.uuid != LOGIN_CHAR) return
            if (s != BluetoothGatt.GATT_SUCCESS) {
                // The write itself failed — the sensor won't stream. Drop the
                // link so the reconnect path (and probe) gets another go.
                Log.w(TAG, "login write failed status=$s — reconnecting")
                g.disconnect()
                return
            }
            markProgress()
            // Login is in → arm the CCCD so the stream has somewhere to land.
            val cccd = g.getService(SERVICE)?.getCharacteristic(DATA_CHAR)?.getDescriptor(CCCD)
            if (cccd == null) {
                Log.w(TAG, "CCCD missing — reconnecting")
                g.disconnect()
                return
            }
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(cccd)) {
                Log.w(TAG, "writeDescriptor rejected — reconnecting")
                g.disconnect()
                return
            }
            status = "подписка на данные…"
        }

        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            if (s != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed status=$s — reconnecting")
                g.disconnect()
                return
            }
            val svc = g.getService(SERVICE)
            if (svc == null) {
                Log.w(TAG, "fde3 service missing; reconnecting")
                g.disconnect()
                return
            }
            markProgress()
            // The sensor drops unauthenticated links FAST — the login goes
            // out first thing (xDrip's order); the CCCD round-trip follows
            // from onCharacteristicWrite. Every missing piece DISCONNECTS
            // (not a bare return): a bare return left the GATT connected with
            // no packets and nothing to reconnect it — a silent stall.
            val data = svc.getCharacteristic(DATA_CHAR)
            if (data == null) {
                Log.w(TAG, "data characteristic missing — reconnecting")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(data, true)
            val state = com.example.diapilot.data.Libre2State.load(context)
            if (state == null) {
                Log.w(TAG, "no credentials — reconnecting")
                g.disconnect()
                return
            }
            probedIndex = state.connectionIndex + probeOffsets[probeIdx]
            val unlock = state.unlockArray.getOrNull(probedIndex - state.unlockStartIndex)
            if (unlock == null) {
                status = "unlock-буферы кончились — пересканируйте сенсор"
                Log.w(TAG, "no unlock buffer for index $probedIndex")
                probeIdx = (probeIdx + 1) % probeOffsets.size
                g.disconnect()
                return
            }
            val login = svc.getCharacteristic(LOGIN_CHAR)
            if (login == null) {
                Log.w(TAG, "login characteristic missing — reconnecting")
                g.disconnect()
                return
            }
            @Suppress("DEPRECATION")
            login.value = unlock
            login.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(login)) {
                Log.w(TAG, "login writeCharacteristic rejected — reconnecting")
                g.disconnect()
                return
            }
            loginSentThisSession = true
            status = "логин отправлен (nonce $probedIndex)"
            Log.i(TAG, "login sent first, ${unlock.size} bytes, index=$probedIndex")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
            Log.i(TAG, "descriptor write status=$s")
            if (s != BluetoothGatt.GATT_SUCCESS) {
                status = "не удалось подписаться (status=$s)"
                g.disconnect()
            } else {
                markProgress()
                status = "жду поток…"
            }
        }

        // API 33+ delivers notifications through the NEW signature; older
        // Androids call the deprecated one. Both funnel into onChunk.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onChunk(value)

        @Deprecated("pre-33 path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < 33) c.value?.let(::onChunk)
        }
    }

    private fun onChunk(chunk: ByteArray) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "chunk ${chunk.size}B (filled=$bufferFilled)")
        if (now - lastFragmentMs > FRAGMENT_GAP_MS) bufferFilled = 0
        lastFragmentMs = now
        val n = minOf(chunk.size, PACKET_LEN - bufferFilled)
        chunk.copyInto(buffer, bufferFilled, 0, n)
        bufferFilled += n
        if (bufferFilled >= PACKET_LEN) {
            bufferFilled = 0
            packetsThisSession++
            reconnectDelayMs = RECONNECT_DELAY_MS   // productive link — reset the ladder
            lastPacketMs = now
            markProgress()
            DiagState.bleLastPacketMs = now
            status = "поток идёт (пакет ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(now)})"
            val packet = buffer.copyOf()
            // The sensor repeats/re-fragments within the minute and each
            // transmission is re-encrypted (different ciphertext) — dedup by
            // TIME: at most one packet per 45s reaches OOP2.
            if (now - lastPacketSentMs < 45_000) {
                Log.d(TAG, "packet throttled (${(now - lastPacketSentMs) / 1000}s since last)")
                return
            }
            lastPacket = packet
            lastPacketSentMs = now
            sendToOop2(packet, now)
            Libre2PairLog.logEncrypted(context, now, packet)
        }
    }

    /** The encrypted 46-byte packet → OOP2; the decode lands in CollectorService. */
    private fun sendToOop2(packet: ByteArray, tsMs: Long) {
        val state = com.example.diapilot.data.Libre2State.load(context) ?: return
        val intent = Intent(ACTION_LIBRE_BLE_DATA).apply {
            putExtra("com.eveningoutpost.dexdrip.Extras.DATA_BUFFER", packet)
            putExtra("com.eveningoutpost.dexdrip.Extras.TIMESTAMP", tsMs)
            putExtra("com.eveningoutpost.dexdrip.Extras.LIBRE_PATCH_UID_BUFFER", state.uid)
            putExtra("com.eveningoutpost.dexdrip.Extras.LIBRE_RAW_ID", android.os.Process.myPid())
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            setPackage(OOP2_PACKAGE)
        }
        context.sendBroadcast(intent)
    }
}
