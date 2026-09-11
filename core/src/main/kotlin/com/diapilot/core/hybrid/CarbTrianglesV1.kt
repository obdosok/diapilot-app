package com.diapilot.core.hybrid

/**
 * The three population carbohydrate triangles every dish is mixed from.
 *
 * WHY THIS IS A TYPE AND NOT TWELVE LITERALS. These numbers decide the SHAPE of
 * every meal the model has never seen a pool for — which is most meals — and
 * they sat inline inside `foodShapeMixture`, invisible from any bench and
 * unreachable by any sweep. Meanwhile `carbSpread` and `carbTimeScale`, which do
 * nothing but SCALE these same twelve numbers, both had knobs. So the derived
 * quantities were adjustable and the primitives were not, which is backwards:
 * a sweep of `carbTimeScale` can only stretch a wrong triangle uniformly, and
 * the one measurement that matters here — that beer, ice cream and a smoothie
 * all peak at the same minute — is a statement about these numbers directly.
 *
 * THE DEFAULTS ARE MEASURED (M-90/M-93). They were the population
 * literals until then — fast 5/25/75, medium 10/55/180, slow 15/95/330 — and
 * moved because fitting them on a batch of episodes with insulin FROZEN at the
 * fitted landmarks, the ramp off and amplitude locked cut median shape by
 * roughly a third and MAE at the hour by roughly two thirds. The change
 * survives the choice of ISF: it wins at multiple tested ISF values alike,
 * so it is not an ISF compensation wearing a food model's clothes.
 *
 * The medium triangle moved most — peak 55 -> 33, end 180 -> 91 — and that is
 * the finding: "medium" carbohydrate behaves almost as fast as fast carbohydrate,
 * which is why the model under-called every early rise. The FAST triangle barely
 * moved then (25 -> 19, 75 -> 74): it was the one already right.
 *
 * THOSE FAST NUMBERS ARE NO LONGER WHAT SHIPS — read the block below before
 * quoting them. Shortly after, fast was collapsed INTO medium, so the shipped
 * fast triangle is 10/33/91, not 10/19/74. This paragraph records how medium
 * got its values; it stopped describing the fast one once that collapse landed.
 *
 * Shipped under the standing food-shape exemption, which allows timing and
 * shape to land default-on. It does NOT cover amplitude, and amplitude is not
 * touched here.
 *
 * `macroShare` is how strongly a type responds to the gastric terms (fibre, fat,
 * protein). It USED to grade them — slow taking the full delay, fast under half
 * — and all three now carry the same 0.48, because equalising them scored
 * -0.004 with 12 days better against 5 (see below). The per-type field stays so
 * they can diverge again without a migration.
 * MEDIUM IS THE ANCHOR — `carbSpread` collapses fast and slow toward it, not
 * toward the mixture's own centroid, so that a dish does not change shape merely
 * because its split changed.
 */
/*
 * FAST NOW EQUALS MEDIUM, and the macro shares are one number — measured,
 * not tidied.
 *
 * The audit that motivated it asked what each fitted quantity is worth. Across
 * a plausible range of its own uncertainty, every one of the twelve triangle
 * numbers moved the balance score by less than 0.06 mmol, against 6.10 for ISF
 * and 4.78 for carbohydrate amplitude. Collapsing fast into medium then scored,
 * day-paired on the held-out half:
 *
 *   balance   +0.000  (8 days better, 8 worse — exactly neutral)
 *   MAE@60    -0.326  (11 days better, 3 worse)
 *
 * A third of a millimole at the hour, on the horizon where the hypo alert
 * lives, for free. Equal macro shares are free outright (-0.004, 12/5).
 *
 * WHY THE DISTINCTION IS COLLAPSED AND NOT DELETED. It would be easy to read
 * this as «the body does not tell fast from medium» and remove the capability.
 * That reading is refuted by the project's own measurement: M-104 found the
 * carb-type label moves the peak five times as much once the caloric queue is
 * loosened. The queue SUPPRESSES the distinction; it does not prove the body
 * lacks it. So the shape of the data model stays, carrying identical values, and
 * the day the queue changes the three triangles can diverge again without a
 * migration.
 *
 * The same audit refused two neighbouring temptations: removing the sieve or the
 * caloric queue costs +0.095 at the hour on 10 days against 2, so neither is a
 * rudiment however inert its own knob looks.
 *
 * Shipped default-on under the standing food-shape exemption, which covers
 * timing and shape. Amplitude is untouched.
 */
data class CarbTrianglesV1(
    val fastDelayMin: Double = 10.0,
    val fastPeakMin: Double = 33.0,
    val fastEndMin: Double = 91.0,
    val fastMacroShare: Double = 0.48,
    val mediumDelayMin: Double = 10.0,
    val mediumPeakMin: Double = 33.0,
    val mediumEndMin: Double = 91.0,
    val mediumMacroShare: Double = 0.48,
    val slowDelayMin: Double = 15.0,
    val slowPeakMin: Double = 63.0,
    val slowEndMin: Double = 326.0,
    val slowMacroShare: Double = 0.48,
) {
    companion object {
        /**
         * Bump on ANY change to the defaults above.
         *
         * `PhysioRuntime.artifactId` is built from the model JSON's SHA and a
         * state hash — neither notices a Kotlin constant moving. Without this in
         * the id, editing a triangle silently writes the new generation into
         * `physio_parallel_runs` under the OLD artifact id, and every paired A/B
         * then averages two food models. That is exactly the blending the
         * shadow-tag rule exists to prevent, and the physio arm had no equivalent
         * until now.
         */
        const val GENERATION = "tri-v3-fast-equals-medium"

        /**
         * THE SINGLE SOURCE OF SHIPPED TRIANGLES FOR FITTING.
         *
         * Exists because a duplicate of these numbers drifted apart and cost
         * real debugging time. `PhysioAutoFitV1.Knobs` kept ITS OWN defaults —
         * the population 5/25/75, 10/55/180, 15/95/330 and macro shares
         * 0.45/0.75/1.00, i.e. the values from BEFORE the M-93 and M-120
         * revisions. Any stand that built `Knobs(...)` without explicitly
         * passing triangles was running a different food model than the
         * device: its average triangle was twice as slow and twice as long
         * as the shipped one.
         *
         * The direction matched an observed gap in acceptance: slow-arriving
         * food reaches the horizon worse, so the stand systematically
         * undershot where the device overshot — "the device sees more food",
         * whose cause was hunted for and not found by checking and ruling out
         * evidence revisions, meal duration, `decorateFood`, carbSens and
         * spread.
         *
         * These twelve numbers must not be duplicated anywhere else. Reference
         * this constant instead.
         */
        val SHIPPED = CarbTrianglesV1()
    }
}
