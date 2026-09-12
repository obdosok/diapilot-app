package io.github.obdosok.diapilot

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.data.Stores

/**
 * The composition root: what a screen is allowed to reach for.
 *
 * The activity builds ONE graph and publishes it through [LocalAppGraph], so
 * every composable below receives its dependencies instead of calling
 * `Stores.get(context)` on its own. Nothing about the lifetime or the threading
 * of the store changes — see [store]. What changes is that the UI layer no
 * longer names the process-wide singleton, which is what made a screen
 * impossible to render without the real database.
 *
 * Services, workers, receivers and the widget keep reaching for [Stores]
 * directly: they run outside any composition, so there is no graph to hand
 * them.
 *
 * `Settings` and the `*Runtime` objects are deliberately NOT here yet. They are
 * static entry points with their own caches, and moving them behind the graph
 * cannot be done without touching those caches — which is a separate change.
 */
class AppGraph(private val context: Context) {

    /**
     * The shared database handle, resolved on every read and NOT held here.
     *
     * A restore closes the process-wide handle and opens a new one over the
     * replaced file (`Stores.close()` in `BackupRestore`); a graph that kept the
     * previous instance would go on handing the screens a closed database.
     * Resolving per read also leaves the FIRST open exactly where it happens
     * today — on whichever thread asks first, never moved onto the composition.
     */
    val store: CollectorStore get() = Stores.get(context)
}

/**
 * The graph of the current composition. There is no default on purpose: a
 * screen rendered outside [MainActivity] — a test, for instance — has to say
 * which graph it runs on instead of silently reaching for the process-wide
 * store.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error(
        "No AppGraph in this composition. Wrap the content in " +
            "CompositionLocalProvider(LocalAppGraph provides AppGraph(context)).",
    )
}
