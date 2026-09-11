package com.example.diapilot.i18n

import android.content.Context
import com.diapilot.core.libre.LibreStatus
import com.example.diapilot.R

/** Renders :core's Libre sensor state (LibreFram.kt). */
object LibreText {
    fun status(context: Context, s: LibreStatus): String = context.localized().getString(
        when (s) {
            LibreStatus.NOT_STARTED -> R.string.libre_status_not_started
            LibreStatus.STARTING -> R.string.libre_status_starting
            LibreStatus.READY -> R.string.libre_status_ready
            LibreStatus.EXPIRED -> R.string.libre_status_expired
            LibreStatus.SHUTDOWN -> R.string.libre_status_shutdown
            LibreStatus.FAILURE -> R.string.libre_status_failure
            LibreStatus.UNKNOWN -> R.string.libre_status_unknown
        },
    )
}
