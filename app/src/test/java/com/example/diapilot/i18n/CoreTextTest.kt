package com.example.diapilot.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.BackgroundForce
import com.diapilot.core.analysis.CommandBlock
import com.diapilot.core.analysis.Confidence
import com.diapilot.core.analysis.DishRecognitionV1
import com.diapilot.core.analysis.MarkKind
import com.diapilot.core.analysis.Reject
import com.diapilot.core.analysis.StatusInput
import com.diapilot.core.analysis.SuggestTrigger
import com.diapilot.core.analysis.SysLabels
import com.diapilot.core.analysis.TodBucket
import com.diapilot.core.analysis.WatchHint
import com.diapilot.core.analysis.statusSummary
import com.diapilot.core.libre.LibreStatus
import com.diapilot.core.physio.CoercedLandmark
import com.diapilot.core.physio.InsulinLandmark
import com.diapilot.core.physio.InsulinTailFromAmplitudeV1
import com.diapilot.core.physio.LandmarkRefusal
import com.diapilot.core.physio.LandmarkRejection
import com.diapilot.core.physio.OrderingConflict
import com.diapilot.core.twin.HealthReason
import com.diapilot.core.twin.Regime
import com.diapilot.core.twin.TrendNuance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

/** One rendering of every language-neutral value :core produces. */
private fun everyCoreText(c: Context): List<String> = buildList {
    TodBucket.entries.forEach { add(IsfText.todBucket(c, it)) }
    Confidence.entries.forEach { add(IsfText.confidence(c, it)) }
    Reject.ALL.forEach { add(IsfText.reject(c, it)) }
    LibreStatus.entries.forEach { add(LibreText.status(c, it)) }
    TrendNuance.entries.forEach { add(TwinText.nuance(c, it)) }
    Regime.entries.forEach { add(TwinText.regime(c, it)) }
    listOf(
        HealthReason.StaleData(20), HealthReason.ThinEvidence(3.0), HealthReason.WideCorridor,
        HealthReason.NoMinuteStream, HealthReason.SensorImplausible("x"), HealthReason.FloorClamped(2.2, 3),
    ).forEach { add(TwinText.healthReason(c, it)) }
    InsulinLandmark.entries.forEach { add(PhysioText.landmark(c, it)) }
    LandmarkRejection.Kind.entries.forEach {
        add(PhysioText.rejection(c, LandmarkRejection(InsulinLandmark.PEAK, it)))
    }
    listOf(
        LandmarkRefusal.WindowTooShort, LandmarkRefusal.LineUnreadable, LandmarkRefusal.NoBreak,
        LandmarkRefusal.AllRejected(emptyList()),
    ).forEach { add(PhysioText.landmarkRefusal(c, it)) }
    InsulinTailFromAmplitudeV1.TailRefusal.entries.forEach { add(PhysioText.tailRefusal(c, it)) }
    add(PhysioText.orderingConflict(c, OrderingConflict(InsulinLandmark.PEAK, 20.0, InsulinLandmark.ONSET, 40.0)))
    listOf(
        CommandBlock.NotANumber, CommandBlock.GlucoseMissing, CommandBlock.GlucoseOutOfRange,
        CommandBlock.DoseMissing, CommandBlock.BolusAboveFuse(20.0, 12.0), CommandBlock.UnknownPurpose("x"),
        CommandBlock.BasalAboveFuse(80.0, 40.0), CommandBlock.FoodEmpty, CommandBlock.FoodTooLong,
        CommandBlock.CarbsImplausible, CommandBlock.ActivityEmpty, CommandBlock.ActivityTooLong,
        CommandBlock.UnknownAction("x"),
    ).forEach { add(CommandText.block(c, it)) }
    listOf(WatchHint.HypoCheck, WatchHint.AboveTarget(12.5, false)).forEach { add(StatusText.watchHint(c, it)) }
    MarkKind.entries.forEach { add(FoodText.markKind(c, it)) }
    SuggestTrigger.entries.forEach { add(FoodText.suggestTrigger(c, it)) }
    BackgroundForce.entries.forEach { add(FoodText.background(c, listOf(it))) }
    SysLabels.ALL.forEach { add(FoodText.mealLabel(c, it)) }
    add(FoodText.portionWord(c))
    add(unitLabel(false))
    add(unitLabel(true))
}

private fun fullPicture() = statusSummary(
    StatusInput(
        iobUnits = 4.5, lastBolusUnits = 5.0, lastBolusAgeMin = 40,
        foodLabel = "ягодянка", foodAgeMin = 65, foodCarbs = 150.0,
        predMmolIn60 = 7.5, predLoIn60 = 6.6, predHiIn60 = 8.4,
    ),
)

private fun forecastOnly(settleMin: Long, settled: Boolean, lo: Double? = null, hi: Double? = null, mmol: Double = 6.1) =
    statusSummary(
        StatusInput(
            null, null, null, null, null, null,
            predMmolIn60 = mmol, predLoIn60 = lo, predHiIn60 = hi,
            predSettleMin = settleMin, predSettled = settled,
        ),
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreTextEnglishTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `the header summary reads naturally in English`() {
        assertEquals(
            listOf("“ягодянка” 1h05m ~150 g · IOB 4.5 · injected 40m ago", "In an hour ~7.5 (6.6–8.4)"),
            StatusText.lines(context, fullPicture()),
        )
        assertEquals(
            listOf("Levels off at ~6.1 (5.2–7.0) in 1h35m"),
            StatusText.lines(context, forecastOnly(95, true, 5.2, 7.0)),
        )
        assertEquals(listOf("Steady from here: ~6.1"), StatusText.lines(context, forecastOnly(5, true)))
        assertEquals(
            listOf("In 3h00m ~4.9, still moving"),
            StatusText.lines(context, forecastOnly(180, false, mmol = 4.9)),
        )
    }

    @Test fun `the watch hint never shows a computed number or insulin units`() {
        val check = StatusText.watchHint(context, WatchHint.HypoCheck)
        assertTrue("no digits allowed in an uncomputed hint: $check", check.none { it.isDigit() })
        val high = StatusText.watchHint(context, WatchHint.AboveTarget(12.5, false))
        assertEquals("above target, to ~12.5", high)
        assertFalse(high, Regex("\\bU\\b").containsMatchIn(high))
        assertEquals("hypo? plan: 10 g juice", StatusText.watchHint(context, WatchHint.HypoPlan("10 g juice")))
    }

    @Test fun `the dish question carries its two facts`() {
        assertEquals(
            "Is this your smoothie? (28 times, usually 22 g)",
            FoodText.dishQuestion(context, DishRecognitionV1.DishQuestion("smoothie", 28, 22.0)),
        )
        assertEquals(
            "Is this your smoothie? (1 time)",
            FoodText.dishQuestion(context, DishRecognitionV1.DishQuestion("smoothie", 1, null)),
        )
    }

    @Test fun `every core value has English text`() {
        everyCoreText(context).forEach {
            assertTrue("empty text", it.isNotBlank())
            // The four system labels are stored Russian tokens; their display is English.
            assertFalse("Cyrillic in English UI: $it", CYRILLIC.containsMatchIn(it))
        }
    }

    @Test fun `a user label is shown as typed`() {
        assertEquals("гречка", FoodText.mealLabel(context, "гречка"))
        assertEquals("end 110→120", PhysioText.coerced(context, listOf(CoercedLandmark(InsulinLandmark.TAIL_END, 110.0, 120.0))))
    }

    @Test fun `every stored tag and purpose has an English label`() {
        val stored = com.diapilot.core.analysis.CONTEXT_TAGS + com.diapilot.core.analysis.ACTIVITY_TAGS +
            com.diapilot.core.analysis.ABSORPTION_SLOW_TAGS + com.diapilot.core.analysis.RESCUE_NOTE_PREFIX
        stored.forEach { tag ->
            val label = TokenText.noteTag(context, tag)
            assertFalse("no English label for the stored tag $tag: $label", CYRILLIC.containsMatchIn(label))
        }
        com.diapilot.core.analysis.BOLUS_PURPOSES.forEach {
            assertFalse(it, CYRILLIC.containsMatchIn(TokenText.bolusPurpose(context, it)!!))
        }
        assertEquals("walk · 40 min", TokenText.noteTag(context, "прогулка · 40 мин"))
        assertEquals("гречка с курицей", TokenText.noteTag(context, "гречка с курицей"))
        assertEquals(null, TokenText.bolusPurpose(context, null))
    }

    @Test fun `ui text resolves nested fragments`() {
        val nested = UiText.res(
            com.example.diapilot.R.string.dish_question,
            UiText.res(com.example.diapilot.R.string.background_force_food),
        )
        assertEquals("Is this your food?", nested.resolve(context))
        assertEquals("3 times", UiText.plural(com.example.diapilot.R.plurals.dish_question_times, 3).resolve(context))
        assertEquals("гречка", UiText.raw("гречка").resolve(context))
    }
}

/** The exact Russian text these values had while :core produced it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class CoreTextRussianTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `the header summary keeps its Russian wording`() {
        assertEquals(
            listOf("«ягодянка» 1ч05м ~150г · IOB 4,5 · укол 40м", "Через час ~7,5 (6,6–8,4)"),
            StatusText.lines(context, fullPicture()),
        )
        assertEquals(
            listOf("Выровняется на ~6,1 (5,2–7,0) через 1ч35м"),
            StatusText.lines(context, forecastOnly(95, true, 5.2, 7.0)),
        )
        assertEquals(listOf("Дальше ровно: ~6,1"), StatusText.lines(context, forecastOnly(5, true)))
        assertEquals(
            listOf("Через 3ч00м ~4,9, ещё в движении"),
            StatusText.lines(context, forecastOnly(180, false, mmol = 4.9)),
        )
    }

    @Test fun `the watch hint keeps its Russian wording`() {
        assertEquals("гипо? план: 10 г сока", StatusText.watchHint(context, WatchHint.HypoPlan("10 г сока")))
        val check = StatusText.watchHint(context, WatchHint.HypoCheck)
        assertEquals("риск гипо — проверьте", check)
        assertTrue(check.none { it.isDigit() })
        val high = StatusText.watchHint(context, WatchHint.AboveTarget(12.5, false))
        assertEquals("выше цели, к ~12,5", high)
        assertFalse(high.contains("ед"))
    }

    @Test fun `the dish question and the guard keep their Russian wording`() {
        assertEquals(
            "Это ваш смузи? (28 раз, обычно 22 г)",
            FoodText.dishQuestion(context, DishRecognitionV1.DishQuestion("смузи", 28, 22.0)),
        )
        assertEquals(
            "болюс 100,0 ед выше персонального предохранителя (12 ед)",
            CommandText.block(context, CommandBlock.BolusAboveFuse(100.0, 12.0)),
        )
        assertEquals(
            "неизвестное назначение «профилактика»",
            CommandText.block(context, CommandBlock.UnknownPurpose("профилактика")),
        )
        assertEquals(
            "лендмарки прочитаны, но ни один не прошёл проверки: пик вне порядка/границ, " +
                "старт снят ИЗ-ЗА ПИКА (не разнесён на 10 мин)",
            PhysioText.landmarkRefusal(
                context,
                LandmarkRefusal.AllRejected(
                    listOf(
                        LandmarkRejection(InsulinLandmark.PEAK, LandmarkRejection.Kind.OUT_OF_ORDER),
                        LandmarkRejection(InsulinLandmark.ONSET, LandmarkRejection.Kind.ONSET_DROPPED_FOR_PEAK),
                    ),
                ),
            ),
        )
        assertEquals("ускоряется", TwinText.nuance(context, TrendNuance.ACCELERATING))
        assertEquals("инсулин+еда", FoodText.background(context, listOf(BackgroundForce.INSULIN, BackgroundForce.FOOD)))
        assertEquals(SysLabels.DAWN, FoodText.mealLabel(context, SysLabels.DAWN))
        // Stored tokens are shown exactly as stored in Russian.
        assertEquals("прогулка · 40 мин", TokenText.noteTag(context, "прогулка · 40 мин"))
        assertEquals("псиллиум", TokenText.noteTag(context, "псиллиум"))
        assertEquals("коррекция", TokenText.bolusPurpose(context, "коррекция"))
        assertEquals("3 раза", UiText.plural(com.example.diapilot.R.plurals.dish_question_times, 3).resolve(context))
    }

    @Test fun `every core value has Russian text`() {
        everyCoreText(context).forEach {
            assertTrue("no Russian text: $it", CYRILLIC.containsMatchIn(it) || it == "x")
        }
    }
}
