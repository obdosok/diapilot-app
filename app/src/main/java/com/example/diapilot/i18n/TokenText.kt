package com.example.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.RESCUE_NOTE_PREFIX
import com.example.diapilot.R

/**
 * Display labels for STORED Russian tokens: bolus purposes and the note tags
 * the composer's chips write (context, activity and absorption tags).
 *
 * The database keeps the token as it is — analytics, the command guard and
 * the tag matchers in :core compare against it — so a chip still writes the
 * correction token and only its label changes. In Russian the stored text is shown
 * as it is; in English a known token is replaced by its label and anything
 * else (the user's own words) is shown unchanged.
 */
object TokenText {
    // Keys are the stored tokens (data, not UI text): core's BOLUS_PURPOSES.
    private val PURPOSES: Map<String, Int> = mapOf(
        "коррекция" to R.string.bolus_purpose_correction,
        "на еду" to R.string.bolus_purpose_meal,
        "докол" to R.string.bolus_purpose_top_up,
        "воздух" to R.string.bolus_purpose_air,
    )

    // Keys are the stored tokens (data, not UI text): core's CONTEXT_TAGS,
    // ACTIVITY_TAGS, ABSORPTION_SLOW_TAGS and RESCUE_NOTE_PREFIX.
    internal val NOTE_TAGS: Map<String, Int> = mapOf(
        "укол в живот" to R.string.note_tag_injection_belly,
        "укол в бедро" to R.string.note_tag_injection_thigh,
        "укол в руку" to R.string.note_tag_injection_arm,
        "недосып" to R.string.note_tag_sleep_debt,
        "болею" to R.string.note_tag_ill,
        "стресс" to R.string.note_tag_stress,
        "алкоголь" to R.string.note_tag_alcohol,
        "тренировка" to R.string.note_tag_workout,
        "прогулка" to R.string.note_tag_walk,
        "новый сенсор" to R.string.note_tag_new_sensor,
        "новая ампула" to R.string.note_tag_new_cartridge,
        "нагрев ампулы" to R.string.note_tag_cartridge_warming,
        // A composer starter chip; the same text is the system dawn meal label.
        "утренняя заря" to R.string.sys_label_dawn,
        "фото" to R.string.note_tag_photo,
        "спорт" to R.string.note_tag_sport,
        "зал" to R.string.note_tag_gym,
        "бег" to R.string.note_tag_run,
        "велосипед" to R.string.note_tag_bike,
        "псилиум" to R.string.note_tag_psyllium,
        "псиллиум" to R.string.note_tag_psyllium,
        "клетчатка" to R.string.note_tag_fiber,
        "жирное" to R.string.note_tag_fatty,
        "уксус" to R.string.note_tag_vinegar,
        "белок первым" to R.string.note_tag_protein_first,
        RESCUE_NOTE_PREFIX to R.string.note_tag_dextrose,
    ) + PURPOSES

    private val MINUTES_SUFFIX = Regex("""^(\d+)\s*мин$""")

    /** "correction" for the stored purpose token; null stays null, unknown text stays as is. */
    fun bolusPurpose(context: Context, stored: String?): String? {
        if (stored == null) return null
        val res = context.localized()
        if (res.isRussianUi()) return stored
        return PURPOSES[stored.trim().lowercase()]?.let { res.getString(it) } ?: stored
    }

    /**
     * A note's text for display. Known tag heads are labelled, and the duration
     * suffix activity notes carry ("<tag> · 40 min") follows the language;
     * everything else is the user's text and comes back unchanged.
     */
    fun noteTag(context: Context, content: String): String {
        val res = context.localized()
        if (res.isRussianUi()) return content
        val head = content.substringBefore('·').trim()
        val id = NOTE_TAGS[head.lowercase()] ?: return content
        val label = res.getString(id)
        if (!content.contains('·')) return label
        val tail = content.substringAfter('·').trim()
        val minutes = MINUTES_SUFFIX.find(tail)?.groupValues?.get(1)?.toIntOrNull()
        return "$label · " + (minutes?.let { res.getString(R.string.note_tag_minutes, it) } ?: tail)
    }
}
