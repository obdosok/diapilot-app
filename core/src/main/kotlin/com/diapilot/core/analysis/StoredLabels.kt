/**
 * Language-neutral stored labels.
 *
 * Everything the app WRITES into a structured field (a bolus purpose, a note
 * tag written by a composer chip, the rescue-carbs note, a system meal label,
 * an insulin product default) is a stable English snake_case KEY. Keys are what
 * gets stored and compared; the UI renders them through string resources in
 * every language, so adding a language means adding a resource folder and one
 * column of words to the tables below.
 *
 * READING accepts every supported language's words for a key as well as the
 * key itself, because notes are typed by people and databases restored from
 * older backups still hold the Russian tokens the app used to write.
 *
 * [LabelMigrationV1] in the app converts the stored Russian tokens to keys;
 * [canonicalNoteContent] is the one rule it and every write path share.
 */
package com.diapilot.core.analysis

/** Languages whose words the label parser understands. */
enum class LabelLanguage { EN, RU }

/** Build a lowercase `form -> value` index: the key plus every word in every language. */
private fun <T> indexForms(
    values: Iterable<T>,
    key: (T) -> String,
    words: Map<LabelLanguage, Map<T, List<String>>>,
): Map<String, T> = buildMap {
    for (v in values) {
        put(key(v), v)
        for (lang in LabelLanguage.entries) {
            words[lang]?.get(v)?.forEach { put(it.trim().lowercase(), v) }
        }
    }
}

/**
 * Why a bolus was given. Stored in `insulin_events.purpose` as [key].
 *
 * PRIME is the air shot that purges a fresh cartridge: insulin that left the
 * pen and never entered the body, excluded from every analytic path.
 *
 * MEAL's key is "meal_bolus", not "meal": purpose words are context tags too
 * (older builds wrote them as notes), and a food note named "meal" must stay
 * food.
 */
enum class BolusPurpose(val key: String) {
    CORRECTION("correction"),
    MEAL("meal_bolus"),
    TOP_UP("top_up"),
    PRIME("prime");

    companion object {
        /** Words people type for each purpose. RU are also the tokens older builds stored. */
        val WORDS: Map<LabelLanguage, Map<BolusPurpose, List<String>>> = mapOf(
            LabelLanguage.EN to mapOf(
                CORRECTION to listOf("correction"),
                MEAL to listOf("for food"),
                TOP_UP to listOf("top-up", "top up"),
                PRIME to listOf("air shot", "priming"),
            ),
            LabelLanguage.RU to mapOf(
                CORRECTION to listOf("коррекция"),
                MEAL to listOf("на еду"),
                TOP_UP to listOf("докол"),
                PRIME to listOf("воздух"),
            ),
        )

        private val BY_FORM: Map<String, BolusPurpose> = indexForms(entries, { it.key }, WORDS)

        /** Every accepted spelling (keys and words), lowercase. */
        val ALL_FORMS: Set<String> get() = BY_FORM.keys

        /** The purpose [text] names in any supported language, or null. */
        fun of(text: String?): BolusPurpose? = text?.trim()?.lowercase()?.let(BY_FORM::get)

        /** The stored key for [text]; unknown text is returned unchanged, null stays null. */
        fun canonical(text: String?): String? = text?.let { of(it)?.key ?: it }
    }
}

/** True for the air shot ([BolusPurpose.PRIME]) in any stored form. */
fun isPrimePurpose(purpose: String?): Boolean = BolusPurpose.of(purpose) == BolusPurpose.PRIME

/** Stored purpose values that mean the air shot — for SQL filters (`purpose NOT IN (...)`). */
val PRIME_PURPOSE_FORMS: List<String> =
    listOf(BolusPurpose.PRIME.key) + BolusPurpose.WORDS.values.flatMap { it[BolusPurpose.PRIME].orEmpty() }

/** Tag families; they decide which analytic set a tag belongs to. */
enum class NoteTagGroup { CONTEXT, ACTIVITY, ABSORPTION, RESCUE, OTHER }

/**
 * A note tag: the whole content of a note written by a composer chip (or typed
 * as a single word), optionally followed by " · <detail>" — activity notes
 * carry their duration there ("walk · 40 min").
 */
enum class NoteTag(val key: String, val group: NoteTagGroup) {
    INJECTION_BELLY("injection_belly", NoteTagGroup.CONTEXT),
    INJECTION_THIGH("injection_thigh", NoteTagGroup.CONTEXT),
    INJECTION_ARM("injection_arm", NoteTagGroup.CONTEXT),
    SLEEP_DEBT("sleep_debt", NoteTagGroup.CONTEXT),
    ILL("ill", NoteTagGroup.CONTEXT),
    STRESS("stress", NoteTagGroup.CONTEXT),
    ALCOHOL("alcohol", NoteTagGroup.CONTEXT),
    NEW_SENSOR("new_sensor", NoteTagGroup.CONTEXT),
    NEW_CARTRIDGE("new_cartridge", NoteTagGroup.CONTEXT),
    PHOTO("photo", NoteTagGroup.CONTEXT),
    CARTRIDGE_WARMING("cartridge_warming", NoteTagGroup.OTHER),

    /** A composer starter chip; the same key is the system meal label [SysLabels.DAWN]. */
    DAWN("dawn", NoteTagGroup.OTHER),
    WORKOUT("workout", NoteTagGroup.ACTIVITY),
    WALK("walk", NoteTagGroup.ACTIVITY),
    SPORT("sport", NoteTagGroup.ACTIVITY),
    GYM("gym", NoteTagGroup.ACTIVITY),
    RUN("run", NoteTagGroup.ACTIVITY),
    BIKE("bike", NoteTagGroup.ACTIVITY),
    PSYLLIUM("psyllium", NoteTagGroup.ABSORPTION),
    FIBER("fiber", NoteTagGroup.ABSORPTION),
    FATTY("fatty", NoteTagGroup.ABSORPTION),
    VINEGAR("vinegar", NoteTagGroup.ABSORPTION),
    PROTEIN_FIRST("protein_first", NoteTagGroup.ABSORPTION),

    /** Rescue carbs; the note reads "dextrose ×N" ([RESCUE_NOTE_PREFIX]). */
    DEXTROSE("dextrose", NoteTagGroup.RESCUE);

    companion object {
        /** Words people type for each tag. RU are also the tokens older builds stored. */
        val WORDS: Map<LabelLanguage, Map<NoteTag, List<String>>> = mapOf(
            LabelLanguage.EN to mapOf(
                INJECTION_BELLY to listOf("injection in the belly"),
                INJECTION_THIGH to listOf("injection in the thigh"),
                INJECTION_ARM to listOf("injection in the arm"),
                SLEEP_DEBT to listOf("sleep debt"),
                ILL to listOf("sick"),
                NEW_SENSOR to listOf("new sensor"),
                NEW_CARTRIDGE to listOf("new cartridge"),
                CARTRIDGE_WARMING to listOf("cartridge warming"),
                DAWN to listOf("dawn phenomenon"),
                BIKE to listOf("cycling"),
                FATTY to listOf("fatty food"),
                PROTEIN_FIRST to listOf("protein first"),
            ),
            LabelLanguage.RU to mapOf(
                INJECTION_BELLY to listOf("укол в живот"),
                INJECTION_THIGH to listOf("укол в бедро"),
                INJECTION_ARM to listOf("укол в руку"),
                SLEEP_DEBT to listOf("недосып"),
                ILL to listOf("болею"),
                STRESS to listOf("стресс"),
                ALCOHOL to listOf("алкоголь"),
                NEW_SENSOR to listOf("новый сенсор"),
                NEW_CARTRIDGE to listOf("новая ампула"),
                PHOTO to listOf("фото"),
                CARTRIDGE_WARMING to listOf("нагрев ампулы"),
                DAWN to listOf("утренняя заря"),
                WORKOUT to listOf("тренировка"),
                WALK to listOf("прогулка"),
                SPORT to listOf("спорт"),
                GYM to listOf("зал"),
                RUN to listOf("бег"),
                BIKE to listOf("велосипед"),
                PSYLLIUM to listOf("псилиум", "псиллиум"),
                FIBER to listOf("клетчатка"),
                FATTY to listOf("жирное"),
                VINEGAR to listOf("уксус"),
                PROTEIN_FIRST to listOf("белок первым"),
                DEXTROSE to listOf("декстроза"),
            ),
        )

        private val BY_FORM: Map<String, NoteTag> = indexForms(entries, { it.key }, WORDS)

        /** Every accepted spelling of [tags] (keys and words), lowercase. */
        fun formsOf(tags: Collection<NoteTag>): Set<String> =
            BY_FORM.filterValues { it in tags }.keys

        /** The tag [text] names exactly (whole text, any language), or null. */
        fun of(text: String?): NoteTag? = text?.trim()?.lowercase()?.let(BY_FORM::get)

        /** The tag at the head of a note (the part before "·"), or null. */
        fun ofHead(content: String): NoteTag? = of(content.substringBefore('·'))
    }
}

/** Minutes in an activity note's duration suffix, "· 40 min" or the older Russian unit. */
val ACTIVITY_MINUTES_REGEX = Regex("""(\d+)\s*(?:min|мин)""", RegexOption.IGNORE_CASE)

/** The canonical activity note: "walk · 40 min". */
fun activityNote(tag: NoteTag, minutes: Int): String = "${tag.key} · $minutes min"

/** The canonical rescue note: "dextrose ×N". */
fun rescueNote(count: Int): String = "$RESCUE_NOTE_PREFIX ×$count"

/** Every accepted rescue prefix, lowercase ("dextrose", and the older Russian word). */
val RESCUE_NOTE_PREFIXES: List<String> =
    listOf(NoteTag.DEXTROSE.key) + NoteTag.WORDS.values.flatMap { it[NoteTag.DEXTROSE].orEmpty() }

/** A rescue-carbs note ("dextrose ×2", or the older Russian form). */
fun isRescueNote(content: String?): Boolean {
    val t = content?.trim() ?: return false
    return RESCUE_NOTE_PREFIXES.any { t.startsWith(it, ignoreCase = true) }
}

private val TAG_WITH_MINUTES = Regex("""^(.+?)\s*·\s*(\d+)\s*(?:min|мин)\.?$""", RegexOption.IGNORE_CASE)
private val RESCUE_WITH_COUNT = Regex("""^(\S+)\s*×\s*(\d+)$""")

/**
 * The stored form of a note's content: a TAG-SHAPED note becomes its key form,
 * anything else comes back unchanged (the user's own words are never rewritten).
 *
 * Tag-shaped means exactly one of:
 *  - a note tag or bolus purpose in any supported language (-> "walk");
 *  - a tag plus a minute suffix (-> "walk · 40 min");
 *  - the rescue note (-> "dextrose ×2").
 *
 * Idempotent: a key form maps to itself.
 */
fun canonicalNoteContent(content: String): String {
    val t = content.trim()
    NoteTag.of(t)?.let { return it.key }
    BolusPurpose.of(t)?.let { return it.key }
    TAG_WITH_MINUTES.matchEntire(t)?.let { m ->
        NoteTag.of(m.groupValues[1])?.let { return "${it.key} · ${m.groupValues[2]} min" }
    }
    RESCUE_WITH_COUNT.matchEntire(t)?.let { m ->
        if (NoteTag.of(m.groupValues[1]) == NoteTag.DEXTROSE) return rescueNote(m.groupValues[2].toInt())
    }
    return content
}

/**
 * The stored form of a meal label: a system label in any language becomes its
 * key ([SysLabels]), a tag-shaped label follows [canonicalNoteContent] (labels
 * are taken from notes, "dextrose ×2" included), a dish name stays as it is.
 */
fun canonicalMealLabel(name: String): String = SysLabels.keyOf(name) ?: canonicalNoteContent(name)

/**
 * Insulin product placeholders shown until the user names the insulin in the
 * pen. Stored in Settings and published by the events API as [key].
 */
enum class InsulinProductDefault(val key: String) {
    RAPID("rapid"),
    BASAL("basal");

    companion object {
        private val LEGACY: Map<String, InsulinProductDefault> = mapOf(
            "быстрый" to RAPID,
            "базальный" to BASAL,
        )

        fun of(text: String?): InsulinProductDefault? {
            val t = text?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == t } ?: LEGACY[t]
        }

        /** The stored key for a placeholder in any form; a real product name stays as it is. */
        fun canonical(text: String?): String? = text?.let { of(it)?.key ?: it }
    }
}
