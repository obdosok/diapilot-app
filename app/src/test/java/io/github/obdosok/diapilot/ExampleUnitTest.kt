package io.github.obdosok.diapilot

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun cheapLiveRefreshPreservesLoadedHistorySlice() {
        val old=UiState(
            historyDays=21,historyAnchorMs=1234L,labelByOnset=mapOf(99L to "meal"),
            historyNotes=listOf(com.diapilot.core.collector.Annotation(99L,"food","meal",id=7L,estCarbs=20.0)),
        )
        val live=UiState(readings=500L,lastReading=null)
        val merged=live.withHistoryFrom(old)
        assertEquals(500L,merged.readings)
        assertEquals(21,merged.historyDays)
        assertEquals(1234L,merged.historyAnchorMs)
        assertEquals(setOf(7L),merged.historyNotes.map{it.id}.toSet())
        assertEquals("meal",merged.labelByOnset[99L])
    }
}
