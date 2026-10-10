package com.localfirst.realtimetranslator.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.localfirst.realtimetranslator.model.EnglishSubtitleState
import com.localfirst.realtimetranslator.model.OverlayCaptionText
import com.localfirst.realtimetranslator.model.OverlayVisibility
import com.localfirst.realtimetranslator.model.SessionState

internal data class OverlayFrame(
    val state: SessionState,
    val subtitles: EnglishSubtitleState,
    val enabled: Boolean,
    val appVisible: Boolean,
    val twoLines: Boolean,
)

/**
 * Two independent capped rows: previous recognized fragment above the newest.
 * No history replay queue is kept in the overlay; the ASR source is never edited.
 */
internal class EnglishCaptionOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var previousView: TextView? = null
    private var currentView: TextView? = null
    private var displayedPrevious = ""
    private var displayedCurrent = ""
    private var lastTextUpdateMs = -1L
    private val expire = Runnable { hide() }

    fun render(frame: OverlayFrame) {
        if (!OverlayVisibility.shouldDisplay(frame.state, frame.enabled, frame.appVisible) ||
            !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!OverlayCaptionText.isFresh(frame.subtitles, now)) {
            hide()
            return
        }
        val (previous, current) = OverlayCaptionText.rows(frame.subtitles, frame.twoLines)
        if (current.isBlank()) {
            hide()
            return
        }
        if (root == null) {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 20, 26))
                    cornerRadius = dp(12).toFloat()
                }
            }
            val old = buildRow(Color.rgb(204, 209, 219))
            val newest = buildRow(Color.WHITE)
            container.addView(old)
            container.addView(newest)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(78)
                alpha = 0.75f // Android 12+ overlay touch-occlusion threshold
            }
            try {
                windowManager.addView(container, params)
                root = container
                previousView = old
                currentView = newest
            } catch (_: RuntimeException) {
                return // Overlay permission may be revoked during a capture.
            }
        }
        if (previous != displayedPrevious || current != displayedCurrent ||
            frame.subtitles.captionUpdatedAtMs != lastTextUpdateMs) {
            displayedPrevious = previous
            displayedCurrent = current
            lastTextUpdateMs = frame.subtitles.captionUpdatedAtMs
            previousView?.apply {
                text = previous
                visibility = if (previous.isBlank()) View.GONE else View.VISIBLE
            }
            currentView?.text = current
            handler.removeCallbacks(expire)
            val remaining = (3_300L - (now - lastTextUpdateMs)).coerceAtLeast(1L)
            handler.postDelayed(expire, remaining)
        }
    }

    private fun buildRow(color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        textSize = 16f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(3), 0, dp(3))
    }

    fun hide() {
        handler.removeCallbacks(expire)
        val old = root
        root = null
        previousView = null
        currentView = null
        displayedPrevious = ""
        displayedCurrent = ""
        lastTextUpdateMs = -1L
        if (old != null) {
            try { windowManager.removeViewImmediate(old) } catch (_: RuntimeException) { }
        }
    }

    fun close() = hide()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
