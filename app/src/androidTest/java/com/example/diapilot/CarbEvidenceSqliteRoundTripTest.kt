package com.example.diapilot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diapilot.core.collector.*
import com.example.diapilot.data.SqliteCollectorStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarbEvidenceSqliteRoundTripTest {
    @Test fun exportParseImportDbExportIsIdempotentAndRejectsConflict() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-evidence-${System.nanoTime()}.sqlite"
        val source = SqliteCollectorStore(context, name)
        val id = source.addAnnotation(Annotation(1_000, "food", "redacted"))
        val input = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.LABEL_WEIGHT, true,
            labelCarbsPer100g = 20.0, weighedEdibleG = 150.0,
            amountUncertainty = CarbUncertaintyV1("label"), timingUncertainty = CarbUncertaintyV1("start"),
        )
        val first = source.appendCarbEvidence(id, 1_000, input, 2_000, 2_100)
        val wire = first.canonicalJson()
        val parsed = CarbEvidenceV1.parseCanonicalJson(wire)
        assertEquals(wire, parsed.canonicalJson())
        assertFalse(source.importCarbEvidence(parsed))
        assertThrows(IllegalArgumentException::class.java) {
            source.importCarbEvidence(parsed.copy(input = parsed.input.copy(weighedEdibleG = 160.0, totalCarbsG = 32.0)))
        }
        assertEquals(wire, source.carbEvidenceHistory(id).single().canonicalJson())
        source.close()
        context.deleteDatabase(name)
    }

    @Test fun version32MigrationPreservesRowsAndAddsParallelLedgers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-migration-${System.nanoTime()}.sqlite"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER, kind TEXT, content TEXT, media_ref TEXT, analysis TEXT, est_carbs REAL, carbs_known_at_ms INTEGER, carbs_source TEXT)")
            db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(1000,'food','redacted')")
            db.version = 32
        }
        SqliteCollectorStore(context, name).use { store ->
            assertEquals(1, store.annotations(0, 2_000).size)
            val tables = store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertTrue("physio_parallel_runs" in tables)
            assertTrue("physio_daily_checkpoints" in tables)
        }
        context.deleteDatabase(name)
    }
}
