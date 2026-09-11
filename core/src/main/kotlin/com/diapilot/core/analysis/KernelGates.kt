/**
 * The ISF/kernel gates the PHONE actually runs — one definition, shared.
 *
 * They lived in three copies (TwinCache, IsfTagStudy, KernelWindowStudy) and
 * the walk-forward silently ran a fourth (stock [IsfConfig] defaults). That is
 * how one session produced a day of conclusions measured against a branch the
 * user's settings never take, and how the walk-forward baseline quoted in the
 * docs came to describe gates production does not use.
 *
 * A study that wants to VARY a gate copies one of these and edits the field —
 * so the variation is visible next to the production value it deviates from.
 */
package com.diapilot.core.analysis

object KernelGates {
    /**
     * Soft detection config: kernel building favors episode yield; the kernel
     * median is robust to the extra noise.
     */
    val PRODUCTION = IsfConfig(
        startBg = 7.0,
        bolusWindowMin = -120.0..180.0,
        carbWindowMin = -90.0..180.0,
        // Aligned with the disqualifying windows for the same reason as the
        // FAST config below: events were checked to +180 while bgEnd was the
        // median over +150..+210, so anything at +190 moved the measurement
        // without being allowed to reject the episode.
        endWindowMin = 150.0..180.0,
        obsWindowMin = 180.0,
        trustCorrectionTag = true,
    )

    /**
     * Fast analogs (Fiasp/Lyumjev, DIA <= 3.5h): the action is over sooner, so
     * episodes need less isolation — more clean episodes, and the evaluation
     * window stops before the flat tail dilutes the curve.
     *
     * NOT the branch this user takes, checked on the device: the
     * snapshot carries `keyFast = false`, so the user's DIA setting is above 210
     * and production runs [PRODUCTION]. The previous line here asserted the
     * opposite as fact and had gone stale — it briefly misled a session into
     * reasoning about the wrong config. The switch is `Settings.insulinDiaMin
     * <= 210`, a USER SETTING rather than the measured curve, which is A-26's
     * open tail; and flipping it is not free (M-23).
     */
    val PRODUCTION_FAST = IsfConfig(
        startBg = 7.0,
        bolusWindowMin = -90.0..150.0,
        carbWindowMin = -90.0..150.0,
        // ALIGNED WITH THE DISQUALIFYING WINDOWS, and placed where v7's action
        // actually ends. Two separate defects were sitting here.
        //
        // 1. A GAP. Events were checked over -90..+150 while bgEnd was the median
        //    over +120..+180. A bolus or meal at +160 therefore did NOT reject the
        //    episode but DID sit inside the interval the measurement is taken
        //    over — it could move the number without ever being allowed to veto
        //    it. Disqualification must cover measurement; now it exactly does.
        // 2. THE BOUNDARY WAS IN THE WRONG PLACE for the v7 kernel. Measured on
        //    the monotone curve: 75% of the action is done at 90 min, 94% at 120,
        //    plateau at ~145-150. Measuring at 90-120 (an earlier candidate)
        //    reads only 75-94% of the drop and UNDERSTATES ISF — conservative,
        //    but wrong. 120-150 reads 94%-100%.
        endWindowMin = 120.0..150.0,
        obsWindowMin = 150.0,
        trustCorrectionTag = true,
    )

}
