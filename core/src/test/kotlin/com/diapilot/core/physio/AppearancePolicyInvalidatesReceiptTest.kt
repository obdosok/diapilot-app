package com.diapilot.core.physio

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.hybrid.CarbAppearancePolicyV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A RECEIPT MUST NOT SURVIVE THE PHYSIOLOGY THAT PRODUCED IT.
 *
 * Review finding (third pass): the queue changed under Stage9 while
 * `JOINT_ATTRIBUTION_VERSION_V1` still said v6 and the appearance policy was
 * absent from the receipt hash. A decomposition computed on the flat 30 g/h
 * would then restore after the upgrade as if it were current, and the history
 * would be a silent mixture of two physiologies — the worst outcome available,
 * because a mixed corpus reads as one and nothing in it says which row is which.
 *
 * The version string alone is not enough and that is the point of the second
 * test: it only works while somebody remembers to bump it, and the queue is the
 * proof that nobody does. The POLICY ITSELF is in the hash, so retuning the
 * sieving invalidates on its own.
 */
class AppearancePolicyInvalidatesReceiptTest {

    private val m = 60_000L
    private fun note(atMin: Long, text: String, carbs: Double, protein: Double, fat: Double) =
        Annotation(
            atMin * m, "food", text, estCarbs = carbs, carbsSource = "manual",
            carbsKnownAtMs = atMin * m, id = atMin + 1,
            analysis = "БЕЛКИ: $protein г\nЖИРЫ: $fat г",
            analysisKnownAtMs = atMin * m,
        )

    /** Fatty and multi-dish: the only arrangement where the queues differ. */
    private val notes = listOf(
        note(0, "пицца", 69.0, 30.0, 30.0),
        note(45, "пиво", 18.0, 2.0, 0.0),
    )
    private val readings = (0..300 step 5).map { t ->
        GlucosePoint(t * m, 5.5 + 4.0 * (t / 300.0))
    }
    private val cs = PosteriorV1(.165, .137, .197, 20, 10, 0)

    private fun receipt(policy: CarbAppearancePolicyV1) = jointMealAttributionV1(
        notes, readings, emptyList<BolusPoint>(), cs, 300 * m,
        { b -> VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1))
            .kernelForEpisode(b.tsMs, emptyList(), b.tsMs, b.units) },
        appearance = policy,
    )

    /** A LEAN meal, where the two queues are the same arithmetic: 120 kcal/h
     *  IS 30 g/h on pure carbohydrate and sieving has no fat to sieve. */
    private val leanNotes = listOf(
        note(0, "сок", 30.0, 0.0, 0.0),
        note(45, "глюкоза", 15.0, 0.0, 0.0),
    )

    private fun leanReceipt(policy: CarbAppearancePolicyV1) = jointMealAttributionV1(
        leanNotes, readings, emptyList<BolusPoint>(), cs, 300 * m,
        { b -> VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1))
            .kernelForEpisode(b.tsMs, emptyList(), b.tsMs, b.units) },
        appearance = policy,
    )

    /**
     * THE POLICY ITSELF IS IN THE HASH, and this is the test that proves it.
     *
     * The fatty test below passes even with the policy removed from the hash,
     * because the two queues produce slightly different fits and the fit
     * summary is hashed — so it detects the NUMBERS, not the model generation.
     * A mutation confirmed that. On a lean meal the numbers are identical by
     * construction, so nothing but the recorded policy can tell the two apart,
     * and a receipt must still not be restored under the wrong physiology: a
     * stored row that cannot say which model produced it is exactly what makes
     * a mixed history unreadable.
     */
    @Test
    fun `a lean receipt still records which physiology produced it`() {
        val legacy = requireNotNull(leanReceipt(CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY))
        val shipped = requireNotNull(leanReceipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED))
        val legacyAmounts = legacy.allocations.map { it.bestAllocationMmol }
        val shippedAmounts = shipped.allocations.map { it.bestAllocationMmol }
        assertEquals(
            "the fixture is not lean — the queues moved the allocation, so this " +
                "test would pass on the numbers instead of on the policy",
            legacyAmounts.size,
            shippedAmounts.size,
        )
        legacyAmounts.zip(shippedAmounts).forEach { (a, b) ->
            assertEquals(
                "the fixture is not lean — the queues moved the allocation, so this " +
                    "test would pass on the numbers instead of on the policy",
                a, b, 1e-9,
            )
        }
        assertNotEquals(
            "identical numbers, different physiology, same identity",
            legacy.cacheIdentity, shipped.cacheIdentity,
        )
    }

    @Test
    fun `a receipt made on the gram queue is not the same receipt`() {
        val legacy = requireNotNull(receipt(CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY))
        val shipped = requireNotNull(receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED))
        assertNotEquals(
            "a 30 g/h decomposition would restore as current after the upgrade",
            legacy.cacheIdentity, shipped.cacheIdentity,
        )
    }

    /**
     * AND RETUNING THE POLICY INVALIDATES WITHOUT ANY VERSION BUMP. This is the
     * half that keeps working when the next person moves the sieving and does
     * not think about caches.
     */
    @Test
    fun `moving the sieving alone changes the receipt hash`() {
        val shipped = requireNotNull(receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED))
        val retuned = requireNotNull(
            receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = 0.35)),
        )
        assertNotEquals(shipped.cacheIdentity, retuned.cacheIdentity)
        val slower = requireNotNull(
            receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(emptyingKcalPerHour = 90.0)),
        )
        assertNotEquals(shipped.cacheIdentity, slower.cacheIdentity)
    }

    /** The same policy still hashes the same, or every restart is a recompute. */
    @Test
    fun `an unchanged policy keeps the receipt stable`() {
        assertEquals(
            requireNotNull(receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED)).cacheIdentity,
            requireNotNull(receipt(CarbAppearancePolicyV1.PHYSIO_SHIPPED)).cacheIdentity,
        )
    }

    /** The version string carries the generation, so a reader of stored data can
     *  tell which physiology produced it without re-deriving it. */
    @Test
    fun `the joint attribution version names the queue it runs`() {
        assertTrue(
            "the version still claims v6 while the queue is caloric: $JOINT_ATTRIBUTION_VERSION_V1",
            JOINT_ATTRIBUTION_VERSION_V1.contains("v7"),
        )
        assertEquals("kcal180-sieve0.65", CarbAppearancePolicyV1.PHYSIO_SHIPPED.signature())
        assertEquals("gram30", CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY.signature())
    }
}
