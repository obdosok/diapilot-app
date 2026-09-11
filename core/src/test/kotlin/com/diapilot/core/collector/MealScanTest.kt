package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Test

private const val BASE = 1_748_768_400_000L

/** Minimal in-memory store for scan orchestration tests. */
internal class FakeStore : CollectorStore {
    val readings = mutableListOf<GlucosePoint>()
    val bolusList = mutableListOf<BolusPoint>()
    val meals = linkedMapOf<Long, Pair<MealEvent, Long?>>() // onset -> (event, labelId)
    val labels = mutableMapOf<String, Long>()

    override fun upsertReading(reading: Reading) {
        readings.add(GlucosePoint(reading.tsMs, reading.mmol))
    }

    override fun upsertInsulin(event: InsulinEvent) {
        bolusList.add(BolusPoint(event.tsMs, event.units))
    }

    override fun deleteInsulin(tsMs: Long) {
        bolusList.removeAll { it.tsMs == tsMs }
    }

    override fun setBolusPurpose(tsMs: Long, purpose: String?) {
        val i = bolusList.indexOfFirst { it.tsMs == tsMs }
        if (i >= 0) bolusList[i] = bolusList[i].copy(purpose = purpose)
    }

    private val basal = mutableListOf<BolusPoint>()
    override fun upsertBasal(tsMs: Long, units: Double) { basal.add(BolusPoint(tsMs, units)) }
    override fun basalEvents(fromMs: Long, toMs: Long): List<BolusPoint> =
        basal.filter { it.tsMs in fromMs..toMs }
    override fun deleteBasal(tsMs: Long) { basal.removeAll { it.tsMs == tsMs } }

    private val hr = mutableListOf<HrPoint>()
    private val sleep = mutableListOf<SleepSession>()
    override fun upsertHeartRate(point: HrPoint) { hr.add(point) }
    override fun heartRate(fromMs: Long, toMs: Long): List<HrPoint> =
        hr.filter { it.tsMs in fromMs..toMs }
    override fun lastHeartRate(): HrPoint? = hr.maxByOrNull { it.tsMs }
    override fun upsertSleepSession(session: SleepSession) { sleep.add(session) }
    override fun sleepSessions(fromMs: Long, toMs: Long): List<SleepSession> =
        sleep.filter { it.endMs >= fromMs && it.startMs <= toMs }

    private val stepBuckets = mutableListOf<StepBucket>()
    override fun upsertSteps(bucket: StepBucket) { stepBuckets.add(bucket) }
    override fun steps(fromMs: Long, toMs: Long): List<StepBucket> =
        stepBuckets.filter { it.endMs >= fromMs && it.startMs <= toMs }

    private val minute = mutableListOf<Reading>()
    override fun upsertMinuteReading(reading: Reading) { minute.add(reading) }
    override fun minuteReadings(fromMs: Long, toMs: Long): List<GlucosePoint> =
        minute.filter { it.tsMs in fromMs..toMs }.map { GlucosePoint(it.tsMs, it.mmol) }
    override fun lastMinuteReading(): Reading? = minute.maxByOrNull { it.tsMs }

    private val annotationList = mutableListOf<Annotation>()

    override fun addAnnotation(annotation: Annotation): Long {
        val id = (annotationList.size + 1).toLong()
        annotationList.add(annotation.copy(id = id))
        return id
    }

    override fun annotations(fromMs: Long, toMs: Long): List<Annotation> =
        annotationList.filter { it.tsMs in fromMs..toMs }.sortedByDescending { it.tsMs }

    override fun updateAnnotation(id: Long, tsMs: Long, content: String, mediaRef: String?) {
        val i = annotationList.indexOfFirst { it.id == id }
        if (i >= 0) {
            annotationList[i] = annotationList[i].copy(tsMs = tsMs, content = content, mediaRef = mediaRef)
        }
    }

    override fun deleteAnnotation(id: Long) {
        annotationList.removeAll { it.id == id }
    }

    override fun setAnnotationAnalysis(id: Long, analysis: String) {
        val i = annotationList.indexOfFirst { it.id == id }
        if (i >= 0) annotationList[i] = annotationList[i].copy(analysis = analysis)
    }

    override fun topAnnotationTexts(limit: Int): List<String> =
        annotationList.groupBy { it.content }.entries
            .sortedByDescending { it.value.size }.take(limit).map { it.key }

    override fun getOrCreateLabel(name: String): Long =
        labels.getOrPut(name) { (labels.size + 1).toLong() }

    override fun addMealEvent(event: MealEvent, labelId: Long?) {
        val existingLabel = meals[event.onsetMs]?.second
        meals[event.onsetMs] = event to (existingLabel ?: labelId)
    }

    override fun setMealLabel(onsetMs: Long, labelId: Long) {
        meals[onsetMs]?.let { meals[onsetMs] = it.first to labelId }
    }

    override fun readings(fromMs: Long, toMs: Long): List<GlucosePoint> =
        readings.filter { it.tsMs in fromMs..toMs }.sortedBy { it.tsMs }

    override fun lastReading(): Reading? =
        readings.maxByOrNull { it.tsMs }?.let { Reading(it.tsMs, it.mmol * 18.0182, it.mmol, null) }

    override fun boluses(fromMs: Long, toMs: Long): List<BolusPoint> =
        bolusList.filter { it.tsMs in fromMs..toMs }.sortedBy { it.tsMs }

    override fun unlabeledMeals(sinceMs: Long): List<MealEvent> =
        meals.values.filter { it.second == null && it.first.onsetMs >= sinceMs }.map { it.first }

    override fun meals(fromMs: Long, toMs: Long): List<MealEvent> =
        meals.values.filter { it.second != -1L }.map { it.first }
            .filter { it.onsetMs in fromMs..toMs }

    override fun dismissMeal(onsetMs: Long) {
        meals[onsetMs]?.let { meals[onsetMs] = it.first to -1L }
    }

    override fun labeledMeals(limit: Int): List<LabeledMeal> =
        meals.values.filter { it.second != null }.take(limit).map { (ev, lid) ->
            LabeledMeal(ev, lid!!, labels.entries.first { it.value == lid }.key)
        }

    override fun decrementLabelUse(labelId: Long) { /* counts not modeled in fake */ }

    override fun topLabels(limit: Int): List<Pair<Long, String>> =
        labels.entries.take(limit).map { it.value to it.key }

    override fun count(table: String): Long = when (table) {
        "glucose_readings" -> readings.size.toLong()
        "meal_events" -> meals.size.toLong()
        else -> 0
    }
}

class MealScanTest {

    private fun mealCurve(store: FakeStore) {
        // Flat, then a rise 6.0 -> 9.9 mmol within 30 min, then plateau.
        val pts = (-30 until 5 step 5).map { it to 6.0 } +
            listOf(5 to 6.5, 10 to 7.5, 15 to 8.8, 20 to 9.5, 25 to 9.8, 30 to 9.9) +
            (35 until 60 step 5).map { it to 9.9 }
        pts.forEach { (m, bg) ->
            store.upsertReading(Reading(BASE + m * 60_000L, bg * 18.0182, bg, null))
        }
        store.upsertInsulin(InsulinEvent(BASE, 6.0, "Fiasp"))
    }

    @Test
    fun `scan detects and persists meals`() {
        val store = FakeStore()
        mealCurve(store)
        val n = scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        assertEquals(1, n)
        assertEquals(1, store.unlabeledMeals().size)
        assertEquals(MealEvent.Kind.ANNOUNCED, store.unlabeledMeals()[0].kind)
    }

    @Test
    fun `rescan is idempotent and keeps labels`() {
        val store = FakeStore()
        mealCurve(store)
        scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        val onset = store.unlabeledMeals()[0].onsetMs
        store.setMealLabel(onset, store.getOrCreateLabel("овсянка"))
        assertEquals(0, store.unlabeledMeals().size)

        // Rescan the same window: same event re-detected, label survives.
        scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        assertEquals(1L, store.count("meal_events"))
        assertEquals(0, store.unlabeledMeals().size)
    }

    @Test
    fun `relabel replaces the label`() {
        val store = FakeStore()
        mealCurve(store)
        scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        val onset = store.unlabeledMeals()[0].onsetMs
        store.setMealLabel(onset, store.getOrCreateLabel("пиво"))
        val old = store.labeledMeals()[0]
        assertEquals("пиво", old.labelName)

        store.relabelMeal(onset, old.labelId, "овсянка")
        val new = store.labeledMeals()[0]
        assertEquals("овсянка", new.labelName)
        assertEquals(onset, new.event.onsetMs)
    }

    @Test
    fun `dismissed meal stays dismissed after rescan`() {
        val store = FakeStore()
        mealCurve(store)
        scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        val onset = store.unlabeledMeals()[0].onsetMs
        store.dismissMeal(onset)
        assertEquals(0, store.meals(0, Long.MAX_VALUE).size)

        scanMeals(store, BASE - 60 * 60_000L, BASE + 120 * 60_000L)
        assertEquals(0, store.meals(0, Long.MAX_VALUE).size)
        assertEquals(0, store.unlabeledMeals().size)
    }

    @Test
    fun `empty store scans to zero`() {
        assertEquals(0, scanMeals(FakeStore(), 0, Long.MAX_VALUE))
    }
}
