package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.CommandBlock
import io.github.obdosok.diapilot.R

/** Renders :core's [CommandBlock] (why a parsed command is not written). */
object CommandText {
    fun block(context: Context, b: CommandBlock): String {
        val res = context.localized()
        return when (b) {
            CommandBlock.NotANumber -> res.getString(R.string.command_guard_not_a_number)
            CommandBlock.GlucoseMissing -> res.getString(R.string.command_guard_glucose_missing)
            CommandBlock.GlucoseOutOfRange -> res.getString(R.string.command_guard_glucose_out_of_range)
            CommandBlock.DoseMissing -> res.getString(R.string.command_guard_dose_missing)
            is CommandBlock.BolusAboveFuse -> res.getString(R.string.command_guard_bolus_above_fuse, b.units, b.fuseUnits)
            is CommandBlock.UnknownPurpose -> res.getString(R.string.command_guard_unknown_purpose, b.purpose)
            is CommandBlock.BasalAboveFuse -> res.getString(R.string.command_guard_basal_above_fuse, b.units, b.fuseUnits)
            CommandBlock.FoodEmpty -> res.getString(R.string.command_guard_food_empty)
            CommandBlock.FoodTooLong -> res.getString(R.string.command_guard_food_too_long)
            CommandBlock.CarbsImplausible -> res.getString(R.string.command_guard_carbs_implausible)
            CommandBlock.ActivityEmpty -> res.getString(R.string.command_guard_activity_empty)
            CommandBlock.ActivityTooLong -> res.getString(R.string.command_guard_activity_too_long)
            is CommandBlock.UnknownAction -> res.getString(R.string.command_guard_unknown_action, b.action)
        }
    }
}
