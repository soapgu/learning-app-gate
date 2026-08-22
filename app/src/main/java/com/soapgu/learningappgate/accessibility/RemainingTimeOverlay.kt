package com.soapgu.learningappgate.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.R

/** 豆包授权前台期间常驻的不可交互剩余时间胶囊。 */
class RemainingTimeOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var textView: TextView? = null
    private var background: GradientDrawable? = null

    /** 首次调用创建窗口，后续调用只更新文字和颜色，不重复增删 View。 */
    fun showOrUpdate(remainingMs: Long) {
        val displayText = context.getString(
            R.string.remaining_time_overlay,
            RemainingTimeDisplayPolicy.formatDuration(remainingMs),
        )
        textView?.let { view ->
            view.text = displayText
            updateBackground(remainingMs)
            return
        }

        val view = buildView(displayText)
        try {
            windowManager.addView(view, buildLayoutParams())
            textView = view
            updateBackground(remainingMs)
        } catch (error: RuntimeException) {
            Logger.d("显示剩余时间胶囊失败：${error::class.java.simpleName}")
        }
    }

    fun hide() {
        val view = textView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // 窗口尚未附加或已由系统移除，视为清理完成。
        }
        textView = null
        background = null
    }

    private fun buildView(displayText: String): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val drawable = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
        }
        background = drawable
        return TextView(context).apply {
            text = displayText
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = drawable
            elevation = dp(6).toFloat()
        }
    }

    private fun updateBackground(remainingMs: Long) {
        background?.setColor(
            if (RemainingTimeDisplayPolicy.isWarning(remainingMs)) WARNING_BACKGROUND_COLOR
            else NORMAL_BACKGROUND_COLOR,
        )
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 放在豆包标题栏区域，避开正文内容；目标机状态栏下 16dp 可覆盖标题而不遮挡正文。
            y = (16 * density + 0.5f).toInt()
        }
    }

    private companion object {
        const val NORMAL_BACKGROUND_COLOR = 0xD92563EB.toInt()
        const val WARNING_BACKGROUND_COLOR = 0xE01D4ED8.toInt()
    }
}
