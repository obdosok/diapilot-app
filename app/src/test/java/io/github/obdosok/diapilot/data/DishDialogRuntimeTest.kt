package io.github.obdosok.diapilot.data

import com.diapilot.core.analysis.DishRecognitionV1
import com.diapilot.core.analysis.FoodPhysicalFormV2
import com.diapilot.core.analysis.FoodStructureAcceptanceV1
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.HrPoint
import com.diapilot.core.collector.InsulinEvent
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.SleepSession
import com.diapilot.core.collector.StepBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F-05 layers 2–3, the WRITE path: without confirmation the note stays plain;
 * "yes" appends the accepted structure (with the dish id) as a revision and
 * teaches the wording; "no" is remembered and never re-asked.
 *
 * The mistake this respects: the counter and the write must ask the SAME
 * question. Here the note's post-confirmation analysis is asserted through
 * [FoodStructureAcceptanceV1.acceptedDishId] — the exact predicate the pool
 * builder uses — not through a lookalike.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class DishDialogRuntimeTest {

    private val context: android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private val smoothie = DishRecognitionV1.KnownDish(
        FoodStructureAcceptanceV1.Proposed(
            id = "smoothie", title = "смузи (апельсиновый сок + петрушка)",
            aliases = listOf("смузи с апельсиновым соком и петрушкой", "смузи", "стакан смузи"),
            form = FoodPhysicalFormV2.LIQUID, fast = .8, medium = .15, slow = .05,
            fiberG = .5, proteinG = 2.0, fatG = 1.0, confidence = .7,
        ),
        intakes = 28, typicalCarbsG = 22.0,
    )

    private class FakeStore : CollectorStore {
        val notes = mutableListOf<Annotation>()
        val analyses = mutableMapOf<Long, String>()
        override fun annotations(fromMs: Long, toMs: Long) =
            notes.filter { it.tsMs in fromMs..toMs }
        override fun setAnnotationAnalysis(id: Long, analysis: String) {
            analyses[id] = analysis
            val i = notes.indexOfFirst { it.id == id }
            if (i >= 0) notes[i] = notes[i].copy(analysis = analysis)
        }

        private fun no(): Nothing = throw UnsupportedOperationException("not used")
        override fun meals(fromMs: Long, toMs: Long): List<MealEvent> = no()
        override fun labeledMeals(limit: Int): List<LabeledMeal> = no()
        override fun boluses(fromMs: Long, toMs: Long): List<BolusPoint> = no()
        override fun heartRate(fromMs: Long, toMs: Long): List<HrPoint> = no()
        override fun steps(fromMs: Long, toMs: Long): List<StepBucket> = no()
        override fun upsertReading(reading: Reading) = no()
        override fun upsertInsulin(event: InsulinEvent) = no()
        override fun deleteInsulin(tsMs: Long) = no()
        override fun setBolusPurpose(tsMs: Long, purpose: String?) = no()
        override fun upsertMinuteReading(reading: Reading) = no()
        override fun minuteReadings(fromMs: Long, toMs: Long): List<GlucosePoint> = no()
        override fun lastMinuteReading(): Reading? = no()
        override fun upsertBasal(tsMs: Long, units: Double) = no()
        override fun basalEvents(fromMs: Long, toMs: Long): List<BolusPoint> = no()
        override fun deleteBasal(tsMs: Long) = no()
        override fun upsertHeartRate(point: HrPoint) = no()
        override fun lastHeartRate(): HrPoint? = no()
        override fun upsertSleepSession(session: SleepSession) = no()
        override fun sleepSessions(fromMs: Long, toMs: Long): List<SleepSession> = no()
        override fun upsertSteps(bucket: StepBucket) = no()
        override fun addAnnotation(annotation: Annotation): Long = no()
        override fun updateAnnotation(id: Long, tsMs: Long, content: String, mediaRef: String?) = no()
        override fun deleteAnnotation(id: Long) = no()
        override fun getOrCreateLabel(name: String): Long = no()
        override fun dismissMeal(onsetMs: Long) = no()
        override fun setMealLabel(onsetMs: Long, labelId: Long) = no()
        override fun readings(fromMs: Long, toMs: Long): List<GlucosePoint> = no()
        override fun lastReading(): Reading? = no()
        override fun unlabeledMeals(sinceMs: Long): List<MealEvent> = no()
        override fun decrementLabelUse(labelId: Long) = no()
        override fun count(table: String): Long = no()
        override fun topAnnotationTexts(limit: Int): List<String> = no()
        override fun addMealEvent(event: MealEvent, labelId: Long?) = no()
        override fun topLabels(limit: Int): List<Pair<Long, String>> = no()
    }

    private fun move(ts: Long, wording: String) = DishDialogRuntime.Move(
        tsMs = ts, noteContent = wording, dishId = "smoothie",
        question = "Это ваш смузи?", createdMs = ts,
    )

    @Test
    fun `without confirmation the note carries no structure`() {
        val store = FakeStore()
        store.notes += Annotation(
            id = 1L, tsMs = 1000L, kind = "food", content = "обычный смузи",
            mediaRef = null, analysis = null, estCarbs = 20.0,
        )
        // the question exists; nothing has been written
        assertNull(FoodStructureAcceptanceV1.acceptedDishId(store.notes[0].analysis))
    }

    @Test
    fun `yes appends the structure with the dish id and learns the alias`() {
        val store = FakeStore()
        store.notes += Annotation(
            id = 1L, tsMs = 1000L, kind = "food", content = "обычный смузи",
            mediaRef = null, analysis = null, estCarbs = 20.0,
        )
        val revised = DishDialogRuntime.confirmDish(
            context, store, move(1000L, "обычный смузи"), listOf(smoothie),
        )
        assertTrue(revised)
        // the SAME predicate the pool builder asks
        assertEquals("smoothie", FoodStructureAcceptanceV1.acceptedDishId(store.notes[0].analysis))
        // grams untouched — the dish gives structure, never quantity
        assertEquals(20.0, store.notes[0].estCarbs!!, 1e-9)
        // layer 3: the wording is now vocabulary
        assertTrue(
            DishAliasRuntime.learned(context)["smoothie"].orEmpty()
                .any { it.equals("обычный смузи", true) },
        )
        // and the exact tier now recognises it without a question
        val learnedDish = smoothie.copy(
            proposed = smoothie.proposed.copy(
                aliases = smoothie.proposed.aliases +
                    DishAliasRuntime.learned(context)["smoothie"].orEmpty(),
            ),
        )
        assertEquals(
            "smoothie",
            DishRecognitionV1.exactAlias("обычный смузи", listOf(learnedDish))?.proposed?.id,
        )
    }

    @Test
    fun `no is remembered — the same wording never re-asks this dish`() {
        val move = move(2000L, "какой-то смузи")
        DishDialogRuntime.rejectDish(context, move)
        assertTrue(DishAliasRuntime.isRejected(context, "smoothie", "какой-то смузи"))
        // and resolve() respects it: no Ask for a rejected wording
        val r = DishRecognitionV1.resolve(
            "какой-то смузи", listOf(smoothie), acceptedIds = emptySet(),
            isRejected = { id -> DishAliasRuntime.isRejected(context, id, "какой-то смузи") },
        )
        assertTrue(r is DishRecognitionV1.Resolution.None)
    }

    @Test
    fun `an unaccepted dish's asset alias asks instead of applying`() {
        // exact alias, but the dish was never accepted → still a question
        val r = DishRecognitionV1.resolve("смузи", listOf(smoothie), acceptedIds = emptySet())
        assertTrue("expected a question, not a silent application: $r", r is DishRecognitionV1.Resolution.Ask)
        // once accepted, the same wording applies silently — the user confirmed it
        val r2 = DishRecognitionV1.resolve("смузи", listOf(smoothie), acceptedIds = setOf("smoothie"))
        assertTrue(r2 is DishRecognitionV1.Resolution.Apply)
    }

    @Test
    fun `codec round-trips moves`() {
        val list = listOf(move(1L, "обычный смузи"), move(2L, "стакан смузи").copy(kind = DishDialogRuntime.KIND_ASSUMPTION))
        assertEquals(list, DishDialogRuntime.decode(DishDialogRuntime.encode(list)))
        assertEquals(emptyList<DishDialogRuntime.Move>(), DishDialogRuntime.decode("мусор"))
    }

}
