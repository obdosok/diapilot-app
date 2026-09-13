package io.github.obdosok.diapilot.nfc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.PersonalParams
import com.diapilot.core.pen.InsulinDose
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.nio.ByteBuffer

/**
 * The pen scan's dose fuse. A NovoPen logs up to 60 U per shot; the personal
 * ceiling every other insulin input passes is far lower, and a dose above it
 * must be refused AND reported — a shot the user actually took that vanishes
 * from the toast is the worse outcome — and must not be marked seen, so the
 * next scan reports it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PenDoseSaverTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val now = 1_700_000_000_000L

    /**
     * A dose the way the pen frames it, through the real parser: pen-clock
     * seconds, units × 10 behind the 0xFF00 marker, and the "valid" flag word.
     */
    private fun dose(units: Double, secondsAgo: Long): InsulinDose {
        val reportRelativeTime = 100_000L
        val buf = ByteBuffer.allocate(12)
            .putInt((reportRelativeTime - secondsAgo).toInt())
            .putInt((0xFF000000L or Math.round(units * 10)).toInt())
            .putInt(0x08000000)
        buf.flip()
        return InsulinDose.parse(buf, reportRelativeTime, now)
    }

    private fun scan(vararg doses: InsulinDose) =
        PenNfcScanner.ScanResult(serial = "PEN0001", model = "NovoPen 6", doses = doses.toList(), completed = true)

    private fun withStore(label: String, block: (SqliteCollectorStore) -> Unit) {
        val name = "pen-$label-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use(block)
        context.deleteDatabase(name)
    }

    @Test fun `a dose above the personal fuse is refused, reported and not stored`() = withStore("fuse") { store ->
        val fuse = PersonalParams.DEFAULT.commandMaxBolusUnits
        val r = PenDoseSaver.save(store, scan(dose(fuse + 0.5, 600), dose(4.0, 3_600)))
        assertEquals(1, r.newDoses)
        assertEquals(1, r.refused)
        assertEquals(listOf(4.0), store.bolusesAll(0, now).map { it.units })
    }

    @Test fun `the fuse itself is a dose the pen may log`() = withStore("edge") { store ->
        val fuse = PersonalParams.DEFAULT.commandMaxBolusUnits
        val r = PenDoseSaver.save(store, scan(dose(fuse, 600)))
        assertEquals(1, r.newDoses)
        assertEquals(0, r.refused)
    }

    @Test fun `a refused dose is reported again on the next scan, not forgotten`() = withStore("again") { store ->
        val fuse = PersonalParams.DEFAULT.commandMaxBolusUnits
        val first = PenDoseSaver.save(store, scan(dose(fuse + 3, 600)))
        val second = PenDoseSaver.save(store, scan(dose(fuse + 3, 600)))
        assertEquals(1, first.refused)
        assertEquals(1, second.refused)
        assertEquals(0, second.duplicates)
        assertEquals(0, store.bolusesAll(0, now).size)
    }

    @Test fun `a stored dose is a duplicate on the next scan, as before`() = withStore("dup") { store ->
        PenDoseSaver.save(store, scan(dose(4.0, 600)))
        val again = PenDoseSaver.save(store, scan(dose(4.0, 600)))
        assertEquals(0, again.newDoses)
        assertEquals(1, again.duplicates)
        assertEquals(1, store.bolusesAll(0, now).size)
    }
}
