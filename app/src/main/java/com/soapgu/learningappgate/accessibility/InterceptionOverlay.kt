package com.soapgu.learningappgate.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.orhanobut.logger.Logger
import java.io.File

/**
 * M0.3 的无障碍拦截覆盖层。
 *
 * 拦截时在所有应用之上短暂显示一条友好提示，自动消失，不响应任何触摸。
 * 覆盖层的任何异常都不允许影响拦截主流程（HOME 动作）。
 *
 * @param context 必须传入无障碍服务自身的 Context：与官方 GlobalActionBarService 示例一致，
 * TYPE_ACCESSIBILITY_OVERLAY 窗口依赖服务 Context 的 WindowManager（真机验证过
 * Application Context 下 addView 会静默失败）。
 */
class InterceptionOverlay(
    private val context: Context,
    private val diagnosticFile: File? = defaultDiagnosticFile(context),
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null

    /** 显示提示并在 [durationMs] 后自动移除；若已有覆盖层则先移除旧的。 */
    fun show(message: CharSequence, durationMs: Long = DEFAULT_DURATION_MS) {
        hide()
        val view = buildOverlayView(message)
        try {
            windowManager.addView(view, buildLayoutParams())
            appendDiagnostics("addView ok")
        } catch (error: RuntimeException) {
            // 覆盖层失败只损失提示，不阻断拦截；记录原因供诊断（logcat 被系统加密时文件仍可读）。
            appendDiagnostics("addView failed: ${error::class.java.name}: ${error.message}")
            Logger.d("显示拦截覆盖层失败：${error::class.java.simpleName}")
            return
        }
        currentView = view
        handler.postDelayed({ removeView(view) }, durationMs)
    }

    /** 立即移除当前覆盖层；服务销毁时调用，避免窗口残留。 */
    fun hide() {
        currentView?.let(::removeView)
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // 视图尚未附加或已被移除，视为已完成清理。
        }
        if (currentView === view) {
            currentView = null
        }
    }

    private fun buildOverlayView(message: CharSequence): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(BACKGROUND_COLOR)
                cornerRadius = dp(16).toFloat()
            }
            addView(
                TextView(context).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                },
            )
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 无障碍覆盖层窗口：flagRequestAccessibilityOverlay 已在服务 XML 中声明。
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不抢焦点、不响应触摸：孩子无法通过点击关闭或转移提示。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun appendDiagnostics(line: String) {
        val file = diagnosticFile ?: return
        try {
            file.appendText("elapsedRealtime=${SystemClock.elapsedRealtime()} $line\n")
        } catch (_: Exception) {
            // 诊断写入失败时保持静默，不影响拦截主流程。
        }
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 3_000L
        const val BACKGROUND_COLOR = 0xCC1C1C1E.toInt()
        const val DIAGNOSTIC_FILE_NAME = "overlay_diagnostics.txt"

        /** M0 阶段的临时诊断输出：logcat 被系统加密时，通过 adb run-as 读取该文件排查。 */
        fun defaultDiagnosticFile(context: Context): File? {
            return if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                File(context.filesDir, DIAGNOSTIC_FILE_NAME)
            } else {
                null
            }
        }
    }
}
