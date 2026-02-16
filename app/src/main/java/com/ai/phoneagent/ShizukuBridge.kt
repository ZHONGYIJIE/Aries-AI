/*
 * Aries AI - Android UI Automation Framework
 * Copyright (C) 2025-2026 ZG0704666
 *
 * Licensed under AGPL-3.0. See LICENSE for details.
 */
package com.ai.phoneagent

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * Shizuku 通道桥接（Kotlin 侧）。
 *
 * **用途**
 * - 在用户授予 Shizuku 权限后，通过 Shizuku 的 binder 以高权限执行 `sh -c <command>`，
 *   用于：
 *   - 虚拟屏/系统服务相关能力（例如配合 `vdiso` 包的系统服务反射调用）。
 *   - Python 侧在 `CONNECT_MODE=SHIZUKU` 时执行 `screencap/input/am` 等命令（由 Python 调用该类的静态方法）。
 *
 * **返回码约定（见 [execResult]）**
 * - `0`：命令成功
 * - `-1`：Shizuku binder 不可用
 * - `-2`：未授予 Shizuku 权限
 * - `-3`：执行过程中抛异常
 *
 * **典型用法**
 * - `ShizukuBridge.execResult("dumpsys window windows")`
 */
object ShizukuBridge {

    private const val TAG = "AriesShizuku"

    data class ExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) {
        fun stdoutText(): String {
            return try {
                stdout.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }

        fun stderrText(): String {
            return try {
                stderr.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun newProcess(command: Array<String>): Any {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null)
    }

    @JvmStatic
    fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun execBytes(command: String): ByteArray {
        val r = execResult(command)
        return if (r.exitCode == 0) r.stdout else ByteArray(0)
    }

    @JvmStatic
    fun execText(command: String): String {
        val bytes = execBytes(command)
        if (bytes.isEmpty()) return ""
        return try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun execResult(command: String): ExecResult {
        return try {
            if (!pingBinder()) return ExecResult(exitCode = -1, stdout = ByteArray(0), stderr = ByteArray(0))
            if (!hasPermission()) return ExecResult(exitCode = -2, stdout = ByteArray(0), stderr = ByteArray(0))

            val process = newProcess(arrayOf("sh", "-c", command))
            val cls = process.javaClass
            val inputStream = cls.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val errorStream = cls.getMethod("getErrorStream").invoke(process) as java.io.InputStream

            val outBytes = readAllBytes(inputStream)
            val errBytes = readAllBytes(errorStream)
            val exitCode = cls.getMethod("waitFor").invoke(process) as Int

            if (exitCode != 0) {
                val stderrText = try {
                    errBytes.toString(Charsets.UTF_8).trim()
                } catch (_: Exception) {
                    ""
                }
                val stdoutText = try {
                    outBytes.toString(Charsets.UTF_8).trim()
                } catch (_: Exception) {
                    ""
                }
                Log.w(TAG, "Shizuku exec failed: exitCode=$exitCode cmd=$command stderr=${stderrText.take(500)} stdout=${stdoutText.take(500)}")
            }

            ExecResult(exitCode = exitCode, stdout = outBytes, stderr = errBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku exec exception: ${t.message}")
            ExecResult(exitCode = -3, stdout = ByteArray(0), stderr = ByteArray(0))
        }
    }

    private fun readAllBytes(input: java.io.InputStream): ByteArray {
        return try {
            input.use { ins ->
                ByteArrayOutputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /**
     * 检查 Shizuku 是否可用（binder + 权限）
     */
    @JvmStatic
    fun isShizukuAvailable(): Boolean {
        return pingBinder() && hasPermission()
    }
}
