package com.example.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.BackgroundForce
import com.diapilot.core.analysis.DishRecognitionV1
import com.diapilot.core.analysis.MarkKind
import com.diapilot.core.analysis.SuggestTrigger
import com.diapilot.core.analysis.SysLabels
import com.example.diapilot.R

/**
 * Renders :core's food values: the dish question, meal marks, suggestion
 * triggers, background forces, system meal labels, the portion word.
 */
object FoodText {
    /** "Is this your smoothie? (28 times, usually 22 g)". */
    fun dishQuestion(context: Context, q: DishRecognitionV1.DishQuestion): String {
        val res = context.localized()
        val facts = buildList {
            q.intakes?.let { add(res.resources.getQuantityString(R.plurals.dish_question_times, it, it)) }
            q.typicalCarbsG?.let { add(res.getString(R.string.dish_question_usual_carbs, it)) }
        }
        return if (facts.isEmpty()) res.getString(R.string.dish_question, q.title)
        else res.getString(R.string.dish_question_with_facts, q.title, facts.joinToString(", "))
    }

    /** The meal-mark button text; the stored value is [MarkKind.name]. */
    fun markKind(context: Context, k: MarkKind): String = context.localized().getString(
        when (k) {
            MarkKind.GRAMS_MORE -> R.string.mark_kind_grams_more
            MarkKind.GRAMS_LESS -> R.string.mark_kind_grams_less
            MarkKind.RESCUE -> R.string.mark_kind_rescue
            MarkKind.SENSOR_LIED -> R.string.mark_kind_sensor_lied
            MarkKind.ATE_MORE -> R.string.mark_kind_ate_more
            MarkKind.CONTEXT -> R.string.mark_kind_context
            MarkKind.MODEL_WRONG -> R.string.mark_kind_model_wrong
            MarkKind.UNKNOWN -> R.string.mark_kind_unknown
        },
    )

    /** "injection" / "glucose rise" — what made the app ask about food. */
    fun suggestTrigger(context: Context, t: SuggestTrigger): String = context.localized().getString(
        when (t) {
            SuggestTrigger.DOSE -> R.string.suggest_trigger_dose
            SuggestTrigger.DETECTED_RISE -> R.string.suggest_trigger_detected_rise
        },
    )

    /** "insulin+food", in the UI language. */
    fun background(context: Context, forces: List<BackgroundForce>): String {
        val res = context.localized()
        return forces.joinToString("+") {
            res.getString(
                when (it) {
                    BackgroundForce.INSULIN -> R.string.background_force_insulin
                    BackgroundForce.FOOD -> R.string.background_force_food
                },
            )
        }
    }

    /**
     * A meal label for display. The four [SysLabels] are STORED Russian tokens
     * ([SysLabels.DAWN], ...) and are shown in the UI language; any other label
     * is the user's own text and comes back unchanged.
     */
    fun mealLabel(context: Context, label: String): String {
        val id = when (label) {
            SysLabels.CONTINUATION -> R.string.sys_label_continuation
            SysLabels.DAWN -> R.string.sys_label_dawn
            SysLabels.SPORT -> R.string.sys_label_sport
            SysLabels.UNKNOWN -> R.string.sys_label_unknown
            else -> return label
        }
        return context.localized().getString(id)
    }

    /** The word written into a note by half/double portion ("portion"), in the UI language. */
    fun portionWord(context: Context): String = context.localized().getString(R.string.food_portion_word)
}
