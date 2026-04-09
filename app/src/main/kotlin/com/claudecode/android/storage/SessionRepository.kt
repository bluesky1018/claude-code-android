package com.claudecode.android.storage

import android.content.Context
import androidx.room.*
import com.claudecode.android.api.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话持久化仓库 — 像素级复刻真实 Claude Code 的会话存储机制
 *
 * 真实 Claude Code 存储方式：
 * - 位置：~/.claude/projects/<project-hash>/<session-id>.jsonl
 * - 格式：JSONL（每行一条消息，实时写入）
 * - 功能：--continue 恢复最近会话，--resume <id> 按 ID 恢复
 *
 * Android 实现：
 * - Room DB 存储会话元数据（ID、路径、时间等）
 * - 实际消息以 JSONL 格式写入文件系统（与真实 Claude Code 格式兼容）
 * - 支持会话列表、恢复、fork
 */
@Database(
    entities = [SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: SessionDatabase? = null

        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    "claude_sessions.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val workingDirectory: String,
    val name: String?,                    // -n "name" 命名会话
    val jsonlFilePath: String,            // JSONL 文件路径（与真实 Claude Code 格式兼容）
    val createdAt: Long,
    val lastActiveAt: Long,
    val messageCount: Int = 0,
    val totalCostUsd: Double = 0.0,
    val parentSessionId: String? = null,  // fork 时记录父会话 ID
    val model: String = "claude-sonnet-4-6",
    val isCompleted: Boolean = false
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE workingDirectory = :path ORDER BY lastActiveAt DESC")
    fun getSessionsByProject(path: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC LIMIT 1")
    suspend fun getMostRecent(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("UPDATE sessions SET lastActiveAt = :time, messageCount = messageCount + 1 WHERE sessionId = :id")
    suspend fun bumpActivity(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET isCompleted = 1 WHERE sessionId = :id")
    suspend fun markCompleted(id: String)

    @Query("UPDATE sessions SET totalCostUsd = :cost WHERE sessionId = :id")
    suspend fun updateCost(id: String, cost: Double)
}

/**
 * 会话仓库 — 高层 API
 */
class SessionRepository(context: Context) {

    private val db = SessionDatabase.getInstance(context)
    private val dao = db.sessionDao()
    private val sessionsDir: File = File(
        context.filesDir,
        ".claude/projects"
    ).also { it.mkdirs() }

    /** 创建新会话（同时创建 JSONL 文件） */
    suspend fun createSession(
        sessionId: String,
        workingDirectory: String,
        name: String? = null,
        parentSessionId: String? = null,
        model: String = "claude-sonnet-4-6"
    ): SessionEntity {
        // 创建 JSONL 文件（与真实 Claude Code 格式兼容）
        val projectHash = workingDirectory.hashCode().toString(16)
        val projectDir = File(sessionsDir, projectHash).also { it.mkdirs() }
        val jsonlFile = File(projectDir, "$sessionId.jsonl")
        jsonlFile.createNewFile()

        val entity = SessionEntity(
            sessionId = sessionId,
            workingDirectory = workingDirectory,
            name = name,
            jsonlFilePath = jsonlFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            parentSessionId = parentSessionId,
            model = model
        )

        dao.insert(entity)
        return entity
    }

    /** 追加消息到 JSONL 文件（实时写入，与真实 Claude Code 一致） */
    suspend fun appendMessage(sessionId: String, message: Message) {
        val entity = dao.getById(sessionId) ?: return
        val jsonlLine = Json.encodeToString(message)
        File(entity.jsonlFilePath).appendText(jsonlLine + "\n")
        dao.bumpActivity(sessionId)
    }

    /** 读取会话所有消息（从 JSONL 文件） */
    suspend fun loadMessages(sessionId: String): List<Message> {
        val entity = dao.getById(sessionId) ?: return emptyList()
        val file = File(entity.jsonlFilePath)
        if (!file.exists()) return emptyList()

        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    Json.decodeFromString<Message>(line)
                } catch (e: Exception) {
                    null
                }
            }
    }

    /** Fork 会话（创建当前会话的分支） */
    suspend fun forkSession(sourceSessionId: String): SessionEntity? {
        val source = dao.getById(sourceSessionId) ?: return null
        val newSessionId = java.util.UUID.randomUUID().toString()

        // 复制 JSONL 文件
        val newEntity = createSession(
            sessionId = newSessionId,
            workingDirectory = source.workingDirectory,
            name = "${source.name ?: "session"}-fork",
            parentSessionId = sourceSessionId,
            model = source.model
        )

        val sourceFile = File(source.jsonlFilePath)
        val destFile = File(newEntity.jsonlFilePath)
        if (sourceFile.exists()) {
            sourceFile.copyTo(destFile, overwrite = true)
        }

        return dao.getById(newSessionId)
    }

    /** 获取最近的会话（用于 --continue 功能） */
    suspend fun getMostRecentSession(): SessionEntity? = dao.getMostRecent()

    /** 按名称查找会话（用于 --resume <name>） */
    suspend fun getSessionByName(name: String): SessionEntity? = dao.getByName(name)

    /** 按 ID 查找会话（用于 --resume <id>） */
    suspend fun getSessionById(id: String): SessionEntity? = dao.getById(id)

    /** 获取所有会话列表 */
    fun getAllSessions(): Flow<List<SessionEntity>> = dao.getAllSessions()

    /** 获取项目的所有会话 */
    fun getSessionsByProject(workingDirectory: String): Flow<List<SessionEntity>> =
        dao.getSessionsByProject(workingDirectory)

    /** 标记会话完成 */
    suspend fun markCompleted(sessionId: String) = dao.markCompleted(sessionId)

    /** 更新会话成本 */
    suspend fun updateCost(sessionId: String, costUsd: Double) = dao.updateCost(sessionId, costUsd)

    /** 删除会话（同时删除 JSONL 文件） */
    suspend fun deleteSession(sessionId: String) {
        val entity = dao.getById(sessionId) ?: return
        File(entity.jsonlFilePath).delete()
        dao.delete(entity)
    }
}
