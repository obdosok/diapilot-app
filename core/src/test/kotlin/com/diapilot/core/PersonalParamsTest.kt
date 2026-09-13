package com.diapilot.core

import com.diapilot.core.collector.MGDL_PER_MMOL

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalParamsTest {
    @Test
    fun trendNameFollowsXdripConvention() {
        // mg/dl per 5 min → mmol input
        fun name(mgdl: Double) = trendName(mgdl / MGDL_PER_MMOL)
        assertEquals("DoubleUp", name(18.0))
        assertEquals("SingleUp", name(10.6))   // the anchored WatchDrip sample
        assertEquals("FortyFiveUp", name(5.0))
        assertEquals("Flat", name(4.9))
        assertEquals("Flat", name(-4.9))
        assertEquals("FortyFiveDown", name(-5.0))
        assertEquals("SingleDown", name(-9.0))
        assertEquals("DoubleDown", name(-18.0))
        assertEquals("Flat", trendName(null))
    }

    @Test
    fun glyphsCoverEveryNameAndFailSilent() {
        listOf(
            "DoubleUp", "SingleUp", "FortyFiveUp", "Flat",
            "FortyFiveDown", "SingleDown", "DoubleDown",
        ).forEach { n -> assertEquals(1, trendGlyph(n).codePointCount(0, trendGlyph(n).length)) }
        assertEquals("", trendGlyph(null))
        assertEquals("", trendGlyph("NOT COMPUTABLE"))
    }
}
