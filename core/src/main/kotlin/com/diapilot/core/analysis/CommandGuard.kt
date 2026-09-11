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

/** Bolus purposes the UI knows how to render and train on. Stored tokens: they
 *  are written to the database as they are and never translated. */
val BOLUS_PURPOSES = setOf("коррекция", "на еду", "докол", "воздух")

/** The physiological glucose range a meter command may carry, mmol/L. */
val COMMAND_GLUCOSE_RANGE_MMOL = 1.0..35.0

/** Carbs above this in one food command look like a recognition error, g. */
const val COMMAND_MAX_CARBS_G = 300.0

/** Longest food description a command may carry, characters. */
const val COMMAND_MAX_FOOD_CHARS = 120

/** Longest activity description a command may carry, characters. */
const val COMMAND_MAX_ACTIVITY_CHARS = 40

/** Why a parsed command is blocked; the app renders the reason under the composer. */
sealed interface CommandBlock {
    /** A value is NaN, infinite, zero or negative. */
    data object NotANumber : CommandBlock
    data object GlucoseMissing : CommandBlock
    /** Outside [COMMAND_GLUCOSE_RANGE_MMOL]. */
    data object GlucoseOutOfRange : CommandBlock
    data object DoseMissing : CommandBlock
    data class BolusAboveFuse(val units: Double, val fuseUnits: Double) : CommandBlock
    /** Not one of [BOLUS_PURPOSES]. */
    data class UnknownPurpose(val purpose: String) : CommandBlock
    data class BasalAboveFuse(val units: Double, val fuseUnits: Double) : CommandBlock
    data object FoodEmpty : CommandBlock
    data object FoodTooLong : CommandBlock
    /** Above [COMMAND_MAX_CARBS_G]. */
    data object CarbsImplausible : CommandBlock
    data object ActivityEmpty : CommandBlock
    data object ActivityTooLong : CommandBlock
    data class UnknownAction(val action: String) : CommandBlock
}

/**
 * Null when the command's values are safe to write; otherwise why it is
 * blocked, shown under the composer.
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
): CommandBlock? {
    fun bad(v: Double?): Boolean = v != null && (!v.isFinite() || v <= 0.0)
    // Non-finite or non-positive anywhere = hard stop, whatever the action.
    if (bad(mmol) || bad(units) || bad(grams)) return CommandBlock.NotANumber

    return when (action) {
        "meter" -> when {
            mmol == null -> CommandBlock.GlucoseMissing
            mmol !in COMMAND_GLUCOSE_RANGE_MMOL -> CommandBlock.GlucoseOutOfRange
            else -> null
        }
        "bolus" -> when {
            units == null -> CommandBlock.DoseMissing
            units > p.commandMaxBolusUnits -> CommandBlock.BolusAboveFuse(units, p.commandMaxBolusUnits)
            purpose != null && purpose !in BOLUS_PURPOSES -> CommandBlock.UnknownPurpose(purpose)
            else -> null
        }
        "basal" -> when {
            units == null -> CommandBlock.DoseMissing
            units > p.commandMaxBasalUnits -> CommandBlock.BasalAboveFuse(units, p.commandMaxBasalUnits)
            else -> null
        }
        "food" -> when {
            food.isNullOrBlank() -> CommandBlock.FoodEmpty
            food.length > COMMAND_MAX_FOOD_CHARS -> CommandBlock.FoodTooLong
            grams != null && grams > COMMAND_MAX_CARBS_G -> CommandBlock.CarbsImplausible
            else -> null
        }
        "activity" -> when {
            activity.isNullOrBlank() -> CommandBlock.ActivityEmpty
            activity.length > COMMAND_MAX_ACTIVITY_CHARS -> CommandBlock.ActivityTooLong
            else -> null
        }
        "dextrose" -> null
        else -> CommandBlock.UnknownAction(action)
    }
}
