/** Contract tests for the ISF core — mirror of the stage-0 spec acceptance cases. */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private const val BASE = 1_748_768_400_000L // ms
private val UTC = ZoneId.of("UTC")

/**
 * Synthetic clean correction: flat pre-bolus BG, linear fall to a known end
 * level over 3h, then flat. ISF = (start - end) / dose exactly.
 */
private fun cleanEpisode(
    t0: Long,
    startBg: Double = 10.0,
    endBg: Double = 7.0,
    fromMin: Int = -30,
    toMin: Int = 245,
): List<GlucosePoint> =
    (fromMin..toMin step 5).map { m ->
        val bg = when {
            m <= 0 -> startBg
            m >= 180 -> endBg
            else -> startBg + (endBg - startBg) * m / 180.0
        }
        GlucosePoint(t0 + m * 60_000L, bg)
    }

class IsfDetectionTest {

    @Test
    fun `activity contamination downweights the episode instead of rejecting it`() {
        // Correction DURING a walk: muscle drain adds to the insulin drop, so
        // the ISF reads too STRONG. It used to be REJECTED outright, which cost
        // some of the corrections the user had personally confirmed — and confirmed
        // corrections are the only thing the amplitude may learn from. Kept and
        // flagged; the weight carries the distrust, not the gate.
        val walk = ActivityWindow(BASE - 10 * 60_000L, BASE + 30 * 60_000L, 40)
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 10.0, endBg = 7.0),
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
            activityWindows = listOf(walk),
        )
        assertEquals(1, res.episodes.size)
        assertTrue(res.episodes[0].activityContaminated)
        assertEquals(null, res.rejections[Reject.ACTIVITY_IN_WINDOW])
        // now == t0 so recency is exactly 1.0 and the activity factor is alone.
        assertEquals(ACTIVITY_IN_BOUT_WEIGHT, episodeWeights(res.episodes, BASE)[0], 1e-9)
        // Nocturnal correction 8h after a LONG (150 min) evening walk: the
        // sensitization tail amplifies the drop -> KEPT but flagged for
        // downweight (an active lifestyle must still learn ISF).
        val longWalk = ActivityWindow(BASE - 8L * 3_600_000 - 150 * 60_000L, BASE - 8L * 3_600_000, 150)
        val res2 = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 10.0, endBg = 7.0),
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
            activityWindows = listOf(longWalk),
        )
        assertEquals(1, res2.episodes.size)
        assertTrue(res2.episodes[0].postActivityTail)
        // The tail flag costs the episode most of its vote in the weights.
        val w = episodeWeights(res2.episodes, BASE)
        assertTrue(w[0] < 0.5)
        // The same 8h gap after a SHORT walk (25 min): no long tail -> clean.
        val shortWalk = ActivityWindow(BASE - 8L * 3_600_000 - 25 * 60_000L, BASE - 8L * 3_600_000, 25)
        val res3 = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 10.0, endBg = 7.0),
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
            activityWindows = listOf(shortWalk),
        )
        assertEquals(1, res3.episodes.size)
        assertTrue(!res3.episodes[0].postActivityTail)
    }

    @Test
    fun `clean episode found with exact isf`() {
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 10.0, endBg = 7.0),
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
        )
        assertEquals(1, res.episodes.size)
        assertEquals(0, res.rejections.values.sum())
        val e = res.episodes[0]
        assertEquals(2.0, e.isf, 0.1)  // (10 - 7) / 1.5, spec: ±5%
        assertEquals(10.0, e.bgStart, 1e-9)
        assertTrue(!e.anomaly)
    }

    @Test
    fun `unlogged food rise rejects the correction`() {
        // A correction on a 10 mmol high, but BG climbs to ~12.5 in the first
        // 90 min (unlogged food) before drifting down — no food event logged.
        val readings = (-30..245 step 5).map { m ->
            val bg = when {
                m <= 0 -> 10.0
                m in 5..90 -> 10.0 + 2.5 * (m / 90.0)   // rises to 12.5
                m >= 180 -> 8.0
                else -> 12.5 + (8.0 - 12.5) * (m - 90) / 90.0
            }
            GlucosePoint(BASE + m * 60_000L, bg)
        }
        val res = detectIsfEpisodes(
            readings = readings,
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(),      // the food was NOT logged
            zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.LIKELY_UNLOGGED_FOOD])
    }

    @Test
    fun `detected food in window rejects candidate`() {
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE),
            boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = listOf(BASE + 60 * 60_000L), // meal 1h after bolus
            zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.FOOD_IN_WINDOW])
    }

    @Test
    fun `a correction tag rescues a correction from a DETECTED false meal`() {
        val detectedMeal = listOf(BASE + 60 * 60_000L) // detector's false rise near the shot
        val trust = IsfConfig(trustCorrectionTag = true)
        // Untagged: the detected meal still rejects it (unchanged behaviour).
        val untagged = detectIsfEpisodes(
            readings = cleanEpisode(BASE), boluses = listOf(BolusPoint(BASE, 1.5)),
            foodOnsetsMs = emptyList(), detectedFoodOnsetsMs = detectedMeal, cfg = trust, zone = UTC,
        )
        assertEquals(0, untagged.episodes.size)
        assertEquals(1, untagged.rejections[Reject.FOOD_IN_WINDOW])
        // Tagged "correction": the heuristic meal is overridden → episode kept.
        val tagged = detectIsfEpisodes(
            readings = cleanEpisode(BASE), boluses = listOf(BolusPoint(BASE, 1.5, "коррекция")),
            foodOnsetsMs = emptyList(), detectedFoodOnsetsMs = detectedMeal, cfg = trust, zone = UTC,
        )
        assertEquals(1, tagged.episodes.size)
        assertEquals(2.0, tagged.episodes[0].isf, 0.1)
    }

    @Test
    fun `the tag never overrides LOGGED food or an objective rise`() {
        val trust = IsfConfig(trustCorrectionTag = true)
        // Logged food (grams note) is ground truth: the tag must NOT rescue it.
        val loggedFood = detectIsfEpisodes(
            readings = cleanEpisode(BASE), boluses = listOf(BolusPoint(BASE, 1.5, "коррекция")),
            foodOnsetsMs = listOf(BASE + 60 * 60_000L), cfg = trust, zone = UTC,
        )
        assertEquals(0, loggedFood.episodes.size)
        assertEquals(1, loggedFood.rejections[Reject.FOOD_IN_WINDOW])
        // A tagged shot with a real post-bolus rise (unlogged food) is still
        // rejected by the objective guard — a tag can't beat the physics.
        val rising = (-30..245 step 5).map { m ->
            val bg = when {
                m <= 0 -> 10.0
                m in 5..90 -> 10.0 + 2.5 * (m / 90.0)
                m >= 180 -> 8.0
                else -> 12.5 + (8.0 - 12.5) * (m - 90) / 90.0
            }
            GlucosePoint(BASE + m * 60_000L, bg)
        }
        val objectiveRise = detectIsfEpisodes(
            readings = rising, boluses = listOf(BolusPoint(BASE, 1.5, "коррекция")),
            foodOnsetsMs = emptyList(), detectedFoodOnsetsMs = listOf(BASE + 60 * 60_000L),
            cfg = trust, zone = UTC,
        )
        assertEquals(0, objectiveRise.episodes.size)
        assertEquals(1, objectiveRise.rejections[Reject.LIKELY_UNLOGGED_FOOD])
    }

    @Test
    fun `the tag does nothing unless trustCorrectionTag is enabled`() {
        // Default config (trustCorrectionTag=false): tag is inert, detected food rejects.
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE), boluses = listOf(BolusPoint(BASE, 1.5, "коррекция")),
            foodOnsetsMs = emptyList(), detectedFoodOnsetsMs = listOf(BASE + 60 * 60_000L), zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.FOOD_IN_WINDOW])
    }

    @Test
    fun `other bolus in window rejects both`() {
        // 90 min apart: beyond the cluster gap, inside the isolation window.
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, toMin = 360),
            boluses = listOf(BolusPoint(BASE, 1.5), BolusPoint(BASE + 90 * 60_000L, 2.0)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(2, res.rejections[Reject.OTHER_BOLUS_IN_WINDOW])
    }

    @Test
    fun `split doses within 30 min merge into one candidate`() {
        // Pen habit: 2.0U then 1.5U twenty minutes later — one correction.
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 10.0, endBg = 6.5),
            boluses = listOf(BolusPoint(BASE, 2.0), BolusPoint(BASE + 20 * 60_000L, 1.5)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
        )
        assertEquals(1, res.nCandidates)
        assertEquals(1, res.episodes.size)
        assertEquals(3.5, res.episodes[0].dose, 1e-9)
        assertEquals(1.0, res.episodes[0].isf, 0.05)  // (10 - 6.5) / 3.5
    }

    @Test
    fun `chained split doses merge transitively`() {
        // 3 shots, each 20 min after the previous: one 4.5U cluster.
        val boluses = listOf(
            BolusPoint(BASE, 2.0),
            BolusPoint(BASE + 20 * 60_000L, 1.5),
            BolusPoint(BASE + 40 * 60_000L, 1.0),
        )
        val clustered = clusterBoluses(boluses, 30.0)
        assertEquals(1, clustered.size)
        assertEquals(4.5, clustered[0].units, 1e-9)
        assertEquals(BASE, clustered[0].tsMs)
    }

    @Test
    fun `hypo in window rejects candidate`() {
        val readings = cleanEpisode(BASE, startBg = 9.0, endBg = 3.0) // dips below 3.9
        val res = detectIsfEpisodes(
            readings, listOf(BolusPoint(BASE, 2.0)), emptyList(), zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.HYPO_IN_WINDOW])
    }

    @Test
    fun `low start bg rejects candidate`() {
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 6.0, endBg = 5.0),
            boluses = listOf(BolusPoint(BASE, 1.0)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.START_BG_TOO_LOW])
    }

    @Test
    fun `small dose and sparse cgm are rejected with distinct reasons`() {
        // Dose below 0.5U.
        var res = detectIsfEpisodes(
            cleanEpisode(BASE), listOf(BolusPoint(BASE, 0.3)), emptyList(), zone = UTC,
        )
        assertEquals(1, res.rejections[Reject.DOSE_TOO_SMALL])

        // Sparse CGM: every 30 min -> coverage < 80%.
        val sparse = (-30..245 step 30).map { GlucosePoint(BASE + it * 60_000L, 10.0) }
        res = detectIsfEpisodes(sparse, listOf(BolusPoint(BASE, 1.0)), emptyList(), zone = UTC)
        assertEquals(1, res.rejections[Reject.INSUFFICIENT_CGM_COVERAGE])
    }

    @Test
    fun `rising bg on correction is an anomaly not silently dropped`() {
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE, startBg = 9.0, endBg = 11.0),
            boluses = listOf(BolusPoint(BASE, 1.0)),
            foodOnsetsMs = emptyList(),
            zone = UTC,
        )
        assertEquals(1, res.episodes.size)
        assertTrue(res.episodes[0].anomaly)
    }

    @Test
    fun `correction tag cannot override detected food`() {
        val res = detectIsfEpisodes(
            readings = cleanEpisode(BASE),
            boluses = listOf(BolusPoint(BASE, 1.0, purpose = "коррекция")),
            foodOnsetsMs = listOf(BASE + 30 * 60_000L),
            cfg = IsfConfig(trustCorrectionTag = true),
            zone = UTC,
        )
        assertEquals(0, res.episodes.size)
        assertEquals(1, res.rejections[Reject.FOOD_IN_WINDOW])
    }

    @Test
    fun `completed isolated fall is offered for correction confirmation`() {
        val now = BASE + 4L * 3_600_000
        val readings = (0..48).map { i ->
            val t = BASE + i * 5L * 60_000
            GlucosePoint(t, 10.0 - minOf(i / 36.0, 1.0) * 2.0)
        }
        val bolus = BolusPoint(BASE, 1.0)
        assertEquals(
            bolus,
            correctionConfirmationCandidate(now, readings, listOf(bolus), emptyList()),
        )
        assertNull(
            correctionConfirmationCandidate(
                now, readings, listOf(bolus), listOf(BASE + 30L * 60_000),
            ),
        )
    }

    @Test
    fun `rejections plus episodes sum to candidates`() {
        val readings = cleanEpisode(BASE) + cleanEpisode(BASE + 12 * 3_600_000L)
        val boluses = listOf(
            BolusPoint(BASE, 1.5),                       // clean
            BolusPoint(BASE + 12 * 3_600_000L, 0.2),     // dose too small
        )
        val res = detectIsfEpisodes(readings, boluses, emptyList(), zone = UTC)
        assertEquals(res.nCandidates, res.episodes.size + res.rejections.values.sum())
    }
}

class IsfAggregateTest {

    private fun episode(isf: Double, tod: TodBucket = TodBucket.DAY) =
        IsfEpisode(BASE, 1.0, 10.0, 10.0 - isf, isf, isf <= 0, tod)

    @Test
    fun `fewer than 5 episodes is insufficient`() {
        val agg = aggregateIsf(List(4) { episode(2.0) })
        assertEquals(Confidence.INSUFFICIENT, agg.confidence)
        assertNull(agg.median)
    }

    @Test
    fun `5 to 14 episodes is preliminary with median and iqr`() {
        val agg = aggregateIsf(List(5) { episode(2.0 + it * 0.1) })
        assertEquals(Confidence.PRELIMINARY, agg.confidence)
        assertEquals(2.2, agg.median!!, 1e-9)
        assertTrue(agg.iqr!! > 0)
    }

    @Test
    fun `15 episodes is stable`() {
        assertEquals(Confidence.STABLE, aggregateIsf(List(15) { episode(2.0) }).confidence)
    }

    @Test
    fun `anomalies are excluded from aggregate but counted`() {
        val agg = aggregateIsf(List(5) { episode(2.0) } + listOf(episode(-1.0)))
        assertEquals(5, agg.nValid)
        assertEquals(1, agg.nAnomalies)
        assertEquals(2.0, agg.median!!, 1e-9)
    }

    @Test
    fun `tod breakdown has independent confidence`() {
        val eps = List(6) { episode(2.0, TodBucket.MORNING) } + List(2) { episode(3.0, TodBucket.EVENING) }
        val byTod = aggregateByTod(eps)
        assertEquals(Confidence.PRELIMINARY, byTod[TodBucket.MORNING]!!.confidence)
        assertEquals(Confidence.INSUFFICIENT, byTod[TodBucket.EVENING]!!.confidence)
        assertEquals(Confidence.INSUFFICIENT, byTod[TodBucket.NIGHT]!!.confidence)
    }

    @Test
    fun `percentile matches numpy linear interpolation`() {
        val arr = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.75, percentile(arr, 25.0), 1e-9)
        assertEquals(2.5, percentile(arr, 50.0), 1e-9)
        assertEquals(3.25, percentile(arr, 75.0), 1e-9)
    }
}
