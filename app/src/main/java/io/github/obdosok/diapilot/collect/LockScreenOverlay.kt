package io.github.obdosok.diapilot.collect

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small floating BG chip drawn OVER the lock screen (xDrip-style), so the
 * value is glanceable without unlocking and without a notification card. Uses
 * a TYPE_APPLICATION_OVERLAY window; needs the "draw over other apps" grant
 * (and, on MIUI, the separate "show on lock screen" toggle).
 *
 * Shown only while the screen is on AND the keyguard is locked — never over
 * the user's actual apps. Driven by [CollectorService]: the screen receiver
 * toggles visibility, each reading updates the text.
 */
object LockScreenOverlay {
    private const val TAG = "LockOverlay"
    private var view: TextView? = null
    private var lastText: String = "—"

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context)

    /** Show (or refresh) the chip. No-op if disabled, ungranted, or already up. */
    fun show(context: Context, text: String = lastText) {
        lastText = text
        if (!io.github.obdosok.diapilot.data.Settings.overlayEnabled(context)) return
        if (!canDraw(context)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            if (view == null) {
                val chip = TextView(context).apply {
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    setPadding(44, 20, 44, 20)
                    background = GradientDrawable().apply {
                        cornerRadius = 48f
                        setColor(0xCC1C1C1E.toInt())
                        setStroke(2, 0x33FFFFFF)
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 160
                }
                chip.text = text
                wm.addView(chip, params)
                view = chip
            } else {
                view?.text = text
            }
        } catch (e: Exception) {
            Log.w(TAG, "show failed: ${e.message}")
            view = null
        }
    }

    fun update(context: Context, text: String) {
        lastText = text
        if (view != null) view?.text = text
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "hide failed: ${e.message}")
        }
    }
}
