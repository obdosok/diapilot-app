package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.physio.PhysioAutoFitV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import com.example.diapilot.R
import com.example.diapilot.i18n.localized

/**
 * THE AUTO-FIT SURVIVES LEAVING THE SCREEN.
 *
 * Reported by the user: "pressed Form, switched to another
 * tab, came back — and the Form button is active again, and I have no idea
 * whether it's still computing or not."
 *
 * That was not a missing spinner. The fit lived in `remember` inside the
 * composable and ran in `rememberCoroutineScope()`, so leaving the tab disposed
 * the composition and CANCELLED the work. The button came back enabled because
 * there was genuinely nothing running any more — the screen was telling the
 * truth about a state it had silently created. A progress bar bolted onto that
 * would have been a lie with an animation.
 *
 * So the run lives here instead: a process-scoped holder with its own scope, a
 * [StateFlow] any composition can observe, and a single-run guard. Leaving the
 * tab, backgrounding the app, or rotating now do nothing to it; coming back
 * re-attaches to whatever is in flight.
 *
 * **Concurrency, and why it is here rather than in the fitter.** Ten episodes
 * fitted one after another is roughly a three-minute wait on a phone. The
 * episodes are independent, so they run concurrently — but the MEDIAN is still
 * assembled by `PhysioAutoFitV1.medianOf`, never by a copy written here. The
 * fitter owns the mathematics; this owns the threads. The permit count is
 * deliberately below the core count: this runs while the user is using the app, and a
 * fit that makes the chart stutter would be its own bug report.
 */
object PhysioAutoFitController {

    sealed interface State {
        data object Idle : State

        data class Running(
            val metric: PhysioAutoFitV1.Metric,
            val done: Int,
            val total: Int,
        ) : State {
            val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
        }

        /**
         * [before] is the SAME episodes scored with what is applied today, so
         * «better» is a measured contrast rather than an assertion. Without it
         * the card would report a loss value with nothing to compare it to,
         * which is how a fit that made things worse still looks like progress.
         */
        data class Done(
            val metric: PhysioAutoFitV1.Metric,
            val batch: PhysioAutoFitV1.Batch,
            val start: PhysioAutoFitV1.Knobs,
            val before: List<PhysioAutoFitV1.Score>,
            /**
             * THE MEDIAN SET SCORED ON THE SAME EPISODES — not each episode's
             * own optimum.
             *
             * This used to read `batch.fits.map { it.score }`, which is the
             * score each episode reached with ITS OWN ten knobs. The button
             * offers ONE median set, whose quality was never computed at all. So
             * "shape 5.7 → 2.7" could mean «ten different models each fit their
             * own curve» while the thing being applied was worse than the
             * baseline — and nothing on the card would have said so.
             *
             * It is still IN SAMPLE: the median comes from these episodes.
             * Measured on the bench the same day, that flatters by about five
             * points (80% of episodes improved in sample against 75% out), so
             * the number here reads as an upper bound, not as a forward promise.
             */
            val after: List<PhysioAutoFitV1.Score>,
            /** What the search was allowed to move, in the user's words. */
            val corridor: String,
        ) : State {
            private fun med(v: List<Double>): Double {
                if (v.isEmpty()) return Double.NaN
                val s = v.sorted()
                return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            }

            val shapeBefore: Double get() = med(before.map { it.shape })
            val shapeAfter: Double get() = med(after.map { it.shape })
            val biasBefore: Double get() = med(before.map { kotlin.math.abs(it.bias) })
            val biasAfter: Double get() = med(after.map { kotlin.math.abs(it.bias) })
        }

        data class Failed(val note: String) : State
    }

    /**
     * AXES THE PHONE MUST NOT FIT YET, because it cannot APPLY them.
     *
     * `foodAmp` and `carbTimeScale` were added to the shared fitter
     * for the walk-forward stand. [PhysioTuning] does not carry them to the
     * model, so a fit that moved them would print a number on the card that
     * changes nothing when applied — a suggestion the screen cannot keep.
     *
     * Locking them here keeps the phone doing exactly what it did yesterday.
     * Remove this the same day the settings screen grows the two fields, not
     * before: an axis that is fitted but not applied is worse than an absent
     * one, because it looks like knowledge.
     */
    private val PHONE_LOCKED = setOf("foodAmp", "carbTimeScale")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts a fit, or does nothing if one is already in flight.
     *
     * Returning silently on a double tap is deliberate: the alternative is
     * cancelling the run in progress, and a second tap far more often means
     * «did that register?» than «throw away the two minutes you just spent».
     */
    fun start(
        context: Context,
        metric: PhysioAutoFitV1.Metric,
        limit: Int = 10,
        locked: Set<String> = emptySet(),
        /**
         * The knobs ON SCREEN, which are not always the knobs APPLIED.
         *
         * The start used to come from `PhysioTuning` — what is committed — so a
         * locked axis pinned the committed value while the user was looking at a
         * different number in the field. This was caught immediately: locks held the
         * shipped values and the ramp unchanged, when the fields (and
         * the user's intent) said otherwise. A lock has to mean «keep what I see».
         */
        startOverride: PhysioAutoFitV1.Knobs? = null,
    ) {
        if (isRunning) return
        val app = context.applicationContext
        job = scope.launch {
            // A THROW USED TO LEAVE THE CARD SPINNING.
            //
            // `isRunning` reads `job.isActive`, which goes false on failure, so
            // the guards released correctly — but `_state` stayed `Running`, and
            // the card renders from that. The result was a progress bar that
            // never finished and never said why, on a screen whose whole job is
            // to report a measurement.
            try {
                runFit(app, metric, limit, locked, startOverride)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = State.Failed(t.message ?: t::class.java.simpleName)
            }
        }
    }

    private suspend fun runFit(
        app: Context,
        metric: PhysioAutoFitV1.Metric,
        limit: Int,
        locked: Set<String>,
        startOverride: PhysioAutoFitV1.Knobs?,
    ) = kotlinx.coroutines.coroutineScope {
            _state.value = State.Running(metric, 0, 0)
            val base = HybridModelStore.untunedModel(app)
            val start = startOverride ?: PhysioAutoFitRuntime.startingKnobs(app)
            if (base == null || start == null) {
                _state.value = State.Failed(app.localized().getString(R.string.physio_auto_fit_controller_no_model))
                return@coroutineScope
            }
            val episodes = PhysioAutoFitRuntime.buildEpisodes(Stores.get(app), limit)
            if (episodes.size < 3) {
                _state.value = State.Failed(
                    app.localized().resources.getQuantityString(
                        R.plurals.physio_auto_fit_controller_too_few_episodes, episodes.size, episodes.size,
                    ),
                )
                return@coroutineScope
            }
            // The horizon must cover the grid or every candidate scores null and
            // the fit quietly returns its starting point as «the answer».
            val scored = base.copy(
                runtime = base.runtime.copy(
                    horizonMin = maxOf(base.runtime.horizonMin, PhysioAutoFitRuntime.GRID.last()),
                ),
            )
            // MEASURED TIMINGS BOUND THE SEARCH. Absent a measurement the wide
            // table stands — a corridor around a factory default would be a
            // safety claim with nothing behind it.
            val safety = PhysioAutoFitRuntime.safetyBounds(app)
            val bounds = safety?.first ?: PhysioAutoFitV1.PLAUSIBLE_PHYSIOLOGY
            val corridor = safety?.second
                ?: app.localized().getString(R.string.physio_auto_fit_controller_corridor_unmeasured)
            _state.value = State.Running(metric, 0, episodes.size)
            val finished = AtomicInteger(0)
            val gate = Semaphore(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
            val fits = episodes.map { e ->
                async {
                    val f = gate.withPermit {
                        PhysioAutoFitV1.fitOne(
                            scored, e, start, metric, PhysioAutoFitRuntime.GRID,
                            locked = locked + PHONE_LOCKED, minPoints = 6, bounds = bounds,
                        )
                    }
                    _state.value = State.Running(metric, finished.incrementAndGet(), episodes.size)
                    f?.let { e to it }
                }
            }.awaitAll().filterNotNull()
            // The baseline is scored on the EPISODES THAT SURVIVED the fit, not
            // on all of them: comparing a median over ten stretches with a
            // median over the eight that scored is a comparison of two
            // different corpora wearing one label.
            val median = PhysioAutoFitV1.medianOf(fits.map { it.second }) ?: start
            // BOTH ARMS ARE ONE KNOB SET OVER THE SAME EPISODES. Comparing a
            // single baseline against per-episode optima compares a model
            // against a family of models, which is not a comparison at all.
            val before = fits.mapNotNull {
                PhysioAutoFitV1.score(
                    scored, start, it.first, PhysioAutoFitRuntime.GRID, minPoints = 6,
                )
            }
            val after = fits.mapNotNull {
                PhysioAutoFitV1.score(
                    scored, median, it.first, PhysioAutoFitRuntime.GRID, minPoints = 6,
                )
            }
            _state.value = if (fits.isEmpty()) {
                State.Failed(app.localized().getString(R.string.physio_auto_fit_controller_no_episode_scored))
            } else {
                State.Done(
                    metric,
                    PhysioAutoFitV1.Batch(fits, median),
                    start,
                    before,
                    after,
                    corridor,
                )
            }
    }

    /** Clears a finished result so the card goes back to its resting state. */
    fun clear() {
        if (isRunning) return
        _state.value = State.Idle
    }
}
