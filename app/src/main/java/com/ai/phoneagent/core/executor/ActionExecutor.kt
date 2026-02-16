/*
 * Aries AI - Android UI Automation Framework
 * Copyright (C) 2025-2026 ZG0704666
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.ai.phoneagent.core.executor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import com.ai.phoneagent.AutomationOverlay
import com.ai.phoneagent.LaunchProxyActivity
import com.ai.phoneagent.PhoneAgentAccessibilityService
import com.ai.phoneagent.ShizukuBridge
import com.ai.phoneagent.VirtualDisplayController
import com.ai.phoneagent.core.agent.ParsedAgentAction
import com.ai.phoneagent.core.config.AgentConfiguration
import com.ai.phoneagent.core.utils.ActionUtils
import kotlinx.coroutines.delay

/**
 * 动作执行器 - 单一职责
 * 
 * 负责执行所有类型的Agent动作
 * 原逻辑来自 UiAutomationAgent.kt 的 execute 方法
 */
class ActionExecutor(
    private val config: AgentConfiguration = AgentConfiguration.DEFAULT
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    // ─── 虚拟屏模式辅助方法 ───

    /**
     * 判断当前是否应使用虚拟屏执行路径
     */
    private fun isVirtualDisplayMode(): Boolean {
        return config.useBackgroundVirtualDisplay
                && VirtualDisplayController.shouldUseVirtualDisplay
                && VirtualDisplayController.isVirtualDisplayStarted()
    }

    /**
     * 获取虚拟屏 displayId，不可用时返回 -1
     */
    private fun getVirtualDisplayId(): Int {
        return VirtualDisplayController.getDisplayId() ?: -1
    }

    /**
     * 完全隔离模式：不切换焦点到虚拟屏。
     * 所有 VD 操作通过 displayId 定向注入，焦点始终在主屏。
     */
    private var lastEnsureFocusMs = 0L
    private fun ensureVdFocus() {
        // NO-OP: 焦点隔离模式下，不切换系统焦点到虚拟屏。
        // 虚拟屏的触摸/按键/文本输入全部通过 displayId 定向注入。
        // 焦点维持在主屏（display 0），由 VirtualDisplayController 周期性强制执行。
    }



    /**
     * 执行动作
     */
    suspend fun execute(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val rawName = action.actionName ?: return false
        val name = rawName.trim().trim('"', '\'', ' ').lowercase()
        val nameKey = name.replace(" ", "")

        return when (nameKey) {
            // 启动应用
            "launch", "open_app", "start_app" -> executeLaunch(action, service, onLog)
            // 返回/导航
            "back" -> executeBack(service, onLog)
            "home" -> executeHome(service, onLog)
            // 等待
            "wait", "sleep" -> executeWait(action, onLog)
            // 输入
            "type", "input", "text", "type_name" -> executeType(action, service, uiDump, screenW, screenH, onLog)
            // 点击
            "tap", "click", "press" -> executeTap(action, service, uiDump, screenW, screenH, onLog)
            // 长按
            "longpress", "long_press", "long press" -> executeLongPress(action, service, screenW, screenH, onLog)
            // 双击
            "doubletap", "double_tap", "double tap" -> executeDoubleTap(action, service, screenW, screenH, onLog)
            // 滑动
            "swipe", "scroll" -> executeSwipe(action, service, screenW, screenH, onLog)
            // 用户接管
            "take_over", "takeover" -> executeTakeOver(action, onLog)
            // 结束任务（不执行，只返回）
            "finish" -> true
            else -> false
        }
    }

    private suspend fun executeLaunch(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        val rawTarget = action.fields["package"]
            ?: action.fields["package_name"]
            ?: action.fields["pkg"]
            ?: action.fields["app"]
            ?: action.fields["app_name"]
            ?: ""
        val t = rawTarget.trim().trim('"', '\'', ' ')
        if (t.isBlank()) return false

        val pm = service.packageManager
        val beforeTime = service.lastWindowEventTime()

        // 检查应用是否已安装
        fun isInstalled(pkgName: String): Boolean {
            return runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
                true
            }.getOrDefault(false)
        }

        // 构建启动Intent
        fun buildLaunchIntent(pkgName: String): android.content.Intent? {
            val direct = pm.getLaunchIntentForPackage(pkgName)
            if (direct != null) return direct

            val query = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val ri = runCatching { pm.queryIntentActivities(query, 0) }
                .getOrNull()
                ?.firstOrNull { it.activityInfo?.packageName == pkgName }
                ?: return null

            val ai = ri.activityInfo ?: return null
            return android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
        }

        // 初始化 AppPackageManager 缓存（确保能查询已安装应用）
        com.ai.phoneagent.core.tools.AppPackageManager.initializeCache(service)

        // 智能解析包名 - 使用新的智能匹配逻辑
        val smartResolved = com.ai.phoneagent.core.tools.AppPackageManager.resolvePackageName(t)

        // 构建候选包名列表 - 优先级：智能解析 > 精确匹配 > 已安装应用列表
        val candidates = buildList {
            // 1. 智能解析（包含高优先级关键词匹配、防误匹配逻辑）
            if (smartResolved != null) {
                add(smartResolved)
            }
            // 2. 如果输入看起来像包名（包含多个点），优先尝试
            if (t.contains('.') && t.count { it == '.' } >= 1) {
                add(t)
            }
            // 3. 已安装应用列表精确匹配
            com.ai.phoneagent.core.tools.AppPackageManager.resolvePackageByLabel(service, t)?.let { add(it) }
        }.distinct()

        // 如果候选列表为空，从已安装应用中智能搜索
        val finalCandidates = if (candidates.isEmpty()) {
            val allApps = com.ai.phoneagent.core.tools.AppPackageManager.getAllInstalledApps()
            allApps.filter { (_, appName) ->
                // 智能匹配：应用名包含查询词，或查询词包含应用名（作为完整单词）
                appName.contains(t, ignoreCase = true) ||
                t.contains(appName, ignoreCase = true) ||
                // 单词边界匹配：避免"云"匹配"阿里云盘"
                isWordBoundaryMatch(t, appName)
            }.map { it.first }.take(3) // 最多取3个候选，避免误匹配
        } else {
            candidates
        }

        var pkgName = finalCandidates.firstOrNull().orEmpty().ifBlank { t }
        var intent: android.content.Intent? = null

        for (candidate in finalCandidates) {
            if (candidate.contains('.') && !isInstalled(candidate)) continue
            val i = buildLaunchIntent(candidate)
            if (i != null) {
                pkgName = candidate
                intent = i
                break
            }
        }

        onLog("执行：Launch($pkgName)")
        if (intent == null) {
            onLog("Launch 失败：未找到可启动入口：$pkgName（candidates=${candidates.joinToString()}）")
            return false
        }

        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        return try {
            if (isVirtualDisplayMode()) {
                // ── 虚拟屏模式：将应用启动到虚拟屏（不切换系统焦点）──
                val displayId = getVirtualDisplayId()
                onLog("Launch → 虚拟屏 displayId=$displayId")
                LaunchProxyActivity.launchOnDisplay(service, intent, displayId)
                delay(config.launchActionDelayMs)  // 等待应用在虚拟屏上启动
                // 系统可能因 Activity 创建自动切焦，立即恢复
                VirtualDisplayController.restoreFocusToDefaultDisplayNow()
            } else {
                // ── 前台模式 ──
                LaunchProxyActivity.launch(service, intent)
            }
            true
        } catch (e: Exception) {
            onLog("Launch 失败：${e.message.orEmpty()}")
            false
        }
    }

    /**
     * 单词边界匹配 - 避免"云"误匹配"阿里云盘"和"移动云手机"
     */
    private fun isWordBoundaryMatch(query: String, text: String): Boolean {
        val queryWords = query.lowercase().split(Regex("[\\s_\\-]")).filter { it.length >= 2 }
        val textWords = text.lowercase().split(Regex("[\\s_\\-]"))
        
        // 查询词必须全部包含在文本中，且至少匹配一个完整单词
        return queryWords.all { word ->
            textWords.any { textWord ->
                textWord.contains(word) || word.contains(textWord)
            }
        } && textWords.any { textWord ->
            queryWords.any { word -> textWord.startsWith(word) || word.startsWith(textWord) }
        }
    }

    private suspend fun executeBack(
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("执行：Back")
        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            VirtualDisplayController.injectBackBestEffort(getVirtualDisplayId())
            delay(config.backAwaitWindowTimeoutMs)
            return true
        }
        val beforeTime = service.lastWindowEventTime()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.awaitWindowEvent(beforeTime, timeoutMs = config.backAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeHome(
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("执行：Home")
        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            VirtualDisplayController.injectHomeBestEffort(getVirtualDisplayId())
            delay(config.homeAwaitWindowTimeoutMs)
            return true
        }
        val beforeTime = service.lastWindowEventTime()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        service.awaitWindowEvent(beforeTime, timeoutMs = config.homeAwaitWindowTimeoutMs)
        return true
    }

    /**
     * 执行 Take_over - 需要用户接管
     * 实际上不执行任何动作，只返回失败，由上层处理
     */
    private fun executeTakeOver(
        action: ParsedAgentAction,
        onLog: (String) -> Unit
    ): Boolean {
        val message = action.fields["message"].orEmpty().ifBlank { "需要用户协助处理" }
        onLog("Take_over: $message")
        return false
    }

    private suspend fun executeWait(
        action: ParsedAgentAction,
        onLog: (String) -> Unit
    ): Boolean {
        val raw = action.fields["duration"].orEmpty().trim()
        val d = when {
            raw.endsWith("ms", ignoreCase = true) -> raw.dropLast(2).trim().toLongOrNull()
            raw.endsWith("s", ignoreCase = true) -> raw.dropLast(1).trim().toLongOrNull()?.times(1000)
            raw.contains("second", ignoreCase = true) -> Regex("""(\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1000)
            else -> raw.toLongOrNull()
        } ?: 600L

        onLog("执行：Wait(${d}ms)")
        delay(d.coerceAtLeast(0L))
        return true
    }

    private suspend fun executeType(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        // 敏感内容检查
        if (ActionUtils.looksSensitive(uiDump, config.sensitiveKeywords)) {
            onLog("检测到支付/验证界面关键词，停止并要求用户接管")
            return false
        }

        val inputText = action.fields["text"].orEmpty()
        val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
        val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
        val className = action.fields["className"] ?: action.fields["class_name"]
        val elementText = action.fields["elementText"]
            ?: action.fields["element_text"]
            ?: action.fields["targetText"]
            ?: action.fields["target_text"]
        val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0

        // 如果有坐标，先点击
        val element = ActionUtils.parsePoint(action.fields["element"])
            ?: ActionUtils.parsePoint(action.fields["point"])
        if (element != null) {
            val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)
            onLog("执行：先点击输入框(${element.first},${element.second})")
            if (isVirtualDisplayMode()) {
                ensureVdFocus()
                VirtualDisplayController.injectTapBestEffort(getVirtualDisplayId(), x.toInt(), y.toInt())
            } else {
                service.clickAwait(x, y)
            }
            delay(300)
        }

        onLog("执行：Type(${inputText.take(config.logInputTextTruncateLength)})")

        if (isVirtualDisplayMode()) {
            // 虚拟屏模式：根据文本内容选择最佳输入方式
            ensureVdFocus()
            val displayId = getVirtualDisplayId()
            val ok = injectTextOnVirtualDisplay(displayId, inputText, onLog)
            if (!ok) {
                onLog("虚拟屏文本输入失败")
            }
            delay(config.typeAwaitWindowTimeoutMs)
            return ok
        }

        var ok = if (resourceId != null || contentDesc != null || className != null || elementText != null) {
            service.setTextOnElement(
                text = inputText,
                resourceId = resourceId,
                elementText = elementText,
                contentDesc = contentDesc,
                className = className,
                index = index
            )
        } else {
            service.setTextOnFocused(inputText)
        }

        if (!ok) {
            onLog("输入失败，尝试查找并激活输入框…")
            val inputClicked = service.clickFirstEditableElement()
            if (inputClicked) {
                delay(300)
                ok = service.setTextOnFocused(inputText)
            }
        }

        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.typeAwaitWindowTimeoutMs)
        return ok
    }

    private suspend fun executeTap(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        uiDump: String,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        // 敏感内容检查
        if (ActionUtils.looksSensitive(uiDump, config.sensitiveKeywords)) {
            onLog("检测到支付/验证界面关键词，停止并要求用户接管")
            return false
        }

        val resourceId = action.fields["resourceId"] ?: action.fields["resource_id"]
        val contentDesc = action.fields["contentDesc"] ?: action.fields["content_desc"]
        val className = action.fields["className"] ?: action.fields["class_name"]
        val elementText = action.fields["elementText"]
            ?: action.fields["element_text"]
            ?: action.fields["label"]
        val index = action.fields["index"]?.trim()?.toIntOrNull() ?: 0

        // 优先使用 selector（仅前台模式，虚拟屏模式下 AccessibilityService 操作的是前台屏幕）
        val selectorOk = if (!isVirtualDisplayMode() && (resourceId != null || contentDesc != null || className != null || elementText != null)) {
            onLog("执行：Tap(selector)")
            // 临时隐藏悬浮窗，防止点击到悬浮窗
            AutomationOverlay.temporaryHide()
            val result = service.clickElement(
                resourceId = resourceId,
                text = elementText,
                contentDesc = contentDesc,
                className = className,
                index = index
            )
            AutomationOverlay.restoreVisibility()
            result
        } else {
            false
        }

        if (selectorOk) {
            service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
            return true
        }

        // 回退到坐标点击
        val element = ActionUtils.parsePoint(action.fields["element"])
            ?: ActionUtils.parsePoint(action.fields["point"])
            ?: ActionUtils.parsePoint(action.fields["pos"])
        val xRel = action.fields["x"]?.trim()?.toIntOrNull() ?: element?.first ?: return false
        val yRel = action.fields["y"]?.trim()?.toIntOrNull() ?: element?.second ?: return false

        val (x, y) = ActionUtils.parsePointToScreen(xRel to yRel, screenW, screenH)
        onLog("执行：Tap($xRel,$yRel)")

        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            VirtualDisplayController.injectTapBestEffort(getVirtualDisplayId(), x.toInt(), y.toInt())
            delay(config.tapAwaitWindowTimeoutMs)
            return true
        }

        // 临时隐藏悬浮窗，防止点击到悬浮窗
        AutomationOverlay.temporaryHide()
        service.clickAwait(x, y)
        AutomationOverlay.restoreVisibility()
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeLongPress(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val element = ActionUtils.parsePoint(action.fields["element"]) ?: return false
        val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)

        onLog("执行：Long Press(${element.first},${element.second})")

        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            val displayId = getVirtualDisplayId()
            // 长按 = DOWN → delay → UP
            val downTime = android.os.SystemClock.uptimeMillis()
            VirtualDisplayController.injectTapBestEffort(displayId, x.toInt(), y.toInt()) // 先发 down
            delay(config.longPressDurationMs)
            // 注入 UP 事件完成长按（best-effort：部分 ROM 上 injectTap 已自动发 DOWN+UP，此处补发一次无副作用）
            VirtualDisplayController.injectTapBestEffort(displayId, x.toInt(), y.toInt())
            delay(config.tapAwaitWindowTimeoutMs)
            return true
        }

        // 临时隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        service.clickAwait(x, y, durationMs = config.longPressDurationMs)
        AutomationOverlay.restoreVisibility()
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return true
    }

    private suspend fun executeDoubleTap(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val element = ActionUtils.parsePoint(action.fields["element"]) ?: return false
        val (x, y) = ActionUtils.parsePointToScreen(element, screenW, screenH)

        onLog("执行：Double Tap(${element.first},${element.second})")

        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            val displayId = getVirtualDisplayId()
            VirtualDisplayController.injectTapBestEffort(displayId, x.toInt(), y.toInt())
            delay(config.doubleTapIntervalMs)
            VirtualDisplayController.injectTapBestEffort(displayId, x.toInt(), y.toInt())
            delay(config.tapAwaitWindowTimeoutMs)
            return true
        }

        // 临时隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        val ok1 = service.clickAwait(x, y, durationMs = config.clickDurationMs)
        delay(config.doubleTapIntervalMs)
        val ok2 = service.clickAwait(x, y, durationMs = config.clickDurationMs)
        AutomationOverlay.restoreVisibility()
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.tapAwaitWindowTimeoutMs)
        return ok1 && ok2
    }

    private suspend fun executeSwipe(
        action: ParsedAgentAction,
        service: PhoneAgentAccessibilityService,
        screenW: Int,
        screenH: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val start = ActionUtils.parsePoint(action.fields["start"])
        val end = ActionUtils.parsePoint(action.fields["end"])

        val sxRel = action.fields["start_x"]?.trim()?.toIntOrNull() ?: start?.first ?: return false
        val syRel = action.fields["start_y"]?.trim()?.toIntOrNull() ?: start?.second ?: return false
        val exRel = action.fields["end_x"]?.trim()?.toIntOrNull() ?: end?.first ?: return false
        val eyRel = action.fields["end_y"]?.trim()?.toIntOrNull() ?: end?.second ?: return false

        val durRaw = action.fields["duration"].orEmpty().trim()
        val dur = when {
            durRaw.endsWith("ms", ignoreCase = true) -> durRaw.dropLast(2).trim().toLongOrNull()
            durRaw.endsWith("s", ignoreCase = true) -> durRaw.dropLast(1).trim().toLongOrNull()?.times(1000)
            else -> durRaw.toLongOrNull()
        } ?: config.scrollDurationMs

        val (sx, sy) = ActionUtils.parsePointToScreen(sxRel to syRel, screenW, screenH)
        val (ex, ey) = ActionUtils.parsePointToScreen(exRel to eyRel, screenW, screenH)

        onLog("执行：Swipe($sxRel,$syRel -> $exRel,$eyRel, ${dur}ms)")

        if (isVirtualDisplayMode()) {
            ensureVdFocus()
            VirtualDisplayController.injectSwipeBestEffort(
                getVirtualDisplayId(), sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt(), dur
            )
            delay(config.swipeAwaitWindowTimeoutMs)
            return true
        }

        // 临时隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        service.swipeAwait(sx, sy, ex, ey, dur)
        AutomationOverlay.restoreVisibility()
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.swipeAwaitWindowTimeoutMs)
        return true
    }

    // ─── 虚拟屏文本输入 ───

    /**
     * 在虚拟屏上输入文本（支持中文等非 ASCII 字符）
     *
     * 策略：
     * 1. 纯 ASCII 文本 → 直接使用 `input -d <displayId> text`
     * 2. 含非 ASCII 字符 → 先写入剪贴板，再注入 Ctrl+V 粘贴到虚拟屏
     * 3. 剪贴板方式失败 → 回退到逐字 `input text` 尝试
     */
    internal fun injectTextOnVirtualDisplay(displayId: Int, text: String, onLog: (String) -> Unit): Boolean {
        if (displayId <= 0 || text.isEmpty()) return false

        val isAsciiOnly = text.all { it.code in 0..127 }

        if (isAsciiOnly) {
            // ASCII 文本直接用 input text 命令
            val escaped = text.replace(" ", "%s").replace("'", "\\'").replace("\"", "\\\"")
            val cmd = "input -d $displayId text '$escaped'"
            val result = ShizukuBridge.execResult(cmd)
            if (result.exitCode == 0) return true
            // 带 -d 失败，尝试不带 -d（某些 ROM 不支持）
            val cmd2 = "input text '$escaped'"
            val r2 = ShizukuBridge.execResult(cmd2)
            if (r2.exitCode == 0) return true
            onLog("ASCII input 命令失败，尝试剪贴板方式...")
        }

        // 非 ASCII 或 ASCII 失败 → 使用剪贴板 + 粘贴方式
        if (setClipboardAndPaste(displayId, text, onLog)) return true

        // 回退方案：使用 am broadcast 方式（需要 ADBKeyboard 或类似 IME）
        val broadcastResult = ShizukuBridge.execResult(
            "am broadcast -a ADB_INPUT_TEXT --es msg '$text'"
        )
        if (broadcastResult.exitCode == 0) {
            val output = broadcastResult.stdoutText()
            if (output.contains("result=0") || output.contains("result=-1")) {
                return true
            }
        }

        // 最后回退：不带 -d 的 input text（对某些设备可能有效）
        if (!isAsciiOnly) {
            val escaped = text.replace(" ", "%s").replace("'", "\\'").replace("\"", "\\\"")
            val cmd = "input text '$escaped'"
            val result = ShizukuBridge.execResult(cmd)
            if (result.exitCode == 0) return true
        }

        onLog("所有虚拟屏文本输入方式均失败")
        return false
    }

    /**
     * 通过剪贴板 + Ctrl+V 粘贴方式输入文本
     */
    private fun setClipboardAndPaste(displayId: Int, text: String, onLog: (String) -> Unit): Boolean {
        // 方式 1: 使用 cmd clipboard（Android 12+ 可用）
        val escapedText = text.replace("'", "'\\''")
        val clipCmds = listOf(
            "cmd clipboard set-text '$escapedText'",
            "service call clipboard 2 i32 1 i64 0 s16 'com.android.shell' s16 '$escapedText' i32 0 i32 0",
        )

        var clipboardSet = false
        for (cmd in clipCmds) {
            val r = ShizukuBridge.execResult(cmd)
            if (r.exitCode == 0) {
                clipboardSet = true
                break
            }
        }

        if (!clipboardSet) {
            onLog("剪贴板设置失败，跳过粘贴方式")
            return false
        }

        // 等待剪贴板同步
        try { Thread.sleep(100) } catch (_: InterruptedException) {}

        // 在虚拟屏上注入 Ctrl+V（粘贴）
        VirtualDisplayController.injectPasteBestEffort(displayId)

        // 等待粘贴完成
        try { Thread.sleep(200) } catch (_: InterruptedException) {}

        return true
    }
}
