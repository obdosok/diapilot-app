/**
 * Hard gate between the command LLM and the database. An LLM that can write
 * insulin into the store without a validator is not a bug risk, it is a mine:
 * "10 units" misheard as "100 units" would flow into IOB, the forecast and the hypo
 * alert. Every parsed value passes through here BEFORE the confirm card is
 * shown; on violation nothing is written — the user sees what was recognized,
 * why it is blocked, and enters the value by hand. The guard never "guesses
 * what the LLM meant".
 *
 * The dose ceilings are PERSONAL technical fuses (PersonalParams, [n=1]) —
 * deliberately above the user's own habitual doses and far below absurdity.
 * They are not a medical norm and not dosing advice.
 */
package com.diapilot.core.analysis

/** Actions the command parser is allowed to emit. */
val COMMAND_ACTIONS = setOf("food", "meter", "bolus", "basal", "activity", "dextrose")

/** Bolus purposes the UI knows how to render and train on. */
val BOLUS_PURPOSES = setOf("коррекция", "на еду", "докол", "воздух")

/**
 * Null when the command's values are safe to write; otherwise a short
 * human-readable reason (Russian, shown verbatim under the composer).
 */
fun validateCommandValues(
    action: String,
    mmol: Double? = null,
    units: Double? = null,
    grams: Double? = null,
    food: String? = null,
    purpose: String? = null,
    activity: String? = null,
    p: com.diapilot.core.PersonalParams = com.diapilot.core.PersonalParams.DEFAULT,
): String? {
    fun bad(v: Double?): Boolean = v != null && (!v.isFinite() || v <= 0.0)
    // Non-finite or non-positive anywhere = hard stop, whatever the action.
    if (bad(mmol) || bad(units) || bad(grams)) return "число не распознано (NaN/≤0)"

    return when (action) {
        "meter" -> when {
            mmol == null -> "не распознано значение глюкозы"
            mmol !in 1.0..35.0 -> "глюкоза вне физиологического диапазона (1–35 ммоль/л)"
            else -> null
        }
        "bolus" -> when {
            units == null -> "не распознана доза"
            units > p.commandMaxBolusUnits ->
                "болюс %.1f ед выше персонального предохранителя (%.0f ед)"
                    .format(units, p.commandMaxBolusUnits)
            purpose != null && purpose !in BOLUS_PURPOSES ->
                "неизвестное назначение «$purpose»"
            else -> null
        }
        "basal" -> when {
            units == null -> "не распознана доза"
            units > p.commandMaxBasalUnits ->
                "базал %.1f ед выше персонального предохранителя (%.0f ед)"
                    .format(units, p.commandMaxBasalUnits)
            else -> null
        }
        "food" -> when {
            food.isNullOrBlank() -> "пустое описание еды"
            food.length > 120 -> "описание еды слишком длинное"
            grams != null && grams > 300.0 -> "углеводы > 300 г — похоже на ошибку распознавания"
            else -> null
        }
        "activity" -> when {
            activity.isNullOrBlank() -> "пустое описание активности"
            activity.length > 40 -> "описание активности слишком длинное"
            else -> null
        }
        "dextrose" -> null
        else -> "неизвестное действие «$action»"
    }
}
