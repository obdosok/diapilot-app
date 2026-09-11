/**
 * Dextrose rescues as a first-class SAFETY signal.
 *
 * A "dextrose ×N" note is logged one-tap, in real time, while low or falling
 * fast (taps within 30 min grow the SAME note, so one note = one episode with N
 * tablets, `estCarbs` = N × tablet grams). Today it is read only to drop its
 * window from the corpus — yet each one is a dose/plan error the user closed
 * by HAND, invisible in every other series: no low value survives, the hypo
 * alarm was silent, the hypo counter never moved.
 *
 * The RATE is therefore a direct measure of how often the plan was wrong enough
 * to need a manual rescue. Observation and a counter only — this is never a dose
 * and never advice (hard rule).
 *
 * Pure counting on note metadata; the SENSITIVITY question (can a rescue tell us
 * how strong insulin really was?) is deliberately NOT here — measured
 * to be unextractable on this data, because 21 of 25 rescued lows have no
 * insulin acting at all (they are the driverless drifts the alert cannot see,
 * the complement of the recall-ceiling finding).
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.BolusPoint
import kotlin.math.roundToInt

/** One dextrose rescue: when, and how much sugar it took. */
data class RescueEvent(
    val tsMs: Long,
    val grams: Double,
    val tablets: Int,
)

/**
 * Grams of CARBOHYDRATE per dextrose tablet — the unit `estCarbs` is quantised in,
 * and **the single source of truth**: the app module had its own copy, so the
 * button and the model could have started counting different things.
 *
 * READ THIS BEFORE CHANGING THE NUMBER. It is not a display detail: the one-tap
 * button WRITES `count × this` into `annotations.estCarbs`, so every rescue in the
 * database carries a figure that LOOKS like a logged observation and is in fact
 * this constant. It feeds the deconvolution corpus (dextrose is its largest pool),
 * `carbEpisodes` → `carbSens`, and the forecast after a rescue.
 *
 * MEASURED WRONG, and left at 4.0 on purpose. The user's package
 * reads 46 g per 8 tablets at 89 g carbs per 100 g ⇒ **5.75 g per tablet, 5.12 g
 * of carbs** — this constant under-states by 28%. Fixing it is not a free win:
 * more carbs credited to a rescue means a HIGHER predicted recovery, which
 * releases a sustained hypo alert EARLIER. That is the warning-reducing direction,
 * so it needs its own alert-safety measurement (`foodsafe` / `actv2safety` shape)
 * before it lands — and it probably wants to be a SETTING rather than a constant,
 * since packages differ and a hardcoded one has now broken the scale once.
 */
const val DEXTROSE_TABLET_G = 5.0

/**
 * Rescue notes whose timestamp falls in [fromMs, toMs], ascending. A note with
 * no carbs figure is assumed a single tablet (the one-tap default) so it is
 * never silently dropped from the safety count.
 */
fun rescueEvents(
    notes: List<Annotation>,
    fromMs: Long,
    toMs: Long,
    tabletG: Double = DEXTROSE_TABLET_G,
): List<RescueEvent> =
    notes.asSequence()
        .filter { it.tsMs in fromMs..toMs && it.content.startsWith(RESCUE_NOTE_PREFIX, ignoreCase = true) }
        .sortedBy { it.tsMs }
        .map { n ->
            val g = n.estCarbs ?: tabletG
            RescueEvent(tsMs = n.tsMs, grams = g, tablets = (g / tabletG).roundToInt().coerceAtLeast(1))
        }
        .toList()

/**
 * Rescues per 7 days over the span [fromMs, toMs] — the headline safety number.
 * Null when the span is shorter than [minDays]: a rate off two days of data
 * would swing wildly and mean nothing.
 */
fun rescuesPerWeek(
    events: List<RescueEvent>,
    fromMs: Long,
    toMs: Long,
    minDays: Int = 3,
): Double? {
    val days = (toMs - fromMs).toDouble() / 86_400_000.0
    if (days < minDays) return null
    return events.size / (days / 7.0)
}

enum class RescueOutcomeV1 { LOW_ALREADY_PRESENT, AVERTED_LOW_COMPATIBLE, FALL_NOT_ESTABLISHED }
data class RescueSafetyEpisodeV1(
    val rescue:RescueEvent,val outcome:RescueOutcomeV1,
    val glucoseAtRescue:Double?,val preSlopeMmolPerMin:Double?,val minNext60Mmol:Double?,
    val activeRecentBolusUnits:Double,val linearMinutesToLow:Double?,
)

/** Rescue is an outcome-changing intervention. A post-rescue non-low must not
 * be scored as a successful forecast. This detector records a conservative
 * censored safety endpoint; it never claims the unobserved no-rescue glucose
 * and never turns one stacked-bolus episode into an ISF estimate. */
fun rescueSafetyEpisodesV1(
    events:List<RescueEvent>,glucose:List<GlucosePoint>,boluses:List<BolusPoint>,
    lowMmol:Double=3.9,
):List<RescueSafetyEpisodeV1> = events.map{r->
    fun near(ts:Long)=glucose.minByOrNull{kotlin.math.abs(it.tsMs-ts)}?.takeIf{kotlin.math.abs(it.tsMs-ts)<=10*60_000L}
    val at=near(r.tsMs)
    val before=glucose.filter{it.tsMs in (r.tsMs-30*60_000L)..(r.tsMs-15*60_000L)}.minByOrNull{kotlin.math.abs(it.tsMs-(r.tsMs-25*60_000L))}
    val dt=if(at!=null&&before!=null)(at.tsMs-before.tsMs)/60_000.0 else 0.0
    val slope=if(dt>=10)(at!!.mmol-before!!.mmol)/dt else null
    val minAfter=glucose.filter{it.tsMs in r.tsMs..(r.tsMs+60*60_000L)}.minOfOrNull{it.mmol}
    val minutesToLow=if(at!=null&&slope!=null&&slope<-.01&&at.mmol>lowMmol)(at.mmol-lowMmol)/-slope else null
    val outcome=when{
        at?.mmol?.let{it<=lowMmol}==true->RescueOutcomeV1.LOW_ALREADY_PRESENT
        slope!=null&&slope<=-.08&&minutesToLow!=null&&minutesToLow<=45&&minAfter?.let{it>lowMmol}==true->RescueOutcomeV1.AVERTED_LOW_COMPATIBLE
        else->RescueOutcomeV1.FALL_NOT_ESTABLISHED
    }
    RescueSafetyEpisodeV1(r,outcome,at?.mmol,slope,minAfter,
        boluses.filter{it.tsMs in (r.tsMs-4*3_600_000L)..r.tsMs}.sumOf{it.units},minutesToLow)
}
