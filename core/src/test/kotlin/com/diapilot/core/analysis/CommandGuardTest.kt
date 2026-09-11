package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGuardTest {

    @Test
    fun saneCommandsPass() {
        assertNull(validateCommandValues("bolus", units = 3.5, purpose = "коррекция"))
        assertNull(validateCommandValues("bolus", units = 1.0, purpose = null))
        assertNull(validateCommandValues("basal", units = 16.0))
        assertNull(validateCommandValues("meter", mmol = 7.2))
        assertNull(validateCommandValues("food", food = "гречка с курицей", grams = 45.0))
        assertNull(validateCommandValues("food", food = "чай", grams = null))
        assertNull(validateCommandValues("activity", activity = "прогулка"))
        assertNull(validateCommandValues("dextrose"))
    }

    @Test
    fun insulinFusesBlockAbsurdDoses() {
        // "10 U" misheard as "100 U" must never reach the database.
        assertNotNull(validateCommandValues("bolus", units = 100.0))
        assertNotNull(validateCommandValues("bolus", units = 12.5))
        assertNotNull(validateCommandValues("basal", units = 200.0))
        // Missing dose is a block, not a guess.
        assertNotNull(validateCommandValues("bolus", units = null))
    }

    @Test
    fun nonFiniteAndNonPositiveAreHardStops() {
        assertNotNull(validateCommandValues("bolus", units = Double.NaN))
        assertNotNull(validateCommandValues("bolus", units = Double.POSITIVE_INFINITY))
        assertNotNull(validateCommandValues("bolus", units = -2.0))
        assertNotNull(validateCommandValues("bolus", units = 0.0))
        assertNotNull(validateCommandValues("meter", mmol = Double.NaN))
        assertNotNull(validateCommandValues("food", food = "суп", grams = -30.0))
    }

    @Test
    fun glucoseMustBePhysiological() {
        assertNotNull(validateCommandValues("meter", mmol = 500.0))   // mg/dl slipped in as mmol
        assertNotNull(validateCommandValues("meter", mmol = 0.5))
        assertNotNull(validateCommandValues("meter", mmol = null))
        assertNull(validateCommandValues("meter", mmol = 1.0))
        assertNull(validateCommandValues("meter", mmol = 35.0))
    }

    @Test
    fun whitelistsHold() {
        assertNotNull(validateCommandValues("bolus", units = 2.0, purpose = "профилактика"))
        assertNotNull(validateCommandValues("hack_the_db"))
        assertNotNull(validateCommandValues("food", food = ""))
        assertNotNull(validateCommandValues("food", food = "х".repeat(200)))
        assertNotNull(validateCommandValues("activity", activity = ""))
        assertNotNull(validateCommandValues("food", food = "торт", grams = 500.0))
    }

    @Test
    fun eachBlockNamesItsReason() {
        val fuse = com.diapilot.core.PersonalParams.DEFAULT.commandMaxBolusUnits
        assertEquals(CommandBlock.BolusAboveFuse(100.0, fuse), validateCommandValues("bolus", units = 100.0))
        assertEquals(CommandBlock.NotANumber, validateCommandValues("bolus", units = Double.NaN))
        assertEquals(CommandBlock.DoseMissing, validateCommandValues("basal", units = null))
        assertEquals(CommandBlock.GlucoseOutOfRange, validateCommandValues("meter", mmol = 500.0))
        assertEquals(CommandBlock.GlucoseMissing, validateCommandValues("meter", mmol = null))
        assertEquals(CommandBlock.UnknownPurpose("профилактика"), validateCommandValues("bolus", units = 2.0, purpose = "профилактика"))
        assertEquals(CommandBlock.UnknownAction("hack_the_db"), validateCommandValues("hack_the_db"))
        assertEquals(CommandBlock.FoodEmpty, validateCommandValues("food", food = ""))
        assertEquals(CommandBlock.FoodTooLong, validateCommandValues("food", food = "х".repeat(200)))
        assertEquals(CommandBlock.CarbsImplausible, validateCommandValues("food", food = "торт", grams = 500.0))
        assertEquals(CommandBlock.ActivityEmpty, validateCommandValues("activity", activity = ""))
        assertEquals(CommandBlock.ActivityTooLong, validateCommandValues("activity", activity = "б".repeat(41)))
    }
}
