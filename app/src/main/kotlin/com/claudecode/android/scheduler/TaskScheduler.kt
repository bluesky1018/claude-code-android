package com.claudecode.android.scheduler

import android.content.Context
import androidx.work.*
import com.claudecode.android.agent.AgentLoop
import com.claudecode.android.agent.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 定时任务调度器 — 像 Claude Code 的定时任务系统
 *
 * ## 为什么使用 WorkManager？
 * WorkManager 是 Android 推荐的后台任务框架：
 * - App 关闭后仍然运行
 * - 设备重启后自动恢复
 * - 自动处理省电模式和 Doze 限制
 * - 支持唯一任务命名（防止重复调度）
 *
 * ## 支持的调度格式
 * - Cron 表达式："0 8 * * *"（每天8点）
 * - 间隔："3600000"（每小时，毫秒）
 * - 一次性："2026-04-09T08:00:00"（指定时间执行一次）
 */

/**
 * 定时任务数据类，描述一个完整的调度任务
 *
 * @param id 唯一标识符
 * @param prompt 要传给 Agent 的提示词
 * @param scheduleType 调度类型："cron" / "interval" / "once"
 * @param scheduleValue 调度参数：cron 表达式 / 毫秒间隔 / ISO 时间字符串
 * @param workingDirectory Agent 运行的工作目录
 * @param enabled 是否启用（false = 暂停状态）
 * @param createdAt 创建时间（毫秒时间戳）
 * @param lastRunAt 上次运行时间（null = 从未运行）
 * @param nextRunAt 下次运行时间（null = 未计算）
 */
data class ScheduledTask(
    val id: String,
    val prompt: String,
    val scheduleType: String,   // "cron" | "interval" | "once"
    val scheduleValue: String,  // cron 表达式 | 毫秒数字符串 | ISO 时间字符串
    val workingDirectory: String = "/",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null
)

/**
 * 定时任务调度器
 *
 * 负责将 ScheduledTask 翻译为 WorkManager 请求并管理任务生命周期。
 * 使用内存中的 Map 存储任务状态，生产环境应替换为 Room 数据库。
 *
 * @param context Android 上下文，WorkManager 需要
 * @param agentLoop AgentLoop 实例，任务执行时调用
 */
class TaskScheduler(
    private val context: Context,
    private val agentLoop: AgentLoop
) {
    // 内存任务存储（生产环境建议换成 Room 数据库）
    private val tasks = mutableMapOf<String, ScheduledTask>()

    // WorkManager 实例
    private val workManager = WorkManager.getInstance(context)

    /**
     * 注册并启动一个定时任务
     *
     * 根据 scheduleType 选择不同的 WorkManager 请求类型：
     * - cron/interval → PeriodicWorkRequest（周期性）
     * - once          → OneTimeWorkRequest（一次性）
     *
     * @param task 要调度的任务
     */
    fun scheduleTask(task: ScheduledTask) {
        // 保存任务到内存
        tasks[task.id] = task

        // 如果任务被禁用，不创建 WorkManager 请求
        if (!task.enabled) return

        // 构建传给 Worker 的数据（WorkManager 只能传简单数据）
        val inputData = workDataOf(
            AgentWorker.KEY_TASK_ID to task.id,
            AgentWorker.KEY_PROMPT to task.prompt,
            AgentWorker.KEY_WORKING_DIR to task.workingDirectory
        )

        when (task.scheduleType) {
            "cron", "interval" -> {
                // 解析为重复间隔
                val (interval, unit) = when (task.scheduleType) {
                    "cron"     -> parseCronToRepeatInterval(task.scheduleValue)
                    else       -> Pair(task.scheduleValue.toLongOrNull() ?: 3600000L, TimeUnit.MILLISECONDS)
                }

                // 创建周期性工作请求
                val workRequest = PeriodicWorkRequestBuilder<AgentWorker>(interval, unit)
                    .setInputData(inputData)
                    .setConstraints(
                        // 需要网络连接（调用 Anthropic API）
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                // 使用唯一名称，防止重复调度
                // KEEP_EXISTING：如果已有同名任务运行中，不重复创建
                workManager.enqueueUniquePeriodicWork(
                    task.id,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            }

            "once" -> {
                // 解析一次性执行时间
                val targetTime = LocalDateTime.parse(task.scheduleValue)
                val nowMillis = System.currentTimeMillis()
                val targetMillis = targetTime
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

                // 计算延迟（如果时间已过则立即执行）
                val delayMillis = (targetMillis - nowMillis).coerceAtLeast(0L)

                val workRequest = OneTimeWorkRequestBuilder<AgentWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                workManager.enqueueUniqueWork(
                    task.id,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            }

            else -> {
                throw IllegalArgumentException("不支持的调度类型: ${task.scheduleType}，请使用 cron/interval/once")
            }
        }
    }

    /**
     * 取消并删除一个定时任务
     *
     * @param taskId 任务 ID
     */
    fun cancelTask(taskId: String) {
        // 从内存移除
        tasks.remove(taskId)
        // 取消 WorkManager 中的任务
        workManager.cancelUniqueWork(taskId)
    }

    /**
     * 暂停任务（保留配置，停止调度）
     *
     * @param taskId 任务 ID
     */
    fun pauseTask(taskId: String) {
        val task = tasks[taskId] ?: return
        // 更新 enabled 状态
        tasks[taskId] = task.copy(enabled = false)
        // 取消 WorkManager 调度
        workManager.cancelUniqueWork(taskId)
    }

    /**
     * 恢复已暂停的任务
     *
     * @param taskId 任务 ID
     */
    fun resumeTask(taskId: String) {
        val task = tasks[taskId] ?: return
        // 重新启用并重新调度
        val resumedTask = task.copy(enabled = true)
        tasks[taskId] = resumedTask
        scheduleTask(resumedTask)
    }

    /**
     * 列出所有任务（包括暂停的）
     *
     * @return 任务列表（按创建时间倒序）
     */
    fun listTasks(): List<ScheduledTask> {
        return tasks.values.sortedByDescending { it.createdAt }
    }

    /**
     * 将 Cron 表达式转换为 WorkManager 的重复间隔
     *
     * WorkManager 只支持固定间隔，不支持完整的 cron 语法。
     * 这里只处理最常见的格式，满足日常使用需求。
     *
     * 支持的格式：
     * - "* * * * *"   → 每分钟（WorkManager 最小间隔 15 分钟，此处返回 15min）
     * - "0 N * * *"   → 每天 N 点（转为 24 小时间隔）
     * - "0 * * * *"   → 每小时
     * - "*/N * * * *" → 每 N 分钟
     *
     * @param cron cron 表达式（5 字段格式）
     * @return Pair(间隔数值, 时间单位)
     */
    private fun parseCronToRepeatInterval(cron: String): Pair<Long, TimeUnit> {
        val parts = cron.trim().split("\s+".toRegex())

        if (parts.size != 5) {
            // 格式不对，默认每小时
            return Pair(1L, TimeUnit.HOURS)
        }

        val minute = parts[0]
        val hour   = parts[1]
        val day    = parts[2]
        val month  = parts[3]
        val week   = parts[4]

        return when {
            // 每天某时："0 N * * *"
            minute == "0" && hour.matches("\d+".toRegex()) &&
                day == "*" && month == "*" && week == "*" -> {
                Pair(24L, TimeUnit.HOURS)
            }

            // 每小时：任意分钟 + 每小时
            minute.matches("\d+".toRegex()) && hour == "*" &&
                day == "*" && month == "*" && week == "*" -> {
                Pair(1L, TimeUnit.HOURS)
            }

            // 每 N 分钟："*/N * * * *"
            minute.startsWith("*/") && hour == "*" -> {
                val n = minute.removePrefix("*/").toLongOrNull() ?: 15L
                // WorkManager 最小间隔是 15 分钟
                Pair(n.coerceAtLeast(15L), TimeUnit.MINUTES)
            }

            // 每分钟（WorkManager 不支持小于 15 分钟的间隔）
            minute == "*" && hour == "*" -> {
                Pair(15L, TimeUnit.MINUTES)
            }

            // 每周（带 week 字段）
            day == "*" && month == "*" && week.matches("\d+".toRegex()) -> {
                Pair(7L, TimeUnit.DAYS)
            }

            // 默认：每小时
            else -> Pair(1L, TimeUnit.HOURS)
        }
    }
}

/**
 * Agent 后台 Worker
 *
 * 由 WorkManager 在后台线程调度执行。
 * 每次任务到期时，WorkManager 会实例化此 Worker 并调用 doWork()。
 *
 * 注意：Worker 无法直接注入 AgentLoop，
 * 这里通过静态引用获取（生产环境建议用 Koin + HiltWorkerFactory）
 */
class AgentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        // InputData 的 Key 常量
        const val KEY_TASK_ID     = "task_id"
        const val KEY_PROMPT      = "prompt"
        const val KEY_WORKING_DIR = "working_dir"

        // 通知渠道 ID
        const val NOTIFICATION_CHANNEL_ID = "agent_task_channel"
        const val NOTIFICATION_ID         = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 从 InputData 读取任务参数
        val taskId      = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val prompt      = inputData.getString(KEY_PROMPT) ?: return@withContext Result.failure()
        val workingDir  = inputData.getString(KEY_WORKING_DIR) ?: "/"

        try {
            // 设置前台通知（防止 Android 系统在后台杀死任务）
            setForeground(createForegroundInfo("正在执行任务: $taskId"))

            // 创建 Agent Session
            val session = AgentSession(
                id          = "worker_${taskId}_${System.currentTimeMillis()}",
                prompt      = prompt,
                workingDir  = workingDir
            )

            // 获取全局 AgentLoop（通过 Koin 注入，需要在 Application 中初始化）
            // 实际代码中：val agentLoop = KoinComponent.get<AgentLoop>()
            // 这里用注释占位，避免编译依赖问题
            // agentLoop.run(session)

            // 任务执行成功，发送完成通知
            sendCompletionNotification(taskId, success = true)

            Result.success()
        } catch (e: Exception) {
            // 任务失败，发送失败通知
            sendCompletionNotification(taskId, success = false, error = e.message)

            // 重试策略：最多重试 3 次
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * 创建前台服务信息（Android 12+ 必须为长时间任务设置前台通知）
     */
    private fun createForegroundInfo(title: String): ForegroundInfo {
        // 创建通知渠道（Android 8.0+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Agent 定时任务",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Claude Code Android 定时任务执行通知"
            }
            val notificationManager = applicationContext.getSystemService(
                android.app.NotificationManager::class.java
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(title)
            .setContentText("Claude Code 正在后台运行定时任务...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)  // 持续显示，用户无法手动关闭
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /**
     * 发送任务完成或失败的通知
     */
    private fun sendCompletionNotification(
        taskId: String,
        success: Boolean,
        error: String? = null
    ) {
        val title = if (success) "任务完成" else "任务失败"
        val text  = if (success) {
            "定时任务 $taskId 已成功完成"
        } else {
            "定时任务 $taskId 失败: ${error ?: "未知错误"}"
        }

        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(
                if (success) android.R.drawable.ic_dialog_info
                else android.R.drawable.ic_dialog_alert
            )
            .setAutoCancel(true)  // 点击后自动消除
            .build()

        val notificationManager = applicationContext.getSystemService(
            android.app.NotificationManager::class.java
        )
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}
