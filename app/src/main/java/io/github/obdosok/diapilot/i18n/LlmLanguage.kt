package io.github.obdosok.diapilot.i18n

import android.content.Context

/**
 * The language the LLM must answer in, named in English for the prompt.
 *
 * Prompts are English. They end with [replyInstruction] so the answer comes
 * back in the app language, and they ask for the English protocol markers
 * (`CARBS_LINE_PREFIX`, `COMPONENT_LINE_PREFIX`, `GI_LINE_PREFIX`, the meta
 * line ... in core's Carbs.kt) byte for byte, whatever the reply language.
 * The parsers in :core read those markers and, for stored analyses, the
 * older Russian ones as well.
 */
object LlmLanguage {
    /** "Russian" when the app text resolves to Russian, otherwise "English". */
    fun replyLanguage(context: Context): String = if (context.isRussianUi()) "Russian" else "English"

    /** "Reply in Russian." / "Reply in English." — append to a prompt. */
    fun replyInstruction(context: Context): String = "Reply in ${replyLanguage(context)}."
}
