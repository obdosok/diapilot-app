package com.diapilot.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sequence tests for the hypo-alert state machine — the scenarios the
 * external review asked to pin down: predicted → confirmed low → dextrose
 * snooze → persistent escalation → recovery; a fingerstick newer/older than
 * the sensor; artifact loudness.
 */
class HypoAlertLogicTest {

    private val T0 = 1_000_000_000_000L
    private val MIN = 60_000L

    private fun inputs(
        now: Long,
        bg: Double,
        predicted: Boolean = false,
        anchorTs: Long = now - MIN,
        night: Boolean = false,
        nightGentle: Boolean = true,
        meterMmol: Double? = null,
        meterTs: Long = 0,
        dextroseTs: Long? = null,
        appOpenedAt: Long = 0,
    ) = HypoAlertInputs(
        nowMs = now,
        anchorTsMs = anchorTs,
        anchorMmol = bg,
        thresholdMmol = 3.9,
        predictedHit = predicted,
        night = night,
        nightGentle = nightGentle,
        lastMeterMmol = meterMmol,
        lastMeterTsMs = meterTs,
        lastDextroseTsMs = dextroseTs,
        appOpenedAtMs = appOpenedAt,
    )

    @Test
    fun `full episode - predicted, low, dextrose snooze, persistent, recovery`() {
        var s = HypoAlertState()
        var now = T0

        // Predicted (day) → immediate full predictive alarm.
        var d = decideHypoAlert(s, inputs(now, bg = 4.5, predicted = true))
        assertEquals(HypoAction.PREDICT_ALARM, d.action)
        assertTrue(d.alertActive)
        s = d.state

        // 6 min later, now actually LOW → confirmed-low alarm.
        now += 6 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.4))
        assertEquals(HypoAction.LOW_ALARM, d.action)
        s = d.state

        // 2 min later, still low, inside the re-fire window → quiet, active.
        now += 2 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.2))
        assertEquals(HypoAction.NONE, d.action)
        assertTrue(d.alertActive)
        s = d.state

        // Dextrose logged 6 min into the low → snooze: quiet AND watch stops
        // buzzing. THE TIMING IS LOAD-BEARING, do not "tidy" it: the rescue must
        // still be INSIDE snoozeMs (20) when persistence hits at low+25, otherwise
        // `treated` is already false on its own and the `!persistentLow` guard is
        // never exercised — which is exactly how this test went blind once.
        now += 4 * MIN            // low+6
        val dextroseAt = now
        d = decideHypoAlert(s, inputs(now, bg = 3.2, dextroseTs = dextroseAt))
        assertEquals(HypoAction.NONE, d.action)
        assertFalse(d.alertActive)
        s = d.state

        // Still inside the snooze window and not yet persistently low → quiet.
        now += 8 * MIN            // low+14, dextrose 8 min old (< 20)
        d = decideHypoAlert(s, inputs(now, bg = 3.1, dextroseTs = dextroseAt))
        assertEquals(HypoAction.NONE, d.action)
        s = d.state

        // The invariant: a snooze never outlives the persistence window —
        // treatment that didn't work must not be silent. Here the rescue is STILL
        // FRESH (19 min < snoozeMs 20), so only `!persistentLow` can break the
        // snooze. Counted from lowSince, never from the episode/prediction open.
        now += 11 * MIN           // low+25 == persistMs; dextrose 19 min old (FRESH)
        d = decideHypoAlert(s, inputs(now, bg = 3.0, dextroseTs = dextroseAt))
        assertEquals(HypoAction.PERSISTENT_ALARM, d.action)
        assertTrue(d.alertActive)
        s = d.state

        // Recovery: back above the floor, no prediction → cleared, quiet.
        now += 10 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 4.6))
        assertEquals(HypoAction.NONE, d.action)
        assertFalse(d.alertActive)
        assertEquals(0L, d.state.episodeStartMs)
    }

    @Test
    fun `a persisted PREDICTED-only risk that recovered is not a sustained-low alarm`() {
        // The live bug (found around midnight): at night the alert watches the
        // wide lower CORRIDOR band, so a stable in-range 98 mg/dl (5.4) kept
        // predictedHit=true. The episode never closed, went "persistent", and
        // fired "Glucose low for a while <70 (98)" every minute — a sustained-low alarm
        // for a value never below the floor. Sustained-low must require BEING low.
        var s = HypoAlertState()
        var now = T0

        // Night, in-range, only the corridor predicts a dip → gentle predict opens the episode.
        var d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertEquals(HypoAction.PREDICT_GENTLE, d.action)
        s = d.state

        // 40 min later — well past persistMs — STILL in range (5.4), still only
        // predicted. Must NOT be a sustained-low alarm, and must not hammer.
        now += 40 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertNotEquals(HypoAction.PERSISTENT_ALARM, d.action)
        s = d.state
        now += 2 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertNotEquals(HypoAction.PERSISTENT_ALARM, d.action)

        // But a genuine sustained LOW still escalates to the persistent alarm.
        // Readings every 5 min. Stepping through them matters: the reported clock
        // counts OBSERVED low time, so a low must be WATCHED, not merely bracketed
        // by two passes 40 min apart — measured on the device's own pass stream
        // (forecast_runs consumer='hypo_alert', median step 1.0 min, p99 8) such a
        // pair is a sensor hole, and billing it as hypo is what is being fixed.
        var s2 = HypoAlertState()
        var t = T0
        var real = decideHypoAlert(s2, inputs(t, bg = 3.4, night = true))
        s2 = real.state
        repeat(8) {
            t += 5 * MIN
            real = decideHypoAlert(s2, inputs(t, bg = 3.3, night = true))
            s2 = real.state
        }
        assertEquals(HypoAction.PERSISTENT_ALARM, real.action)
    }

    @Test
    fun `an escalated prediction releases the WRIST, an actual low re-arms it`() {
        // The wrist is the thing the user feels: WatchServer re-casts alertActive
        // every 60 s and the bridge buzzes once per reading while it is true. The
        // persistent NOTIFICATION only re-fires every 10 min, so it was never the
        // source of "the watch vibrating every minute" — alertActive was, held open
        // all night by a night-corridor prediction that never materialised.
        var s = HypoAlertState()
        var now = T0

        var d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertEquals(HypoAction.PREDICT_GENTLE, d.action)
        assertTrue("a fresh warning holds the wrist", d.alertActive)
        s = d.state

        now += 6 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertEquals(HypoAction.ESCALATE_ALARM, d.action)
        s = d.state

        // Delivered. Still predicted, still in range → quiet AND wrist released.
        now += 30 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 5.4, predicted = true, night = true))
        assertEquals(HypoAction.NONE, d.action)
        assertFalse("an escalated prediction must stop buzzing the wrist", d.alertActive)
        s = d.state

        // But an ACTUAL low re-arms it at once — the safety net is intact. And
        // because the sustained clock counts from the first real low (lowSince),
        // this fresh low correctly lands on LOW_ALARM's urgent 5-min cadence — it
        // no longer inherits «persistent» from the long-open prediction (BUG 1).
        now += 5 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.4, night = true))
        assertEquals("a fresh real low is LOW_ALARM, not PERSISTENT", HypoAction.LOW_ALARM, d.action)
        assertTrue("a real low must buzz again", d.alertActive)
    }

    @Test
    fun `fingerstick newer than sensor silences, older does not`() {
        val s = HypoAlertState()
        val now = T0
        val anchorTs = now - MIN

        // Meter in range, NEWER than the anchor → trust it, quiet.
        var d = decideHypoAlert(
            s,
            inputs(now, bg = 3.4, anchorTs = anchorTs, meterMmol = 5.2, meterTs = anchorTs + 30_000),
        )
        assertEquals(HypoAction.NONE, d.action)
        assertFalse(d.alertActive)

        // Meter in range but OLDER than the fresh low sensor point → alarm.
        d = decideHypoAlert(
            s,
            inputs(now, bg = 3.4, anchorTs = anchorTs, meterMmol = 5.2, meterTs = anchorTs - 5 * MIN),
        )
        assertEquals(HypoAction.LOW_ALARM, d.action)
        assertTrue(d.alertActive)
    }

    @Test
    fun `critically low value alarms loud and re-fires`() {
        var s = HypoAlertState()
        var now = T0
        var d = decideHypoAlert(s, inputs(now, bg = 0.9))
        assertEquals(HypoAction.ARTIFACT_ALARM, d.action)
        assertTrue(d.alertActive)  // the watch buzzes too
        s = d.state

        // Within the cadence → quiet but still active.
        now += 2 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 0.8))
        assertEquals(HypoAction.NONE, d.action)
        assertTrue(d.alertActive)
        s = d.state

        // Past the cadence → alarms again.
        now += 4 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 0.7))
        assertEquals(HypoAction.ARTIFACT_ALARM, d.action)
    }

    @Test
    fun `night gentle escalates when unacknowledged, stays gentle when opened`() {
        var s = HypoAlertState()
        var now = T0

        // Night predicted → gentle first.
        var d = decideHypoAlert(s, inputs(now, bg = 4.5, predicted = true, night = true))
        assertEquals(HypoAction.PREDICT_GENTLE, d.action)
        s = d.state

        // 6 min later, unacknowledged, still predicted → escalate.
        now += 6 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 4.5, predicted = true, night = true))
        assertEquals(HypoAction.ESCALATE_ALARM, d.action)
        s = d.state

        // A fresh episode where the app IS opened after the gentle → no escalation.
        var s2 = HypoAlertState()
        var t = T0 + 3_600_000
        var d2 = decideHypoAlert(s2, inputs(t, bg = 4.5, predicted = true, night = true))
        assertEquals(HypoAction.PREDICT_GENTLE, d2.action)
        s2 = d2.state
        t += 6 * MIN
        d2 = decideHypoAlert(
            s2,
            inputs(t, bg = 4.5, predicted = true, night = true, appOpenedAt = t - 2 * MIN),
        )
        assertEquals(HypoAction.NONE, d2.action)
        assertTrue(d2.alertActive)
    }

    @Test
    fun `a dead-band blip must not reset the sustained-low clock (BUG 2)`() {
        // A single reading that pops just above the floor into the dead band
        // [threshold, threshold+margin) IN THE MIDDLE of a real sustained low
        // must not read as recovery: clearing the episode restarts the
        // persistence clock and pushes the PERSISTENT alarm ~25 min late (the
        // wrist goes quiet, the face goes green). Real data: 42 sustained lows
        // in history contain exactly such a blip.
        var s = HypoAlertState()
        var now = T0

        // Genuinely low → confirmed-low alarm opens the episode. Readings arrive
        // every 4 min: the clock now counts OBSERVED low time, so the low has to
        // be watched through, not jumped over.
        var d = decideHypoAlert(s, inputs(now, bg = 3.5))
        assertEquals(HypoAction.LOW_ALARM, d.action)
        val episodeOpenedAt = d.state.episodeStartMs
        assertNotEquals(0L, episodeOpenedAt)
        val lowStart = d.state.lowSinceMs
        s = d.state
        repeat(2) {
            now += 4 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.5))
            s = d.state
        }

        // 12 min in, one reading blips to 4.0 — above 3.9 but inside the dead
        // band [3.9, 4.3). The user has NOT recovered.
        now += 4 * MIN             // 12 min in
        d = decideHypoAlert(s, inputs(now, bg = 4.0))
        assertEquals(
            "a dead-band blip must not clear the open low episode",
            episodeOpenedAt, d.state.episodeStartMs,
        )
        assertEquals("the sustained-low clock survives the blip", lowStart, d.state.lowSinceMs)
        s = d.state

        // Back below the floor and watched through to 28 min. The blip must not
        // have restarted the clock: the OBSERVED total has to reach persistMs on
        // schedule (a restart would push it ~25 min late), blip time included —
        // holding an open low through a blip is exactly what BUG 2 established.
        repeat(4) {
            now += 4 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.4))
            s = d.state
        }
        assertEquals("28 min into the low, none of it lost to the blip", 28 * MIN, now - T0)
        assertTrue(
            "the sustained clock reached persistMs on schedule",
            d.state.lowObservedMs >= 25 * MIN,
        )
        // …and the alarm itself follows as soon as the persistent cadence opens.
        var sawPersistent = false
        repeat(3) {
            now += 4 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.4))
            s = d.state
            if (d.action == HypoAction.PERSISTENT_ALARM) sawPersistent = true
        }
        assertTrue("the persistent alarm fires, it is not restarted", sawPersistent)
    }

    @Test
    fun `a dead-band PLATEAU is a recovery, and never buzzes the wrist`() {
        // Holding an open low through a blip is right; holding it through a
        // plateau is not. Measured on the device's own anchors before the cap:
        // 21 holds in 8 days, the longest 34 min — 34 min of wrist buzzing at 4.1
        // with nothing on screen, and 34 min folded into "sustained N min".
        var s = HypoAlertState()
        var now = T0

        var d = decideHypoAlert(s, inputs(now, bg = 3.5))
        assertEquals(HypoAction.LOW_ALARM, d.action)
        s = d.state

        // 10 min at 4.0 — inside the cap: the clock survives, the wrist is quiet.
        now += 10 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 4.0))
        assertNotEquals(0L, d.state.lowSinceMs)
        assertFalse("a value above the floor must not buzz the wrist", d.alertActive)
        s = d.state

        // Past the cap (16 min since the last real low) → a genuine recovery.
        now += 6 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 4.0))
        assertEquals(0L, d.state.lowSinceMs)
        assertEquals(0L, d.state.episodeStartMs)
        assertFalse(d.alertActive)
    }

    @Test
    fun `a low after a long-open prediction fires LOW_ALARM, not PERSISTENT (BUG 1)`() {
        // At night the corridor opens the episode as a PREDICTION, long before
        // any real low. When BG finally drops, «now - episodeStart» is already
        // past persistMs, so the very first alarm is PERSISTENT (10-min cadence),
        // skipping LOW_ALARM's urgent 5-min cadence — and "sustained N min" counts
        // from the prediction, a duration the user was never low. Real data:
        // the episode opened while still in range, and the first genuine low
        // came about 58 min later.
        var s = HypoAlertState()
        var now = T0

        // Night, in-range, corridor predicts a dip → gentle predict opens the episode.
        var d = decideHypoAlert(s, inputs(now, bg = 5.1, predicted = true, night = true))
        assertEquals(HypoAction.PREDICT_GENTLE, d.action)
        s = d.state

        // 58 min later — the FIRST genuine low. The user has been low for ZERO minutes.
        now += 58 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.8, night = true))
        assertEquals(
            "a fresh low is LOW_ALARM (5-min cadence), not PERSISTENT",
            HypoAction.LOW_ALARM, d.action,
        )
        // The "sustained N min" text reads lowSinceMs — it must say ~0, not 58.
        assertEquals("sustained-low duration starts at the first real low", now, d.state.lowSinceMs)
    }

    @Test
    fun `a sensor gap is reported as unseen, but still alarms`() {
        // Filed-not-fixed until now: the notifier refuses to judge an anchor older
        // than 10 min and simply RETURNS, leaving this state untouched — so no pass
        // happens during a sensor hole, yet the duration was read as
        // "now - lowSinceMs" and billed the whole hole as low. On a real history a
        // large share of persistent alarms overstated themselves that way, the worst by hours.
        //
        // The two halves of the fix are BOTH pinned here, because they pull in
        // opposite directions: what we PRINT may claim only what was observed,
        // while whether we ALARM still assumes the worst across the hole.
        var s = HypoAlertState()
        var now = T0

        // 20 minutes of genuinely observed low, five minutes per reading.
        var d = decideHypoAlert(s, inputs(now, bg = 3.4))
        assertEquals(HypoAction.LOW_ALARM, d.action)
        s = d.state
        repeat(4) {
            now += 5 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.3))
            s = d.state
        }
        assertEquals("20 min seen, one reading at a time", 20 * MIN, s.lowObservedMs)

        // The sensor drops out for 63 minutes: no passes at all, then one low
        // reading. Wall clock says 83 min low; we watched 20 of them.
        now += 63 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.2))
        assertEquals(
            "the alarm still fires — a hole is not evidence the user recovered",
            HypoAction.PERSISTENT_ALARM, d.action,
        )
        assertTrue(d.alertActive)
        assertEquals("the wall clock says 83 min", 83 * MIN, now - T0)
        assertEquals(
            "'sustained N min' may claim only the 20 min we watched",
            20 * MIN, d.state.lowObservedMs,
        )
        s = d.state

        // Observation resumes and the clock resumes with it — the seen total grows
        // again, one credited reading at a time.
        now += 5 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 3.2))
        assertEquals(25 * MIN, d.state.lowObservedMs)
    }

    @Test
    fun `a dead-band hold credits the time it watched`() {
        // The observed clock must taper, never fall off a cliff. The hold cap and
        // the step cap are both 15 min, so if a hold froze the clock, a hold that
        // ran to its maximum would wipe ALL credit for the low — with the stream
        // never once stopping. Found by review; all-or-nothing rather than a
        // taper, which is what makes it worth pinning even though the replayed
        // pass stream shows it costing only 3 passes.
        var s = HypoAlertState()
        var now = T0

        var d = decideHypoAlert(s, inputs(now, bg = 3.4))
        s = d.state
        repeat(2) {                        // low at 0, 5, 10 — all watched
            now += 5 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.4))
            s = d.state
        }
        repeat(2) {                        // dead band at 15, 20 — still watched
            now += 5 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 4.0))
            s = d.state
        }
        now += 6 * MIN                     // back below the floor at 26
        d = decideHypoAlert(s, inputs(now, bg = 3.4))
        assertEquals(
            "every minute of a continuously watched episode is credited",
            26 * MIN, d.state.lowObservedMs,
        )
    }

    @Test
    fun `a hole must not re-arm the dextrose snooze on a low that already persisted`() {
        // The regression an adversarial review caught in the first cut of the fix:
        // judging persistence on OBSERVED time made a long low look fresh again
        // after a hole, which re-engaged `treated` — and that path returns
        // alertActive=false, i.e. silence INCLUDING the wrist. Replayed on the real
        // pass stream it cost 8 minutes of total silence at 2.90 mmol.
        // Hence: alarms decide on the wall clock.
        var s = HypoAlertState()
        var now = T0

        // 15 min of watched low, then a rescue is logged — the snooze is legitimate
        // here and stays legitimate; it is the HOLE that must not resurrect it.
        var d = decideHypoAlert(s, inputs(now, bg = 3.3))
        s = d.state
        repeat(3) {
            now += 5 * MIN
            d = decideHypoAlert(s, inputs(now, bg = 3.2))
            s = d.state
        }
        val dextroseAt = now + MIN

        // 20-min hole, then still low at 2.9. The rescue is STILL FRESH (19 min <
        // snoozeMs 20), so only persistence can break the snooze — exactly the
        // invariant in this file's header: treatment that didn't work is not silent.
        now += 20 * MIN
        d = decideHypoAlert(s, inputs(now, bg = 2.9, dextroseTs = dextroseAt))
        assertEquals(
            "a low that has persisted past the window alarms through the snooze",
            HypoAction.PERSISTENT_ALARM, d.action,
        )
        assertTrue("and the wrist must not go quiet at 2.9 mmol", d.alertActive)
        assertEquals("while still claiming only what was seen", 15 * MIN, d.state.lowObservedMs)
    }

    @Test
    fun `sensor-suspect anchor gives a gentle SENSOR_CHECK, not a SEVERE artifact alarm`() {
        val s = HypoAlertState()
        // An impossible 0.5 WITHOUT the gate flag is the LOUD artifact alarm...
        assertEquals(HypoAction.ARTIFACT_ALARM, decideHypoAlert(s, inputs(T0, bg = 0.5)).action)
        // ...WITH the gate flag it becomes the gentle verify advisory — and is NOT
        // silent (the wrist still shows something, a real mild low may hide under it).
        val g = decideHypoAlert(s, inputs(T0, bg = 0.5).copy(sensorSuspect = true))
        assertEquals(HypoAction.SENSOR_CHECK, g.action)
        assertTrue(g.alertActive)
    }

    @Test
    fun `sensor-check re-fires gently, not every pass`() {
        var s = HypoAlertState()
        var d = decideHypoAlert(s, inputs(T0, bg = 0.5).copy(sensorSuspect = true))
        assertEquals(HypoAction.SENSOR_CHECK, d.action); s = d.state
        // 5 min later — inside the 10-min gentle cadence → no new advisory, still active.
        d = decideHypoAlert(s, inputs(T0 + 5 * MIN, bg = 0.5).copy(sensorSuspect = true))
        assertEquals(HypoAction.NONE, d.action)
        assertTrue(d.alertActive)
        // 12 min later → re-fires the advisory.
        d = decideHypoAlert(s, inputs(T0 + 12 * MIN, bg = 0.5).copy(sensorSuspect = true))
        assertEquals(HypoAction.SENSOR_CHECK, d.action)
    }

    @Test
    fun `sensorSuspect false leaves the whole cascade byte-identical`() {
        val s = HypoAlertState()
        // Default sensorSuspect=false: a real confirmed low still alarms, a predicted
        // still fires predictive — nothing about the existing cascade moved.
        assertEquals(HypoAction.LOW_ALARM, decideHypoAlert(s, inputs(T0, bg = 3.5)).action)
        assertEquals(HypoAction.PREDICT_ALARM, decideHypoAlert(s, inputs(T0, bg = 4.5, predicted = true)).action)
    }
}
