package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.util.concurrent.TimeUnit

/**
 * 网页内容抓取工具
 *
 * 抓取指定 URL 的网页内容，解析 HTML 提取正文文本（移除脚本、样式、导航等非正文元素），
 * 返回清洁的可读文本内容。
 *
 * 技术实现：
 * ─────────────────────────────────────────────────────────
 * 1. OkHttp：负责网络请求
 *    - 自动处理重定向（最多5次）
 *    - 支持 gzip 压缩自动解压
 *    - 设置合理的超时时间
 *
 * 2. jsoup：负责 HTML 解析
 *    - jsoup 是 Java/Kotlin 中最流行的 HTML 解析库
 *    - 使用 CSS 选择器移除不需要的元素（script、style、nav 等）
 *    - 提取 <body> 中的纯文本内容
 *    - 自动处理 HTML 实体（如 &amp; → &）
 *
 * jsoup 使用方式说明：
 * ─────────────────────────────────────────────────────────
 * // 解析 HTML 字符串
 * val doc: Document = Jsoup.parse(htmlString)
 *
 * // 移除不需要的元素
 * doc.select("script, style, nav").remove()
 *
 * // 获取纯文本
 * val text = doc.body().text()  // 自动处理空白和实体
 *
 * // 使用 CSS 选择器查找特定元素
 * val mainContent = doc.select("article, main, .content").firstOrNull()
 * ─────────────────────────────────────────────────────────
 *
 * 注意事项：
 * - 某些网页需要 JavaScript 渲染才能显示内容，静态抓取可能获取不到完整内容
 * - 部分网站会限制爬虫访问（403 Forbidden），可能需要设置 User-Agent
 * - 最多返回 10000 字符，超长内容会被截断
 */
class WebFetchTool : Tool {

    override val name: String = "web_fetch"

    override val description: String = """
        抓取指定 URL 的网页内容，解析 HTML 提取正文文本。
        使用 OkHttp 发起网络请求，jsoup 解析 HTML 并移除脚本、样式、导航等非正文元素。
        最多返回 10000 字符的正文内容。

        注意：
        - 需要 JavaScript 渲染的动态网页可能获取不到完整内容
        - 返回的是纯文本，不包含 HTML 标签
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 web_fetch 工具接受的参数：
     * - url（必需）：要抓取的网页 URL
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "要抓取内容的网页 URL，必须以 http:// 或 https:// 开头。例如：'https://developer.android.com/kotlin/coroutines'"
                }
            },
            "required": ["url"]
        }
    """.trimIndent()).asJsonObject

    /** 返回内容的最大字符数 */
    private val MAX_CONTENT_LENGTH = 10_000

    /** 网络请求超时时间（秒） */
    private val TIMEOUT_SECONDS = 20L

    /**
     * OkHttp 客户端（单例，在多次调用间复用连接池）
     *
     * 配置了合理的超时时间和重定向跟随策略。
     * 在生产代码中，建议通过依赖注入提供 OkHttpClient，而非在工具内部创建。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)          // 自动跟随 HTTP 重定向
            .followSslRedirects(true)       // 自动跟随 HTTPS 重定向
            .build()
    }

    /**
     * 执行网页内容抓取
     *
     * @param input JSON 对象，包含以下字段：
     *   - url: String（必需）要抓取的网页 URL
     *
     * @return ToolResult
     *   - 成功：解析后的网页正文文本（最多 MAX_CONTENT_LENGTH 字符）
     *   - 失败：网络错误、HTTP 错误状态码、解析错误等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = input.get("url")?.asString?.trim()
            ?: return@withContext ToolResult.error("缺少必需参数 'url'")

        // 检查 URL 格式
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("URL 格式无效: '$url'。URL 必须以 http:// 或 https:// 开头。")
        }

        return@withContext try {
            // 构建 HTTP 请求
            val request = Request.Builder()
                .url(url)
                // 设置常见的浏览器 User-Agent，减少被拒绝的概率
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()

            // 发起请求
            val response = httpClient.newCall(request).execute()

            // 检查 HTTP 状态码
            if (!response.isSuccessful) {
                return@withContext ToolResult.error(
                    "HTTP 请求失败: ${response.code} ${response.message}\nURL: $url"
                )
            }

            // 获取内容类型，判断是否为 HTML
            val contentType = response.header("Content-Type") ?: ""
            val responseBody = response.body?.string()
                ?: return@withContext ToolResult.error("响应体为空: $url")

            response.close()

            // 如果不是 HTML（如 JSON、XML、纯文本），直接返回原始内容
            if (!contentType.contains("html", ignoreCase = true)) {
                val truncated = if (responseBody.length > MAX_CONTENT_LENGTH) {
                    responseBody.take(MAX_CONTENT_LENGTH) + "\n\n[内容已截断，原始长度: ${responseBody.length} 字符]"
                } else {
                    responseBody
                }
                return@withContext ToolResult.success(
                    "URL: $url\n内容类型: $contentType\n\n$truncated"
                )
            }

            // 使用 jsoup 解析 HTML
            val doc: Document = Jsoup.parse(responseBody, url)

            // 提取页面标题
            val pageTitle = doc.title()

            // 移除不需要的元素：脚本、样式、导航、页眉、页脚、广告等
            // 这些元素通常不包含有价值的正文内容
            doc.select(
                "script, style, noscript, " +          // 脚本和样式
                "nav, header, footer, " +              // 导航、页眉、页脚
                "aside, .sidebar, #sidebar, " +        // 侧边栏
                ".ad, .ads, .advertisement, " +        // 广告
                ".cookie-notice, .cookie-banner, " +   // Cookie 提示
                ".popup, .modal, " +                   // 弹窗
                "[aria-hidden='true']"                 // 对屏幕阅读器隐藏的内容
            ).remove()

            // 尝试提取主要内容区域（按优先级）
            val mainContentElement = doc.select("article, main, [role='main'], .content, #content, .post-content").firstOrNull()
                ?: doc.body()

            // 获取纯文本，jsoup 会自动：
            // 1. 将块级元素（div、p 等）转换为换行
            // 2. 处理 HTML 实体（&amp; → &）
            // 3. 合并多余的空白字符
            var extractedText = mainContentElement?.text() ?: ""

            // 去除多余的连续空行（jsoup 有时会留下多个空行）
            extractedText = extractedText.replace(Regex("\n{3,}"), "\n\n").trim()

            // 截断超长内容
            val isTruncated = extractedText.length > MAX_CONTENT_LENGTH
            val finalText = if (isTruncated) {
                extractedText.take(MAX_CONTENT_LENGTH)
            } else {
                extractedText
            }

            // 构建输出
            val sb = StringBuilder()
            sb.appendLine("URL: $url")
            if (pageTitle.isNotEmpty()) sb.appendLine("标题: $pageTitle")
            sb.appendLine()
            sb.append(finalText)
            if (isTruncated) {
                sb.appendLine()
                sb.appendLine()
                sb.append("[内容已截断，原始长度约 ${extractedText.length} 字符，已显示前 $MAX_CONTENT_LENGTH 字符]")
            }

            ToolResult.success(sb.toString())

        } catch (e: java.net.MalformedURLException) {
            ToolResult.error("URL 格式错误: '$url' — ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("无法解析域名，请检查 URL 或网络连接: $url")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("请求超时（${TIMEOUT_SECONDS}s）: $url")
        } catch (e: java.io.IOException) {
            ToolResult.error("网络 I/O 错误: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            ToolResult.error("URL 非法: '$url' — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("抓取网页时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
