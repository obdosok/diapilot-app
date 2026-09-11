package io.github.obdosok.diapilot.data

import com.diapilot.core.hybrid.HybridPersonModel
import org.json.JSONObject
import java.io.InputStream

/**
 * Asset boundary. The parser itself MOVED TO `core`
 * ([com.diapilot.core.hybrid.HybridPersonModelJson]) so the laptop stand can
 * build the very model the phone runs instead of a second copy of it — see
 * discipline #7. This object stays only so the app's call sites and tests keep
 * their import; it must never grow logic of its own.
 */
object HybridPersonModelJson {
    fun read(input: InputStream): HybridPersonModel =
        com.diapilot.core.hybrid.HybridPersonModelJson.read(input)

    fun fromJson(root: JSONObject): HybridPersonModel =
        com.diapilot.core.hybrid.HybridPersonModelJson.fromJson(root)
}
