package io.github.obdosok.diapilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.data.Stores
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The graph is a door to the process-wide store, not a second store.
 *
 * Both properties below are load-bearing for the screens: one connection for
 * the whole process (a second one would see its own cache and its own write
 * locks), and no handle held across a restore, which replaces the database file
 * under the running app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppGraphTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun closeStore() = Stores.close()

    @Test fun theGraphHandsOutTheProcessWideStore() {
        val graph = AppGraph(context)
        assertSame(Stores.get(context), graph.store)
        assertSame(graph.store, graph.store)
        assertSame(graph.store, AppGraph(context).store)
    }

    @Test fun theGraphDoesNotHoldTheHandleAcrossARestore() {
        val graph = AppGraph(context)
        val before = graph.store
        Stores.close()
        assertNotSame(before, graph.store)
    }
}
