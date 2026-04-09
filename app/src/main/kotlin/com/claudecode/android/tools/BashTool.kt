package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell 命令执行工具
 *
 * 使用 ProcessBuilder 执行 Shell 命令，支持超时控制，合并 stdout 和 stderr 输出。
 * 维护一个持久化的 Shell 进程（PersistentShell），在同一会话中的多次调用之间共享
 * 工作目录状态。
 *
 * Android 平台限制说明：
 * ─────────────────────────────────────────────────────────
 * 1. 无 root 权限时，可用的 Shell 路径为 /system/bin/sh
 * 2. 可用命令有限：ls、cat、echo、cd、mkdir、rm、cp、mv、find、grep 等基本命令通常可用
 * 3. 某些系统命令（如 ps、netstat、ifconfig）在非 root 模式下受限
 * 4. 无法访问 /data/data 以外的其他应用目录
 *
 * 如何通过 Termux 提升能力：
 * ─────────────────────────────────────────────────────────
 * 如果设备安装了 Termux，可以通过以下方式执行更丰富的命令：
 * 1. 发送 Intent 到 Termux：
 *    ```kotlin
 *    val intent = Intent("com.termux.RUN_COMMAND").apply {
 *        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
 *        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
 *        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
 *    }
 *    context.startActivity(intent)
 *    ```
 * 2. Termux 提供了完整的 Linux 环境，包括 git、python、node 等工具
 * ─────────────────────────────────────────────────────────
 *
 * @param workingDir 命令执行的默认工作目录
 */
class BashTool(private val workingDir: String) : Tool {

    override val name: String = "bash"

    override val description: String = """
        执行 Shell 命令（使用 /system/bin/sh）。
        合并 stdout 和 stderr 输出一起返回。
        支持超时控制（默认 30 秒），超时后强制终止进程。

        注意（Android 限制）：
        - 使用 /system/bin/sh，可用命令有限
        - 无 root 权限时无法访问受保护目录
        - 建议通过 Termux 获得更完整的 Linux 环境

        可用的典型命令：ls、cat、echo、find、grep、mkdir、rm、cp、mv、chmod、date、id
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 bash 工具接受的参数：
     * - command（必需）：要执行的 Shell 命令
     * - timeout（可选）：超时时间（毫秒），默认 30000ms（30秒）
     * - restart（可选）：是否重启 Shell 进程（清除之前的状态）
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "要执行的 Shell 命令。例如：'ls -la /sdcard'、'find . -name \"*.kt\"'、'cat /proc/version'"
                },
                "timeout": {
                    "type": "integer",
                    "description": "命令执行超时时间（毫秒）。默认为 30000（30秒）。超时后进程会被强制终止。"
                },
                "restart": {
                    "type": "boolean",
                    "description": "是否重启 Shell 进程。设为 true 可清除之前的环境变量和工作目录变更等状态。默认为 false。"
                }
            },
            "required": ["command"]
        }
    """.trimIndent()).asJsonObject

    /** 持久化 Shell 进程管理器（单例，在本工具实例内复用） */
    private val persistentShell = PersistentShell(workingDir)

    /**
     * 执行 Shell 命令
     *
     * @param input JSON 对象，包含以下字段：
     *   - command: String（必需）要执行的命令
     *   - timeout: Long?（可选）超时毫秒数，默认 30000
     *   - restart: Boolean?（可选）是否重启 Shell
     *
     * @return ToolResult
     *   - 成功：命令的标准输出 + 标准错误（合并），附带退出码
     *   - 失败：超时错误、执行错误等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = input.get("command")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'command'")

        val timeoutMs = input.get("timeout")?.asLong ?: 30_000L
        val restart = input.get("restart")?.asBoolean ?: false

        // 如果请求重启，则销毁现有进程
        if (restart) {
            persistentShell.reset()
        }

        return@withContext try {
            persistentShell.execute(command, timeoutMs)
        } catch (e: Exception) {
            ToolResult.error("执行命令时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 持久化 Shell 进程管理器
     *
     * 为每次命令执行创建独立的子进程。虽然名称是"持久化"，但每次调用
     * 实际上会创建新进程，通过设置工作目录来模拟持久化状态。
     * 真正的持久化 Shell（跨调用保持 cd 等状态）实现复杂，这里以简化方式处理。
     *
     * 若需要真正持久化的 Shell 状态（如 cd 命令生效），可以考虑：
     * 1. 维护一个长期运行的 Shell 进程，通过 stdin/stdout 管道通信
     * 2. 使用文件记录当前工作目录，每次命令执行时 cd 到该目录
     *
     * @param initialWorkDir 初始工作目录
     */
    inner class PersistentShell(private val initialWorkDir: String) {

        /** 当前工作目录（通过解析 cd 命令尝试跟踪，但不保证完全准确） */
        private var currentWorkDir: String = initialWorkDir

        /**
         * 重置 Shell 状态（恢复到初始工作目录）
         */
        fun reset() {
            currentWorkDir = initialWorkDir
        }

        /**
         * 执行一条 Shell 命令
         *
         * 每次执行都会创建新的子进程，在 currentWorkDir 中运行命令。
         *
         * @param command   要执行的命令字符串
         * @param timeoutMs 超时时间（毫秒）
         * @return ToolResult 包含命令输出和退出码信息
         */
        fun execute(command: String, timeoutMs: Long): ToolResult {
            // 构建进程
            val processBuilder = ProcessBuilder().apply {
                // 在 Android 上使用 /system/bin/sh
                command(listOf("/system/bin/sh", "-c", command))
                directory(java.io.File(currentWorkDir))
                // 合并 stderr 到 stdout，统一输出流
                redirectErrorStream(true)
                // 设置环境变量
                environment().apply {
                    put("HOME", initialWorkDir)
                    put("TMPDIR", initialWorkDir)
                    put("PATH", "/system/bin:/system/xbin")
                }
            }

            val process = try {
                processBuilder.start()
            } catch (e: Exception) {
                return ToolResult.error("无法启动 Shell 进程: ${e.message}\n可能原因：/system/bin/sh 不可访问")
            }

            // 异步读取输出（防止缓冲区满导致进程阻塞）
            val outputBuilder = StringBuilder()
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))

            // 在后台线程中读取输出
            val readThread = Thread {
                try {
                    outputReader.lineSequence().forEach { line ->
                        outputBuilder.appendLine(line)
                    }
                } catch (e: Exception) {
                    // 进程结束时流会关闭，忽略此异常
                }
            }.apply { start() }

            // 等待进程完成（带超时）
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                // 超时，强制终止进程
                process.destroyForcibly()
                readThread.join(1000)  // 等待读取线程结束
                return ToolResult.error(
                    "命令执行超时（超过 ${timeoutMs}ms）: $command\n" +
                    "已强制终止进程。如需执行耗时操作，请增大 timeout 参数值。"
                )
            }

            // 等待读取线程完成（最多1秒）
            readThread.join(1000)

            val exitCode = process.exitValue()
            val output = outputBuilder.toString()

            // 尝试解析 cd 命令以跟踪工作目录变化
            trackWorkingDirectory(command)

            // 构建结果：包含输出内容和退出码
            val resultText = buildString {
                if (output.isNotEmpty()) {
                    append(output)
                    if (!output.endsWith("\n")) appendLine()
                }
                append("[退出码: $exitCode]")
                if (exitCode != 0) {
                    append("（命令以非零状态退出，表示可能出现错误）")
                }
            }

            // 退出码非零时也返回为 isError=true，帮助 Claude 识别命令失败
            return if (exitCode == 0) {
                ToolResult.success(resultText)
            } else {
                ToolResult(output = resultText, isError = true)
            }
        }

        /**
         * 尝试从命令中解析工作目录变化
         *
         * 当命令是 `cd /some/path` 形式时，更新 currentWorkDir。
         * 这只是简单的字符串解析，不能处理所有复杂情况（如 cd $VAR、cd ~等）。
         *
         * @param command 刚执行的命令
         */
        private fun trackWorkingDirectory(command: String) {
            val trimmed = command.trim()
            if (trimmed.startsWith("cd ")) {
                val newDir = trimmed.removePrefix("cd ").trim()
                if (newDir.startsWith("/")) {
                    val dir = java.io.File(newDir)
                    if (dir.exists() && dir.isDirectory) {
                        currentWorkDir = dir.canonicalPath
                    }
                }
            }
        }
    }
}
