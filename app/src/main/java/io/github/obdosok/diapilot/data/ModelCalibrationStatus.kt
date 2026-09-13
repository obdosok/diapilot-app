package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.physio.ProfileArmV1
import io.github.obdosok.diapilot.Edition

/**
 * WHOSE NUMBERS THE MODEL IS RUNNING ON — the one question the first-run work
 * exists to make answerable on screen.
 *
 * Ordered from least to most the user's own. The status is a fact about the
 * INSULIN side of the model (ISF and the action shape), because that is the
 * side the bundled example person supplied silently and the side the
 * forecast, the predictive alert and the watch hint are most sensitive to.
 * Carb sensitivity has its own chain (manual → weight → constant, audit M4)
 * and is reported by the card as a line, not as a state.
 */
enum class ModelCalibrationStatus {
    /**
     * Nothing entered: the bundled synthetic person's ISF and insulin shape.
     * In this state no forecast pass runs — see [ModelCalibration.forecastAllowed].
     */
    EXAMPLE_PERSON,

    /** The first-run pages (or the Settings card) wrote an ISF and a shape. */
    HAND_ENTERED,

    /**
     * The person's own doses produced a usable curve on the clean arm. The
     * hand-entered shape, when one is set, still WINS by the resolver's
     * precedence — this state says the measurement exists, not that it is the
     * one applied; the Settings card reports the divergence between the two.
     */
    MEASURED,
}

object ModelCalibration {

    /**
     * What the card prints beside the state. Landmarks are the hand tier's
     * own values (null when a field was left blank); the dose counts describe
     * the measured curve when there is one.
     */
    data class Report(
        val status: ModelCalibrationStatus,
        val isfMmolPerU: Double?,
        val onsetMin: Double?,
        val peakMin: Double?,
        val tailMin: Double?,
        val measuredDoses: Int,
        val measuredDays: Int,
        val weightKg: Double?,
        val carbSensOverrideMmolPerG: Double?,
    )

    /**
     * THE ONE PREDICATE EVERY PROSPECTIVE SURFACE ASKS. It is the edition gate
     * the surfaces already had — `Edition.prospective` — with the calibration
     * fact folded in, so the same call that closes the store edition's line
     * closes a fresh oss install's. `Forecaster` itself asks only the
     * edition-neutral half ([Onboarding.forecastPermitted]); this one exists
     * for the callers that would otherwise fetch a model, build an artifact or
     * compose a panel for a pass that is going to return null.
     */
    fun forecastAllowed(context: Context, store: CollectorStore? = null): Boolean =
        Edition.prospective && Onboarding.forecastPermitted(context, store)

    /**
     * Runs the insulin-profile reader on the store (cached per data revision,
     * but a SQLite read all the same) — call it off the main thread.
     *
     * MEASURED is the profile reader's own verdict, not a second opinion:
     * `PersonalInsulinCurveV1.ready` (enough doses on enough distinct days)
     * AND the clean arm in force, which `SegmentLandmarkReaderV1.assemble`
     * switches to once every conditioned landmark has
     * `MIN_CONDITIONED_SAMPLES` (8) food-free samples. Before that the curve
     * is "from all doses (too few clean ones yet)" and the card keeps saying
     * hand-entered, which is what is applied.
     */
    fun report(context: Context, store: CollectorStore?, nowMs: Long = System.currentTimeMillis()): Report {
        val manual = ManualInsulinRuntime.params(context)
        val entered = Onboarding.forecastPermitted(context, store) && manual.isfMmolPerU != null
        val profile = (store as? SqliteCollectorStore)?.let { sq ->
            runCatching { InsulinProfileRuntime.state(store, sq.readableDatabase, nowMs) }.getOrNull()
        }
        val curve = profile?.curve?.takeIf { it.ready }
        val cleanArm = profile?.profile?.arm == ProfileArmV1.FOOD_FREE
        val status = when {
            entered && curve != null && cleanArm -> ModelCalibrationStatus.MEASURED
            entered -> ModelCalibrationStatus.HAND_ENTERED
            else -> ModelCalibrationStatus.EXAMPLE_PERSON
        }
        return Report(
            status = status,
            isfMmolPerU = manual.isfMmolPerU,
            onsetMin = manual.onsetMin,
            peakMin = manual.peakMin,
            tailMin = manual.tailMin,
            measuredDoses = curve?.observations ?: 0,
            measuredDays = curve?.independentDays ?: 0,
            weightKg = Settings.weightKg(context),
            carbSensOverrideMmolPerG = Settings.storedCarbSensOverrideMmolPerG(context),
        )
    }

    fun status(context: Context, store: CollectorStore?, nowMs: Long = System.currentTimeMillis()): ModelCalibrationStatus =
        report(context, store, nowMs).status
}
