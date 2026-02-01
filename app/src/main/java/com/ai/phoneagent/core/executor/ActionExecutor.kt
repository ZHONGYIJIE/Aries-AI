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
import android.graphics.Rect
import com.ai.phoneagent.AutomationOverlay
import com.ai.phoneagent.LaunchProxyActivity
import com.ai.phoneagent.PhoneAgentAccessibilityService
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
    // ActionUtils 是 object 单例，直接使用

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

        // 构建候选包名列表 - 优先级：精确匹配 > 预定义映射 > 已安装应用列表 > 模糊匹配
        val candidates = buildList {
            // 1. 如果输入看起来像包名（包含多个点），优先尝试
            if (t.contains('.') && t.count { it == '.' } >= 1) {
                add(t)
            }
            // 2. 预定义映射（AppPackageMapping - 常用应用）
            com.ai.phoneagent.AppPackageMapping.resolve(t)?.let { add(it) }
            // 3. 已安装应用列表精确匹配
            com.ai.phoneagent.core.tools.AppPackageManager.resolvePackageByLabel(service, t)?.let { add(it) }
            // 4. 如果输入不包含点，直接作为应用名尝试
            if (!t.contains('.')) {
                add(t)
            }
        }.distinct()

        // 如果候选列表为空，尝试从已安装应用列表中模糊搜索
        val finalCandidates = if (candidates.isEmpty()) {
            // 兜底：从已安装应用中模糊搜索应用名包含关键字的
            val allApps = com.ai.phoneagent.core.tools.AppPackageManager.getAllInstalledApps()
            allApps.filter { (_, appName) ->
                appName.contains(t, ignoreCase = true) ||
                t.contains(appName, ignoreCase = true)
            }.map { it.first }.take(5) // 最多取5个候选
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
            LaunchProxyActivity.launch(service, intent)
            service.awaitWindowEvent(beforeTime, timeoutMs = config.launchAwaitWindowTimeoutMs)
            true
        } catch (e: Exception) {
            onLog("Launch 失败：${e.message.orEmpty()}")
            false
        }
    }

    private suspend fun executeBack(
        service: PhoneAgentAccessibilityService,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("执行：Back")
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
            service.clickAwait(x, y)
            delay(300)
        }

        onLog("执行：Type(${inputText.take(config.logInputTextTruncateLength)})")

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

        // 优先使用 selector
        val selectorOk = if (resourceId != null || contentDesc != null || className != null || elementText != null) {
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
        // 临时隐藏悬浮窗
        AutomationOverlay.temporaryHide()
        service.swipeAwait(sx, sy, ex, ey, dur)
        AutomationOverlay.restoreVisibility()
        service.awaitWindowEvent(service.lastWindowEventTime(), timeoutMs = config.swipeAwaitWindowTimeoutMs)
        return true
    }
}

