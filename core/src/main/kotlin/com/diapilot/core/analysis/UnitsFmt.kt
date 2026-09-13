/**
 * Display-unit formatting: mmol/L (one fraction digit) or mg/dL (integer).
 * Storage and every model stay in mmol/L — these helpers exist ONLY at the
 * presentation boundary.
 *
 * Language-neutral: the decimal separator is the one locale-dependent part,
 * and the app sets it through [BgFormat]. Unit NAMES ("mmol/L" and its
 * Russian form) are not here — the app renders them from its resources.
 */
package com.diapilot.core.analysis

/** The one conversion factor, `collector.MGDL_PER_MMOL`, under the name the
 *  presentation code has always imported. Kept as an alias rather than a second
 *  literal so the two can never drift. */
const val MGDL_PER_MMOL_F = com.diapilot.core.collector.MGDL_PER_MMOL

/**
 * The decimal separator of every formatted number, set by the app to follow
 * its UI language (comma for Russian, point for English). Defaults to the
 * comma the app has always shown, so :core callers and tests that never touch
 * it see the historical "10,2".
 */
object BgFormat {
    @Volatile
    @JvmStatic
    var decimalComma: Boolean = true
}

private fun withSeparator(s: String): String = if (BgFormat.decimalComma) s.replace('.', ',') else s

/** "10,2" / "10.2" or "184". */
fun fmtBg(mmol: Double, mgdl: Boolean): String =
    if (mgdl) Math.round(mmol * MGDL_PER_MMOL_F).toString()
    else withSeparator(String.format(java.util.Locale.ROOT, "%.1f", mmol))

/** Signed: "+0,3" / "+0.3" or "+5". */
fun fmtBgDelta(mmol: Double, mgdl: Boolean): String =
    if (mgdl) String.format(java.util.Locale.ROOT, "%+d", Math.round(mmol * MGDL_PER_MMOL_F))
    else withSeparator(String.format(java.util.Locale.ROOT, "%+.1f", mmol))

/** One fraction digit with the display separator: insulin units, "4,5" / "4.5". */
fun fmtOneDecimal(x: Double): String = withSeparator(String.format(java.util.Locale.ROOT, "%.1f", x))
