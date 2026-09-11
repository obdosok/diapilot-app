/**
 * Display-unit formatting: mmol/L (comma decimal, one fraction digit) or
 * mg/dL (integer). Storage and every model stay in mmol/L — these helpers
 * exist ONLY at the presentation boundary.
 */
package com.diapilot.core.analysis

const val MGDL_PER_MMOL_F = 18.0182

/** "10,2" or "184". */
fun fmtBg(mmol: Double, mgdl: Boolean): String =
    if (mgdl) Math.round(mmol * MGDL_PER_MMOL_F).toString()
    else String.format(java.util.Locale.ROOT, "%.1f", mmol).replace('.', ',')

/** Signed: "+0,3" or "+5". */
fun fmtBgDelta(mmol: Double, mgdl: Boolean): String =
    if (mgdl) String.format(java.util.Locale.ROOT, "%+d", Math.round(mmol * MGDL_PER_MMOL_F))
    else String.format(java.util.Locale.ROOT, "%+.1f", mmol).replace('.', ',')

fun unitLabel(mgdl: Boolean): String = if (mgdl) "мг/дл" else "ммоль/л"
