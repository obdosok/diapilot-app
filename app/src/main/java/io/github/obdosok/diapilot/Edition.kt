package io.github.obdosok.diapilot

/**
 * WHICH EDITION THIS BUILD IS — the one place in the code that knows.
 *
 * Two flavors are built from one trunk (`oss` and `store`, see
 * `app/build.gradle.kts`) and the difference between them is described by two
 * booleans, not by a list of screens. Every gate in the app reads one of these
 * two properties; nothing else reads `BuildConfig.FLAVOR`, and no feature is
 * deleted for an edition — a gated feature stays in the code and is simply not
 * reachable on the side that does not have it.
 *
 * THE TENSE RULE decides where a new feature is born, and it is a rule about
 * the sentence the user reads, not about the algorithm behind it. "Will / in
 * N minutes / eat / remaining" is [prospective]; "now / usually / last time /
 * this week" belongs to both editions. A retrospective surface may use the
 * forecast engine freely — "what the model said an hour ago, against what
 * happened" is a past-tense fact and ships in both.
 *
 * WHAT MUST NOT HAPPEN on the closed side: a screen that opens onto nothing, a
 * card with a heading and no content, or a number that reads `0` because the
 * value behind it was never computed. Where a surface loses its content it has
 * to read as ABSENT — the line is not drawn, the card is not composed, the
 * JSON key is not written. `null` propagates; `0.0` does not.
 *
 * The table of what each edition carries, and the full list of gates, is
 * `docs/editions.md`.
 */
object Edition {

    /**
     * The future tense: the 3 h forecast line and its uncertainty band, What-if,
     * insulin on board, the tuning surfaces (including Auto-fit), the
     * predictive alerts, the `predict*` fields of the local API and of the
     * companion push, and the background model builds those consumers trigger.
     *
     * False does not mean "the engine is gone": `:core` is untouched and the
     * retrospective surfaces still build a model. It means nothing draws,
     * announces or publishes a statement about the future.
     */
    val prospective: Boolean = BuildConfig.EDITION_PROSPECTIVE

    /**
     * Talking to the sensor ourselves: the Libre NFC FRAM read, the OOP2
     * decoded minute stream, and the own BLE link.
     *
     * False leaves the app on the sources another app already owns — xDrip's
     * broadcast and its local web service, Health Connect, the NovoPen scan.
     * NovoPen is NOT sensor-direct: it reads a pen's own dose log, not a
     * glucose sensor, and ships in both editions.
     */
    val sensorDirect: Boolean = BuildConfig.EDITION_SENSOR_DIRECT
}
