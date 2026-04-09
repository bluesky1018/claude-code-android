package com.claudecode.android.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网页搜索工具（Brave Search API）
 *
 * 使用 Brave Search API 执行网页搜索，返回前5条搜索结果的标题、URL 和摘要。
 * Brave Search 是一个尊重隐私的搜索引擎，提供高质量的搜索结果。
 *
 * API 说明：
 * - 端点：https://api.search.brave.com/res/v1/web/search
 * - 认证：需要在请求头中传递 X-Subscription-Token
 * - 免费配额：每月 2000 次查询（Free 计划）
 * - 文档：https://api.search.brave.com/app/documentation/web-search
 *
 * API Key 配置：
 * - API Key 从 SettingsRepository 中读取
 * - 如果未配置，工具会返回友好提示而非抛出异常
 * - 用户可在应用设置中配置 Brave Search API Key
 *
 * 输出格式示例：
 * ─────────────────────────────────────────────────────────
 * 搜索结果：Android Kotlin coroutines tutorial
 *
 * 1. Kotlin coroutines on Android | Android Developers
 *    https://developer.android.com/kotlin/coroutines
 *    Kotlin coroutines enable you to write clean, simplified asynchronous code...
 *
 * 2. ...
 * ─────────────────────────────────────────────────────────
 */
class WebSearchTool : Tool {

    override val name: String = "web_search"

    override val description: String = """
        使用 Brave Search API 搜索网页，返回前5条结果（标题、URL、摘要）。
        需要在应用设置中配置 Brave Search API Key，未配置时返回提示信息。
        适合搜索最新信息、技术文档、教程等。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 web_search 工具接受的参数：
     * - query（必需）：搜索关键词
     */
    override val inputSchema: JsonObject = JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "要搜索的关键词或问题，例如：'Android Kotlin coroutines best practices'"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()).asJsonObject

    /** Brave Search API 端点 */
    private val BRAVE_SEARCH_API = "https://api.search.brave.com/res/v1/web/search"

    /** 每次搜索返回的最大结果数 */
    private val MAX_RESULTS = 5

    /** 网络请求超时时间（毫秒） */
    private val TIMEOUT_MS = 15_000

    /**
     * 执行网页搜索
     *
     * 内部通过读取 SettingsRepository 获取 API Key（如果已注入），
     * 若未配置则返回引导用户配置的提示信息。
     *
     * @param input JSON 对象，包含以下字段：
     *   - query: String（必需）搜索关键词
     *
     * @return ToolResult
     *   - 成功：格式化的搜索结果列表
     *   - 失败：API Key 未配置、网络错误、API 错误等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = input.get("query")?.asString?.trim()
            ?: return@withContext ToolResult.error("缺少必需参数 'query'")

        if (query.isEmpty()) {
            return@withContext ToolResult.error("搜索关键词不能为空")
        }

        // 获取 Brave Search API Key
        // 实际项目中应从 SettingsRepository 读取，这里使用占位符实现
        val apiKey = getBraveApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext ToolResult.error(
                "Brave Search API Key 未配置。\n" +
                "请前往应用设置，在「API 配置」中填入您的 Brave Search API Key。\n" +
                "免费获取地址：https://api.search.brave.com/app/keys"
            )
        }

        return@withContext try {
            // URL 编码搜索关键词
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val requestUrl = "$BRAVE_SEARCH_API?q=$encodedQuery&count=$MAX_RESULTS&safesearch=moderate"

            // 创建 HTTP 连接
            val url = URL(requestUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("X-Subscription-Token", apiKey)
            }

            val responseCode = connection.responseCode

            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                return@withContext ToolResult.error(
                    "Brave Search API 返回错误: HTTP $responseCode\n$errorBody"
                )
            }

            // 读取响应
            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            // 解析 JSON 响应
            val responseJson = JsonParser.parseString(responseBody).asJsonObject

            // 提取搜索结果
            val webResults = responseJson
                .getAsJsonObject("web")
                ?.getAsJsonArray("results")
                ?: return@withContext ToolResult.success("搜索完成，但没有找到相关结果。")

            if (webResults.size() == 0) {
                return@withContext ToolResult.success("没有找到与「$query」相关的搜索结果。")
            }

            // 构建格式化输出
            val sb = StringBuilder()
            sb.appendLine("搜索结果：$query")
            sb.appendLine()

            webResults.take(MAX_RESULTS).forEachIndexed { index, resultElement ->
                val result = resultElement.asJsonObject
                val title = result.get("title")?.asString ?: "（无标题）"
                val resultUrl = result.get("url")?.asString ?: ""
                val description = result.get("description")?.asString ?: "（无摘要）"

                sb.appendLine("${index + 1}. $title")
                if (resultUrl.isNotEmpty()) sb.appendLine("   $resultUrl")
                sb.appendLine("   $description")
                sb.appendLine()
            }

            ToolResult.success(sb.toString().trimEnd())

        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("网络连接失败，无法访问 Brave Search API。请检查网络连接。")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("搜索请求超时（${TIMEOUT_MS}ms）。请检查网络连接或稍后重试。")
        } catch (e: Exception) {
            ToolResult.error("搜索时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 获取 Brave Search API Key
     *
     * 实际实现中，此方法应通过依赖注入获取 SettingsRepository 实例，
     * 然后从中读取用户配置的 API Key。
     *
     * 当前为简化实现，返回 null 表示未配置。
     * 集成时，请将此方法替换为从 SettingsRepository 读取的实际逻辑。
     *
     * 示例集成方式：
     * ```kotlin
     * // 在构造函数中注入
     * class WebSearchTool(private val settingsRepository: SettingsRepository) : Tool {
     *     private fun getBraveApiKey(): String? = settingsRepository.braveSearchApiKey
     * }
     * ```
     *
     * @return API Key 字符串，未配置时返回 null 或空字符串
     */
    private fun getBraveApiKey(): String? {
        // TODO: 替换为实际的 SettingsRepository 调用
        // return settingsRepository.braveSearchApiKey
        return null
    }
}
