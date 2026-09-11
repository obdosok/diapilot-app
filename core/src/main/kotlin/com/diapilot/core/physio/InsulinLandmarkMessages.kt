package com.diapilot.core.physio

// Language-neutral messages about the insulin action curve. The profile reader,
// the domain coercion and the ordering check report these; the app renders
// them in the UI language (`com.example.diapilot.i18n.PhysioText`).

/** The named points of an insulin action curve. */
enum class InsulinLandmark {
    /** Start of action. */
    ONSET,
    /** Where the glucose line visibly turns down. */
    VISIBLE_FALL,
    /** Peak action rate. */
    PEAK,
    /** End of the active (plateau) phase. */
    ACTIVE_END,
    /** Where the action rate eases off. */
    SLOWDOWN,
    /** End of action (the tail). */
    TAIL_END,
}

/** A landmark moved into the model's domain: [landmark] went from [fromMin] to [toMin]. */
data class CoercedLandmark(val landmark: InsulinLandmark, val fromMin: Double, val toMin: Double)

/** Why one dose gave no landmark at all. Equal values count as one reason. */
sealed interface LandmarkRefusal {
    /** The window is shorter than the onset horizon. */
    data object WindowTooShort : LandmarkRefusal

    /** Too few points before or after the dose to read the line. */
    data object LineUnreadable : LandmarkRefusal

    /** The line never breaks: compensated, or the dose did not act. */
    data object NoBreak : LandmarkRefusal

    /** Landmarks were read and none passed its checks; [causes] name each check that failed. */
    data class AllRejected(val causes: List<LandmarkRejection>) : LandmarkRefusal
}

/** One landmark's failed check. */
data class LandmarkRejection(val landmark: InsulinLandmark, val kind: Kind) {
    enum class Kind {
        /** Out of order with its neighbours, or out of its bounds. */
        OUT_OF_ORDER,
        /** Beyond the observed horizon. */
        BEYOND_HORIZON,
        /** The onset is outside its own range. */
        ONSET_OUT_OF_RANGE,
        /** The onset was dropped because of the peak (less than 10 min apart). */
        ONSET_DROPPED_FOR_PEAK,
    }
}

/**
 * The pooled medians do not increase: [later] at [laterMin] is not after
 * [earlier] at [earlierMin] — the medians came from different injections.
 */
data class OrderingConflict(
    val later: InsulinLandmark,
    val laterMin: Double,
    val earlier: InsulinLandmark,
    val earlierMin: Double,
)
