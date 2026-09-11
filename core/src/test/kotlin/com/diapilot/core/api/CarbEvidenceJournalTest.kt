package com.diapilot.core.api

import com.diapilot.core.collector.CarbEvidenceInputV1
import com.diapilot.core.collector.CarbEvidenceSourceV1
import com.diapilot.core.collector.CarbEvidenceV1
import com.diapilot.core.collector.CarbUncertaintyV1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbEvidenceJournalTest {
    @Test fun mealPayloadRoundTripsStructuredEvidenceWithoutRawReference() {
        val input = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.LABEL_WEIGHT, true,
            labelCarbsPerServingG = 18.0, servings = 2.0, intakeDurationMin = 30.0,
            amountUncertainty = CarbUncertaintyV1("label_rounding"),
            timingUncertainty = CarbUncertaintyV1("user_duration"),
            evidenceHash = "0123456789abcdef", alcoholPresent = true,
        ).validated()
        val evidence = CarbEvidenceV1(
            "carb:9", 1, null, 9, "annotation:9", 1_000, 1_000, 1_801_000,
            2_000, 2_000, input,
        )
        val body = JSONObject("{" + EventPayloads.meal("redacted", 36.0, "anchor", null, evidence) + "}")
        val wire = body.getJSONObject("carb_evidence")
        assertEquals("carb:9", wire.getString("evidence_id"))
        assertEquals(2_000, wire.getLong("known_at_ms"))
        assertEquals(36.0, wire.getDouble("total_carbs_g"), 1e-12)
        assertTrue(wire.getBoolean("alcohol_present"))
        assertFalse(wire.has("clean_cs"))
        assertFalse(body.toString().contains("raw label text"))
        val parsed = EventPayloads.carbEvidenceFromMealPayload(body.toString())!!
        assertEquals(evidence.canonicalJson(), parsed.canonicalJson())
    }
}
