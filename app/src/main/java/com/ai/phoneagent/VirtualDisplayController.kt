/*
 * Aries AI - Android UI Automation Framework
 * Copyright (C) 2025-2026 ZG0704666
 *
 * Licensed under AGPL-3.0. See LICENSE for details.
 */
package com.ai.phoneagent

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.media.ImageReader
import android.os.SystemClock
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.ai.phoneagent.vdiso.ShizukuVirtualDisplayEngine
import com.ai.phoneagent.vdiso.ImeFocusDeadlockController
import com.ai.phoneagent.input.VirtualAsyncInputInjector

/**
 * 虚拟屏/虚拟隔离模式的 Kotlin 侧门面（Facade）。
 *
 * **用途**
 * - 根据配置决定是否启用"虚拟隔离模式"。
 * - 在虚拟隔离模式下：
 *   - 通过 [ShizukuVirtualDisplayEngine] 创建/复用 VirtualDisplay，并维护当前 `displayId`。
 *   - 提供"截图/输入注入/聚焦显示"等能力给上层（Kotlin UI 与 Python 侧设备抽象）。
 * - 在主屏模式下：
 *   - 确保虚拟屏被停止并清理状态。
 *
 * **典型用法**
 * - 任务开始前（例如 `BackgroundAutomationManager` 执行任务前）：
 *   - `val did = VirtualDisplayController.prepareForTask(context, adbExecPath)`
 * - Python 侧截图：
 *   - `VirtualDisplayController.screenshotPngBase64NonBlack()`（由 Chaquopy 反射调用/静态调用）
 * - 注入点击：
 *   - `VirtualDisplayController.injectTapBestEffort(displayId, x, y)`
 *
 * **使用注意事项**
 * - 依赖 Shizuku：若 binder 不可用或权限未授予，本控制器会返回 `null` 并报错。
 * - 所有对外能力均尽量采用 best-effort：失败返回空字符串/直接 return，避免影响主流程。
 */
object VirtualDisplayController {

    private const val TAG = "AriesVirtualDisplay"

    @Volatile
    private var activeDisplayId: Int? = null

    @Volatile
    private var welcomeShownForCurrentVd: Boolean = false

    @Volatile
    private var lifecycleObserverInstalled: Boolean = false

    // IME 焦点死锁防护控制器
    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var imeFocusController: ImeFocusDeadlockController? = null

    // ─── 焦点完全隔离 v2：连续焦点强制执行（激进模式）───
    // 核心策略：虚拟屏操作全部通过 displayId 定向注入，焦点**始终**驻留主屏（display 0）。
    // **连续高频**强制执行 setFocusedDisplay(0)（100ms 间隔），防止系统任何时刻自动切焦到 VD。
    // 这样用户的物理按键/手势返回**绝大多数时刻**只作用在主屏，虚拟屏仅由程序控制。
    //
    // 为什么改为 100ms：
    // - 人的按键响应时间 > 200ms，所以 100ms 内恢复焦点用户基本感知不到
    // - 系统在 Activity 创建、焦点事件等时刻会自动切焦，100ms 足够快地打断这些自动行为
    // - 虽然 IPC 频繁，但系统级的 wm set-focused-display 操作很轻量
    private const val FOCUS_ENFORCEMENT_INTERVAL_MS = 100L
    private val focusHandler = Handler(Looper.getMainLooper())

    /**
     * **连续焦点强制执行**：每 100ms 检查&恢复焦点到主屏。
     * 这是比"周期性"（2 秒）更激进的隔离策略，确保焦点在任何时刻都尽可能在主屏。
     * 
     * 虽然 IPC 频繁，但获益是：虚拟屏与主屏基本完全独立。
     */
    private val focusEnforcementRunnable = object : Runnable {
        private var lastRestoreMs = 0L

        override fun run() {
            if (activeDisplayId != null && activeDisplayId!! > 0) {
                val now = SystemClock.uptimeMillis()
                // 100ms 间隔内最多调用一次（避免极端频繁）
                if (now - lastRestoreMs >= 80L) {
                    lastRestoreMs = now
                    runCatching { ShizukuVirtualDisplayEngine.restoreFocusToDefaultDisplay() }
                }
            }
            if (isVirtualDisplayStarted()) {
                focusHandler.postDelayed(this, FOCUS_ENFORCEMENT_INTERVAL_MS)
            }
        }
    }

    private fun startFocusEnforcement() {
        focusHandler.removeCallbacks(focusEnforcementRunnable)
        focusHandler.postDelayed(focusEnforcementRunnable, FOCUS_ENFORCEMENT_INTERVAL_MS)
    }

    private fun stopFocusEnforcement() {
        focusHandler.removeCallbacks(focusEnforcementRunnable)
    }

    /**
     * 标记当前是否应该使用虚拟屏模式
     * 在 prepareForTask 被调用时设置，也可以通过 setShouldUseVirtualDisplay 外部设置
     */
    @Volatile
    var shouldUseVirtualDisplay: Boolean = false
        private set

    /**
     * 外部设置是否应该使用虚拟屏模式
     */
    fun setShouldUseVirtualDisplay(value: Boolean) {
        shouldUseVirtualDisplay = value
    }

    /**
     * 检查虚拟屏是否已启动并可用
     */
    fun isVirtualDisplayStarted(): Boolean = activeDisplayId != null && activeDisplayId!! > 0

    /**
     * 获取当前活动的 displayId
     */
    fun getDisplayId(): Int? = activeDisplayId

    /**
     * 获取虚拟屏内容尺寸（用于坐标换算/预览比例）
     * 优先使用 GL dispatcher 的最新 content size；若不可用则回退到 VirtualDisplayConfig。
     */
    fun getContentSizeBestEffort(context: Context? = null): Pair<Int, Int> {
        val latest = runCatching { ShizukuVirtualDisplayEngine.getLatestContentSize() }.getOrNull()
        val lw = latest?.first ?: 0
        val lh = latest?.second ?: 0
        if (lw > 0 && lh > 0) return lw to lh

        val ctx = context ?: return 1088 to 1920 // 默认 1080P 对齐值
        return VirtualDisplayConfig.getSize(ctx)
    }

    /**
     * 准备虚拟屏以执行任务。
     *
     * @param context 上下文
     * @param adbExecPath ADB执行路径（保留参数）
     * @return displayId 或 null
     */
    @Synchronized
    fun prepareForTask(context: Context, adbExecPath: String): Int? {
        // 标记应该使用虚拟屏
        shouldUseVirtualDisplay = true
        
        Log.i(TAG, "prepareForTask: start, pingBinder=${ShizukuBridge.pingBinder()}, hasPermission=${ShizukuBridge.hasPermission()}")
        
        // 检查 Shizuku 权限
        if (!ShizukuBridge.pingBinder()) {
            Log.e(TAG, "Shizuku binder not ready - please start Shizuku app first")
            activeDisplayId = null
            return null
        }
        if (!ShizukuBridge.hasPermission()) {
            Log.e(TAG, "Shizuku permission not granted - please grant permission in Shizuku app")
            activeDisplayId = null
            return null
        }

        Log.i(TAG, "Shizuku permission OK, checking existing display...")

        // 检查是否已存在可用的虚拟屏
        val existing = activeDisplayId
        if (existing != null && ShizukuVirtualDisplayEngine.isStarted()) {
            return existing
        }

        // 从 VirtualDisplayConfig 统一获取分辨率/DPI（已 16 像素对齐）
        val (vdW, vdH) = VirtualDisplayConfig.getSize(context)
        val vdDpi = VirtualDisplayConfig.getDpi(context)

        // 使用 Shizuku VirtualDisplay 引擎创建虚拟屏
        Log.i(TAG, "Creating VirtualDisplay: size=${vdW}x${vdH}, dpi=$vdDpi")
        val r = ShizukuVirtualDisplayEngine.ensureStarted(
            ShizukuVirtualDisplayEngine.Args(
                name = "Aries-Virtual",
                width = vdW,
                height = vdH,
                dpi = vdDpi,
                refreshRate = 0f,
                rotatesWithContent = false,
                ownerPackage = "com.android.shell",
            )
        )
        
        if (r.isSuccess) {
            val did = r.getOrNull()
            Log.i(TAG, "VirtualDisplay created successfully: displayId=$did")
            val isNewOrChanged = (did != null && did != existing)
            activeDisplayId = did

            if (isNewOrChanged) {
                welcomeShownForCurrentVd = false
            }
            if (did != null && !welcomeShownForCurrentVd) {
                welcomeShownForCurrentVd = true
                // 在虚拟屏上启动 WelcomeActivity 作为兜底界面
                // 避免虚拟屏无窗口时黑屏，且 WelcomeActivity 会吞掉 Back 键防止退出
                Log.i(TAG, "Virtual display ready: displayId=$did, launching WelcomeActivity")
                showWelcomeOnActiveDisplayBestEffort(context, did)
            }

            // 完全隔离模式：不启动 IME 强制锁焦（会持续把焦点抢回虚拟屏）
            if (did != null && did > 0) {
                stopImeFocusController()
                // 启动周期性焦点强制执行：确保焦点始终在主屏
                startFocusEnforcement()
            }

            return did
        }
        
        Log.w(TAG, "ShizukuVirtualDisplayEngine.ensureStarted failed", r.exceptionOrNull())
        activeDisplayId = null
        welcomeShownForCurrentVd = false
        return null
    }

    /**
     * 为 Monitor 确保虚拟屏存在
     */
    @Synchronized
    fun ensureVirtualDisplayForMonitor(context: Context): Int? {
        return prepareForTask(context, "")
    }

    /**
     * 启动 Monitor
     */
    @Synchronized
    fun startMonitor(context: Context): Boolean {
        return ensureVirtualDisplayForMonitor(context) != null
    }

    /**
     * 截图并返回 Base64 PNG 字符串
     */
    @JvmStatic
    fun screenshotPngBase64(): String {
        val did = activeDisplayId ?: return ""
        if (!ShizukuVirtualDisplayEngine.isStarted()) return ""
        
        // 严格隔离模式：截图不抢焦点，避免主屏物理按键被路由到虚拟屏

        val bmp = ShizukuVirtualDisplayEngine.captureLatestBitmap().getOrNull() ?: return ""
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    /**
     * 截图并返回非黑帧的 Base64 PNG 字符串
     */
    @JvmStatic
    fun screenshotPngBase64NonBlack(
        maxWaitMs: Long = 1500L,
        pollIntervalMs: Long = 80L,
    ): String {
        val did = activeDisplayId ?: return ""
        if (!ShizukuVirtualDisplayEngine.isStarted()) return ""
        // 严格隔离模式：截图不抢焦点，避免主屏物理按键被路由到虚拟屏

        val deadline = SystemClock.uptimeMillis() + maxWaitMs
        var lastBmp: Bitmap? = null
        while (SystemClock.uptimeMillis() <= deadline) {
            val bmp = ShizukuVirtualDisplayEngine.captureLatestBitmap().getOrNull()
            if (bmp != null) {
                lastBmp?.recycle()
                lastBmp = bmp
                if (!isLikelyBlackBitmap(bmp)) {
                    return try {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val bytes = baos.toByteArray()
                        if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } catch (_: Exception) {
                        ""
                    } finally {
                        runCatching { bmp.recycle() }
                    }
                }
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        runCatching { lastBmp?.recycle() }
        return ""
    }

    /**
     * 焦点管理 — 完全隔离模式下此方法为 NO-OP。
     *
     * 焦点始终驻留在主屏（display 0），虚拟屏操作通过 displayId 定向注入。
     * 不再切换系统焦点到虚拟屏，避免用户的物理按键/手势返回影响虚拟屏。
     */
    @JvmStatic
    fun ensureFocusedDisplayBestEffort(): Boolean {
        // 完全隔离：永远不切焦点到虚拟屏。所有 VD 操作通过 displayId 定向注入。
        return true
    }

    /**
     * 立即恢复焦点到主屏（display 0）。
     * 用于 Activity 启动后、任务结束、虚拟屏清理等场景。
     */
    @JvmStatic
    fun restoreFocusToDefaultDisplayNow() {
        runCatching { ShizukuVirtualDisplayEngine.restoreFocusToDefaultDisplay() }
    }



    private val asyncInputInjector by lazy { VirtualAsyncInputInjector() }

    /**
     * 注入点击事件（best-effort）
     */
    @JvmStatic
    fun injectTapBestEffort(displayId: Int, x: Int, y: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        
        val downTime = SystemClock.uptimeMillis()
        runCatching {
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, x.toFloat(), y.toFloat(), android.view.MotionEvent.ACTION_DOWN)
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, x.toFloat(), y.toFloat(), android.view.MotionEvent.ACTION_UP)
        }
    }

    /**
     * 注入滑动事件（best-effort）
     */
    @JvmStatic
    fun injectSwipeBestEffort(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return

        val downTime = SystemClock.uptimeMillis()
        val dur = durationMs.coerceAtLeast(1)
        runCatching {
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, startX.toFloat(), startY.toFloat(), android.view.MotionEvent.ACTION_DOWN)
            val startTime = SystemClock.uptimeMillis()
            val endTime = startTime + dur
            while (SystemClock.uptimeMillis() < endTime) {
                val elapsed = SystemClock.uptimeMillis() - startTime
                val frac = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val ix = (startX + (endX - startX) * frac).toInt()
                val iy = (startY + (endY - startY) * frac).toInt()
                asyncInputInjector.injectSingleTouchAsync(displayId, downTime, ix.toFloat(), iy.toFloat(), android.view.MotionEvent.ACTION_MOVE)
                try {
                    Thread.sleep(16L)
                } catch (_: Exception) {
                }
            }
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, endX.toFloat(), endY.toFloat(), android.view.MotionEvent.ACTION_UP)
        }
    }

    /**
     * 注入返回事件（best-effort）
     */
    @JvmStatic
    fun injectBackBestEffort(displayId: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        runCatching {
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
        }
    }

    /**
     * 注入 Home 事件（best-effort）
     */
    @JvmStatic
    fun injectHomeBestEffort(displayId: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        runCatching {
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN)
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP)
        }
    }

    /**
     * 注入 粘贴 (Ctrl+V) 事件（best-effort）
     * 用于虚拟屏上的文本输入：先设置剪贴板，再模拟 Ctrl+V 粘贴
     */
    @JvmStatic
    fun injectPasteBestEffort(displayId: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        runCatching {
            asyncInputInjector.injectKeyComboAsync(
                displayId,
                KeyEvent.KEYCODE_V,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        }
    }

    /**
     * 硬重置虚拟屏
     */
    @Synchronized
    fun hardResetOverlay(context: Context) {
        stopFocusEnforcement()
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}
        runCatching { ShizukuVirtualDisplayEngine.restoreFocusToDefaultDisplay() }

        activeDisplayId = null
        welcomeShownForCurrentVd = false
    }

    /**
     * 仅清理 Overlay
     */
    @Synchronized
    fun cleanupOverlayOnly(context: Context) {
        stopFocusEnforcement()
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}
        runCatching { ShizukuVirtualDisplayEngine.restoreFocusToDefaultDisplay() }

        activeDisplayId = null
        welcomeShownForCurrentVd = false
    }

    /**
     * 异步清理
     */
    fun cleanupAsync(context: Context) {
        thread(start = true, name = "VirtualDisplayCleanup") {
            cleanup(context)
        }
    }

    /**
     * 清理虚拟屏，并立即恢复焦点到主屏。
     */
    @Synchronized
    fun cleanup(context: Context) {
        stopFocusEnforcement()
        stopImeFocusController()
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}
        runCatching { ShizukuVirtualDisplayEngine.restoreFocusToDefaultDisplay() }

        activeDisplayId = null
        welcomeShownForCurrentVd = false
        shouldUseVirtualDisplay = false
    }

    // ─── 私有方法 ───

    /**
     * 启动 IME 焦点死锁防护控制器
     */
    private fun startImeFocusController() {
        if (imeFocusController != null) return
        val ctrl = ImeFocusDeadlockController(imeScope)
        ctrl.callback = object : ImeFocusDeadlockController.Callback {
            override fun onLockStateChanged(locked: Boolean, displayId: Int, detail: String) {
                Log.i(TAG, "IME focus lock: locked=$locked displayId=$displayId detail=${detail.take(120)}")
            }
        }
        ctrl.start()
        imeFocusController = ctrl
        Log.i(TAG, "IME focus deadlock controller started")
    }

    /**
     * 停止 IME 焦点死锁防护控制器
     */
    private fun stopImeFocusController() {
        imeFocusController?.stop()
        imeFocusController = null
    }

    /**
     * 在虚拟屏上启动 WelcomeActivity 作为兜底界面
     */
    private fun showWelcomeOnActiveDisplayBestEffort(context: Context, displayId: Int) {
        val appContext = context.applicationContext

        // 路径1: 使用 ActivityOptions.setLaunchDisplayId 启动 WelcomeActivity
        runCatching {
            val intent = Intent(appContext, WelcomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            appContext.startActivity(intent, options.toBundle())
            Log.i(TAG, "WelcomeActivity launched via ActivityOptions on display $displayId")
            // 系统可能自动将焦点切到 VD，延迟恢复到主屏
            schedulePostLaunchFocusRestore()
            return
        }

        // 路径2: 使用 Shizuku shell 命令降级启动
        if (ShizukuBridge.pingBinder() && ShizukuBridge.hasPermission()) {
            thread(start = true, name = "ShowWelcomeOnDisplay") {
                val component = "${appContext.packageName}/.WelcomeActivity"
                val flags = 0x10000000 // FLAG_ACTIVITY_NEW_TASK
                val candidates = listOf(
                    "cmd activity start-activity --user 0 --display $displayId --windowingMode 1 --activity-reorder-to-front -n $component -f $flags",
                    "cmd activity start-activity --user 0 --display $displayId --windowingMode 1 -n $component -f $flags",
                    "cmd activity start-activity --user 0 --display $displayId -n $component -f $flags",
                    "am start --user 0 --display $displayId -n $component -f $flags",
                    "am start --display $displayId -n $component -f $flags",
                )
                for (c in candidates) {
                    val r = runCatching { ShizukuBridge.execResult(c) }.getOrNull()
                    if (r != null) {
                        val err = r.stderrText().trim()
                        val out = r.stdoutText().trim()
                        Log.i(TAG, "showWelcome exec: exitCode=${r.exitCode} cmd=$c stderr=${err.take(200)} stdout=${out.take(200)}")
                        if (r.exitCode == 0) {
                            // 系统可能自动将焦点切到 VD，延迟恢复到主屏
                            schedulePostLaunchFocusRestore()
                            return@thread
                        }
                    }
                }
                Log.w(TAG, "All welcome activity launch candidates failed")
            }
            return
        }

        Log.w(TAG, "Cannot launch WelcomeActivity: Shizuku not available")
    }

    /**
     * Activity 在 VD 上启动后，延迟恢复焦点到主屏。
     * 需要等待系统完成 Activity 创建（~800ms），否则系统可能在恢复后再次自动切焦。
     */
    private fun schedulePostLaunchFocusRestore() {
        focusHandler.postDelayed({
            restoreFocusToDefaultDisplayNow()
            Log.d(TAG, "Post-launch focus restored to main display")
        }, 800)
    }

    private fun isLikelyBlackBitmap(bmp: Bitmap): Boolean {
        return runCatching {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return@runCatching true

            val sampleX = 32
            val sampleY = 32
            val stepX = maxOf(1, w / sampleX)
            val stepY = maxOf(1, h / sampleY)
            var nonBlack = 0
            var total = 0

            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r > 10 || g > 10 || b > 10) {
                        nonBlack++
                        if (nonBlack >= 20) {
                            return@runCatching false
                        }
                    }
                    total++
                    x += stepX
                }
                y += stepY
            }
            total > 0 && nonBlack < 20
        }.getOrElse { true }
    }
}
