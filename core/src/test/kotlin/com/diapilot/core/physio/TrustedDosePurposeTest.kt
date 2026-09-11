package com.diapilot.core.physio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which labels mean "this injection is trusted".
 *
 * The "top-up" label — a correction bolus given when glucose ran higher than
 * the meal bolus covered — was added at the user's request. It is the same
 * statement of intent as "correction"; that it lands while food is still
 * absorbing is a concern to NAME, not a reason to discard the user's label.
 * On real data top-ups were about as common as corrections, so this
 * roughly doubles the corpus.
 */
class TrustedDosePurposeTest {
    @Test fun theLabelsTheUserUsesAreTrusted() {
        assertTrue(isTrustedDosePurposeV1("коррекция"))
        assertTrue(isTrustedDosePurposeV1("Коррекция"))
        assertTrue(isTrustedDosePurposeV1("докол"))
        assertTrue(isTrustedDosePurposeV1(" Докол "))
    }

    @Test fun aMealDoseIsNotACorrection() {
        assertFalse("a meal bolus covers carbs, it does not correct", isTrustedDosePurposeV1("на еду"))
        assertFalse(isTrustedDosePurposeV1("воздух"))
        assertFalse(isTrustedDosePurposeV1(null))
        assertFalse(isTrustedDosePurposeV1(""))
    }
}
