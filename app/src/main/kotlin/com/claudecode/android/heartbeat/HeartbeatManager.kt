package com.claudecode.android.heartbeat

import com.claudecode.android.agent.AgentSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 心跳管理器 — 监控 Agent 任务健康状态
 *
 * ## 心跳的作用
 *
 * 在长时间运行的 Agent 任务中（可能持续数分钟到数十分钟），
 * 心跳机制负责：
 * 1. 定期检查 Session 是否仍然活跃
 * 2. 监控 Token 使用量，在接近上限前发出警告
 * 3. 检测长时间无响应（可能卡死）
 * 4. 保持前台服务活跃（防止被 Android 系统杀死）
 * 5. 向 UI 发送心跳事件（保持 SSE 连接活跃）
 *
 * ## 与 Claude Code 原版的对应
 * Claude Code 在 Node.js 中用 setInterval 实现类似机制，
 * 这里改用 Kotlin 协程的 delay + while 循环实现。
 */

/**
 * 心跳事件密封类
 * 用于描述心跳检测中发现的各种状态
 */
sealed class HeartbeatEvent {
    /** 普通心跳，一切正常 */
    data class Ping(val sessionId: String, val timestamp: Long = System.currentTimeMillis()) : HeartbeatEvent()

    /**
     * Token 使用量警告
     * 当已使用 Token 超过上限的 85% 时触发
     *
     * @param usedTokens 已使用 Token 数量
     * @param maxTokens Token 上限
     * @param percentage 使用百分比 (0.0 - 1.0)
     */
    data class TokenWarning(
        val sessionId: String,
        val usedTokens: Int,
        val maxTokens: Int,
        val percentage: Double
    ) : HeartbeatEvent()

    /**
     * 长时间运行警告
     * 当 Session 运行超过 1 小时时触发，提醒用户注意
     *
     * @param runningMinutes 已运行分钟数
     */
    data class LongRunningWarning(
        val sessionId: String,
        val runningMinutes: Long
    ) : HeartbeatEvent()

    /**
     * Session 超时，强制终止
     * 当 Session 运行超过 2 小时时触发
     */
    data class SessionTimeout(val sessionId: String) : HeartbeatEvent()

    /**
     * Session 仍然存活（正常响应）
     */
    data class Alive(val sessionId: String) : HeartbeatEvent()
}

/**
 * 心跳管理器
 *
 * 为每个活跃的 AgentSession 维护一个独立的心跳协程。
 * 每30秒检查一次 Session 状态，并通过 StateFlow 发布心跳事件。
 *
 * 使用方式：
 * ```kotlin
 * val job = heartbeatManager.startHeartbeat(session)
 * // ... 在 UI 中订阅事件
 * heartbeatManager.events.collect { event ->
 *     when (event) {
 *         is HeartbeatEvent.TokenWarning -> showWarningBanner()
 *         is HeartbeatEvent.SessionTimeout -> stopSession()
 *         else -> {}
 *     }
 * }
 * // Session 结束时停止心跳
 * heartbeatManager.stopHeartbeat(session.id)
 * ```
 */
class HeartbeatManager {

    companion object {
        /** 心跳间隔：30 秒 */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** Token 警告阈值：使用量超过 85% 时触发 */
        private const val TOKEN_WARNING_THRESHOLD = 0.85

        /** 长时间运行警告阈值：1 小时（毫秒） */
        private const val LONG_RUNNING_THRESHOLD_MS = 60L * 60L * 1000L

        /** 强制超时阈值：2 小时（毫秒） */
        private const val TIMEOUT_THRESHOLD_MS = 2L * 60L * 60L * 1000L
    }

    // ViewModel/UI 层订阅此 Flow 以接收心跳事件
    private val _events = MutableStateFlow<HeartbeatEvent>(
        HeartbeatEvent.Ping("init")
    )
    val events: StateFlow<HeartbeatEvent> = _events.asStateFlow()

    // 每个 Session 对应一个心跳 Job（使用 sessionId 作为 key）
    private val heartbeatJobs = mutableMapOf<String, Job>()

    // 心跳协程 Scope（SupervisorJob 保证一个 Session 失败不影响其他）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 为指定 Session 启动心跳检测
     *
     * 每 30 秒执行一次检查：
     * 1. 检查 Token 使用量
     * 2. 检查运行时长
     * 3. 发布对应的心跳事件
     *
     * @param session 要监控的 AgentSession
     * @return 心跳协程的 Job，可用于手动取消
     */
    fun startHeartbeat(session: AgentSession): Job {
        // 如果已有心跳，先停止旧的
        stopHeartbeat(session.id)

        val startTime = System.currentTimeMillis()

        val job = scope.launch {
            while (isActive) {
                // 等待心跳间隔
                delay(HEARTBEAT_INTERVAL_MS)

                val now = System.currentTimeMillis()
                val runningMs = now - startTime
                val runningMinutes = runningMs / 60_000L

                // 检查是否超过强制超时阈值（2 小时）
                if (runningMs >= TIMEOUT_THRESHOLD_MS) {
                    _events.value = HeartbeatEvent.SessionTimeout(session.id)
                    // 超时后停止心跳（调用方应监听此事件并终止 Session）
                    break
                }

                // 检查是否超过长时间运行警告阈值（1 小时）
                if (runningMs >= LONG_RUNNING_THRESHOLD_MS) {
                    _events.value = HeartbeatEvent.LongRunningWarning(
                        sessionId       = session.id,
                        runningMinutes  = runningMinutes
                    )
                    // 警告后继续运行，不停止
                    continue
                }

                // 检查 Token 使用量
                val tokenUsage = session.getTokenUsage()
                if (tokenUsage != null && tokenUsage.maxTokens > 0) {
                    val percentage = tokenUsage.usedTokens.toDouble() / tokenUsage.maxTokens
                    if (percentage >= TOKEN_WARNING_THRESHOLD) {
                        _events.value = HeartbeatEvent.TokenWarning(
                            sessionId   = session.id,
                            usedTokens  = tokenUsage.usedTokens,
                            maxTokens   = tokenUsage.maxTokens,
                            percentage  = percentage
                        )
                        continue
                    }
                }

                // 一切正常，发送普通 Ping 或 Alive 事件
                // 每 5 次心跳发一次 Alive，其余发 Ping（减少 UI 刷新）
                val tickCount = runningMs / HEARTBEAT_INTERVAL_MS
                if (tickCount % 5L == 0L) {
                    _events.value = HeartbeatEvent.Alive(session.id)
                } else {
                    _events.value = HeartbeatEvent.Ping(session.id)
                }
            }
        }

        // 保存 Job，方便后续停止
        heartbeatJobs[session.id] = job
        return job
    }

    /**
     * 停止指定 Session 的心跳检测
     *
     * @param sessionId 要停止心跳的 Session ID
     */
    fun stopHeartbeat(sessionId: String) {
        heartbeatJobs[sessionId]?.cancel()
        heartbeatJobs.remove(sessionId)
    }

    /**
     * 停止所有心跳（App 退出时调用）
     */
    fun stopAll() {
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
    }

    /**
     * 获取当前活跃的心跳 Session 数量
     */
    fun activeHeartbeatCount(): Int = heartbeatJobs.size
}
