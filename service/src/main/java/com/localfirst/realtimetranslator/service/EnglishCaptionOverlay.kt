package com.localfirst.realtimetranslator.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
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
)

/** Session-owned, pass-through TYPE_APPLICATION_OVERLAY; does not change audio capture. */
internal class EnglishCaptionOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var textView: TextView? = null
    private var displayed = ""
    private var lastTextUpdateMs = -1L
    private val expire = Runnable { hide() }

    fun render(frame: OverlayFrame) {
        if (!OverlayVisibility.shouldDisplay(frame.state, frame.enabled, frame.appVisible) ||
            !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        // Do not resurrect the previous sentence when the user returns from another app.
        if (!OverlayCaptionText.isFresh(frame.subtitles, SystemClock.elapsedRealtime())) {
            hide()
            return
        }
        val text = OverlayCaptionText.latest(frame.subtitles)
        if (text.isBlank()) {
            hide()
            return
        }
        if (textView == null) {
            val view = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), dp(11), dp(16), dp(11))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 20, 26))
                    cornerRadius = dp(12).toFloat()
                }
            }
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
                alpha = 0.75f // <= Android 12+ untrusted overlay touch-occlusion threshold
            }
            try {
                windowManager.addView(view, params)
                textView = view
            } catch (_: RuntimeException) {
                // The user may revoke permission mid-session; in-app subtitles keep working.
                return
            }
        }
        if (text != displayed || frame.subtitles.captionUpdatedAtMs != lastTextUpdateMs) {
            displayed = text
            lastTextUpdateMs = frame.subtitles.captionUpdatedAtMs
            textView?.text = text
            handler.removeCallbacks(expire)
            val remaining = (3_300L -
                (SystemClock.elapsedRealtime() - lastTextUpdateMs)).coerceAtLeast(1L)
            handler.postDelayed(expire, remaining)
        }
    }

    fun hide() {
        handler.removeCallbacks(expire)
        val old = textView
        textView = null
        displayed = ""
        lastTextUpdateMs = -1L
        if (old != null) {
            try { windowManager.removeViewImmediate(old) } catch (_: RuntimeException) { }
        }
    }

    fun close() = hide()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
