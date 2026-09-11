package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.BolusPurpose
import com.diapilot.core.analysis.LabelLanguage
import com.diapilot.core.analysis.NoteTag
import io.github.obdosok.diapilot.R

/**
 * Display labels for STORED keys: bolus purposes and the note tags the
 * composer's chips write (context, activity, absorption and rescue tags).
 *
 * The database holds language-neutral keys ("correction", "walk · 40 min",
 * "dextrose ×2"); rows written by older builds may still hold the Russian
 * tokens, and people type either language. Every form of a known value is
 * rendered through a string resource in the UI language; anything else (the
 * user's own words) comes back unchanged.
 */
object TokenText {
    private fun purposeRes(p: BolusPurpose): Int = when (p) {
        BolusPurpose.CORRECTION -> R.string.bolus_purpose_correction
        BolusPurpose.MEAL -> R.string.bolus_purpose_meal
        BolusPurpose.TOP_UP -> R.string.bolus_purpose_top_up
        BolusPurpose.PRIME -> R.string.bolus_purpose_air
    }

    private fun tagRes(t: NoteTag): Int = when (t) {
        NoteTag.INJECTION_BELLY -> R.string.note_tag_injection_belly
        NoteTag.INJECTION_THIGH -> R.string.note_tag_injection_thigh
        NoteTag.INJECTION_ARM -> R.string.note_tag_injection_arm
        NoteTag.SLEEP_DEBT -> R.string.note_tag_sleep_debt
        NoteTag.ILL -> R.string.note_tag_ill
        NoteTag.STRESS -> R.string.note_tag_stress
        NoteTag.ALCOHOL -> R.string.note_tag_alcohol
        NoteTag.NEW_SENSOR -> R.string.note_tag_new_sensor
        NoteTag.NEW_CARTRIDGE -> R.string.note_tag_new_cartridge
        NoteTag.PHOTO -> R.string.note_tag_photo
        NoteTag.CARTRIDGE_WARMING -> R.string.note_tag_cartridge_warming
        // A composer starter chip; the same key is the system dawn meal label.
        NoteTag.DAWN -> R.string.sys_label_dawn
        NoteTag.WORKOUT -> R.string.note_tag_workout
        NoteTag.WALK -> R.string.note_tag_walk
        NoteTag.SPORT -> R.string.note_tag_sport
        NoteTag.GYM -> R.string.note_tag_gym
        NoteTag.RUN -> R.string.note_tag_run
        NoteTag.BIKE -> R.string.note_tag_bike
        NoteTag.PSYLLIUM -> R.string.note_tag_psyllium
        NoteTag.FIBER -> R.string.note_tag_fiber
        NoteTag.FATTY -> R.string.note_tag_fatty
        NoteTag.VINEGAR -> R.string.note_tag_vinegar
        NoteTag.PROTEIN_FIRST -> R.string.note_tag_protein_first
        NoteTag.DEXTROSE -> R.string.note_tag_dextrose
    }

    private val MINUTES_SUFFIX = Regex("""^(\d+)\s*(?:min|мин)\.?$""", RegexOption.IGNORE_CASE)
    private val COUNT_SUFFIX = Regex("""^(\S+)\s*×\s*(\d+)$""")

    /** The label of a purpose, in the UI language. */
    fun bolusPurpose(context: Context, purpose: BolusPurpose): String =
        context.localized().getString(purposeRes(purpose))

    /** The label language of the UI, for the "keep the viewer's own spelling" rule. */
    private fun uiLanguage(context: Context): LabelLanguage =
        if (context.localized().isRussianUi()) LabelLanguage.RU else LabelLanguage.EN

    /** "correction" for any stored form of a purpose; null stays null, unknown text stays as is.
     *  A word already in the UI language is shown as it was written. */
    fun bolusPurpose(context: Context, stored: String?): String? {
        if (stored == null) return null
        val p = BolusPurpose.of(stored) ?: return stored
        val own = BolusPurpose.WORDS[uiLanguage(context)]?.get(p).orEmpty()
        if (stored.trim().lowercase() in own) return stored
        return bolusPurpose(context, p)
    }

    /** The label of a tag, in the UI language. */
    fun noteTag(context: Context, tag: NoteTag): String = context.localized().getString(tagRes(tag))

    /**
     * A note's text for display. Known tag heads are labelled, the duration
     * suffix activity notes carry ("walk · 40 min") and the rescue count
     * ("dextrose ×2") follow the language; everything else is the user's text
     * and comes back unchanged. A note already written in the UI language's own
     * words (a Russian note in the Russian UI) is shown exactly as written.
     */
    fun noteTag(context: Context, content: String): String {
        val res = context.localized()
        val lang = uiLanguage(context)
        val head0 = content.substringBefore('·').trim().substringBefore('×').trim().lowercase()
        val ownWords = (NoteTag.of(head0)?.let { NoteTag.WORDS[lang]?.get(it) }
            ?: BolusPurpose.of(head0)?.let { BolusPurpose.WORDS[lang]?.get(it) }).orEmpty()
        val minutesOwn = lang == LabelLanguage.RU || !content.contains("мин")
        if (head0 in ownWords && minutesOwn) return content
        COUNT_SUFFIX.matchEntire(content.trim())?.let { m ->
            if (NoteTag.of(m.groupValues[1]) == NoteTag.DEXTROSE) {
                return "${res.getString(R.string.note_tag_dextrose)} ×${m.groupValues[2]}"
            }
        }
        val head = content.substringBefore('·').trim()
        val label = NoteTag.of(head)?.let { res.getString(tagRes(it)) }
            ?: BolusPurpose.of(head)?.let { res.getString(purposeRes(it)) }
            ?: return content
        if (!content.contains('·')) return label
        val tail = content.substringAfter('·').trim()
        val minutes = MINUTES_SUFFIX.find(tail)?.groupValues?.get(1)?.toIntOrNull()
        return "$label · " + (minutes?.let { res.getString(R.string.note_tag_minutes, it) } ?: tail)
    }
}
