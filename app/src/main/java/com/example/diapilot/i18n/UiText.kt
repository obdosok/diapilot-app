package com.example.diapilot.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text decided where no Context is reachable (runtime objects, data classes of
 * a screen state) and rendered where one is (the UI, a notification).
 *
 * ```
 * // data layer — no Context here
 * fun explain(state: State): UiText = UiText.res(R.string.insulin_profile_runtime_prior, state.doses)
 * // Compose
 * Text(line.resolve())
 * // notification / worker
 * builder.setContentText(line.resolve(context))
 * ```
 *
 * An argument that is itself a [UiText] is resolved first, so a sentence can
 * carry a translated fragment as a placeholder value (never glue fragments
 * together outside a resource).
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    /** Already final: the user's own text, a number, a stored label shown as is. */
    data class Raw(val text: String) : UiText

    fun resolve(context: Context): String {
        val res = context.localized()
        fun resolved(args: List<Any>): Array<Any> =
            args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray()
        return when (this) {
            is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *resolved(args))
            is Plural -> res.resources.getQuantityString(id, count, *resolved(args))
            is Raw -> text
        }
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
        fun raw(text: String): UiText = Raw(text)
    }
}

/** Compose form of [UiText.resolve]. */
@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)
