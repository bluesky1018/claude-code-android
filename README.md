# Claude Code Android

> 基于 Kotlin + Jetpack Compose 的 Claude Code 完整 Android 实现，版本 v2.0

## 项目简介

Claude Code Android 是 Anthropic Claude Code CLI 的 Android 原生移植版本，使用 Kotlin 和 Jetpack Compose 构建。本项目力求在移动端完整复现 Claude Code 的核心能力，包括 Agent Loop、15 个工具、记忆系统、上下文压缩、Hook 系统、权限管理、MCP 客户端、Skills 系统和会话持久化等功能。

---

## 实现度说明

| 模块 | 实现状态 | 说明 |
|------|----------|------|
| Agent Loop | 完整实现 | 并发工具执行、无限迭代、pause_turn/refusal/budget 支持 |
| 15 个工具 | 完整实现 | Read/Write/Edit/MultiEdit/Glob/Grep/LS/Bash/WebSearch/WebFetch/TodoRead/TodoWrite/NotebookRead/NotebookEdit/Agent |
| 记忆系统 | 完整实现 | CLAUDE.md 四层 + @import + CLAUDE.local.md + Auto Memory |
| 上下文压缩 | 完整实现 | 三级策略（85%/90%/95%） |
| Hook 系统 | 完整实现 | 23 个事件，command/http/prompt 三种类型 |
| 权限系统 | 完整实现 | 6 种模式，allow/deny/ask 规则引擎 |
| MCP 客户端 | 完整实现 | JSON-RPC 2.0，stdio/http 传输层 |
| Skills 系统 | 完整实现 | .claude/skills/*.md 斜杠命令 |
| 会话持久化 | 完整实现 | JSONL 文件 + Room DB，fork/resume/continue |
| 定时任务 | 完整实现 | WorkManager Cron |
| 心跳机制 | 完整实现 | 30 秒间隔 |
| Computer Use | 未实现 | Android 平台限制 |
| GitHub 集成 | 部分实现 | 无原生 PR/Issue 操作 |

---

## 架构图

```
┌─────────────────────────────────────────────────────────┐
│                   Claude Code Android                    │
│                                                         │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────┐   │
│  │   UI     │   │ViewModel │   │   Agent Loop      │   │
│  │ Compose  │◄──│  Koin DI │◄──│  (AgentEngine)   │   │
│  └──────────┘   └──────────┘   └────────┬─────────┘   │
│                                          │              │
│  ┌───────────────────────────────────────▼───────────┐ │
│  │                  Tool Executor (15 Tools)          │ │
│  │  Read  Write  Edit  Glob  Grep  LS  Bash          │ │
│  │  WebSearch  WebFetch  Todo  Notebook  Agent        │ │
│  └───────────────┬───────────────────────────────────┘ │
│                  │                                      │
│  ┌───────────────▼───────────────────────────────────┐ │
│  │              Core Systems                          │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │ │
│  │  │ Memory   │ │  Hook    │ │  Permission       │  │ │
│  │  │ System   │ │  System  │ │  Engine           │  │ │
│  │  └──────────┘ └──────────┘ └──────────────────┘  │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │ │
│  │  │   MCP    │ │  Skills  │ │  Context          │  │ │
│  │  │  Client  │ │  System  │ │  Compressor       │  │ │
│  │  └──────────┘ └──────────┘ └──────────────────┘  │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │              Data Layer                            │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │ │
│  │  │  Room DB │ │  JSONL   │ │  SecurityCrypto   │  │ │
│  │  │ Sessions │ │  Files   │ │  (API Keys)       │  │ │
│  │  └──────────┘ └──────────┘ └──────────────────┘  │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │           Network Layer                            │ │
│  │  ┌──────────────────┐  ┌──────────────────────┐  │ │
│  │  │  Ktor (SSE/HTTP) │  │  OkHttp (Hooks/Web)  │  │ │
│  │  └──────────────────┘  └──────────────────────┘  │ │
│  └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## 核心功能清单

- [x] SSE 流式输出（Ktor + Server-Sent Events）
- [x] Agent Loop 无限迭代，支持 stop_reason 处理
- [x] 并发工具执行（多工具同时运行）
- [x] pause_turn / refusal / token budget 机制
- [x] 15 个完整工具实现
- [x] CLAUDE.md 四层记忆（项目/父目录/用户/全局）
- [x] @import 指令支持
- [x] CLAUDE.local.md 本地覆盖
- [x] MEMORY.md 自动记忆写入
- [x] 上下文三级压缩（85%/90%/95% 阈值）
- [x] Hook 系统（23 事件 + command/http/prompt 类型）
- [x] 权限系统（6 模式 + allow/deny/ask 规则引擎）
- [x] MCP 客户端（JSON-RPC 2.0，stdio/http）
- [x] Skills 斜杠命令（.claude/skills/*.md）
- [x] 会话持久化（JSONL + Room DB）
- [x] 会话 fork / resume / continue
- [x] WorkManager 定时任务（Cron 表达式）
- [x] 心跳机制（30 秒间隔）
- [x] 代码编辑器（Sora Editor + Tree-sitter 语法高亮）
- [x] Markdown 渲染（multiplatform-markdown-renderer）
- [x] 加密存储 API Key（AndroidX Security Crypto）
- [x] Koin 依赖注入

---

## 快速开始

### 1. 配置 API Key

进入应用设置页面，在「API Key」字段中填入你的 Anthropic API Key（格式：`sk-ant-...`）。API Key 使用 AndroidX Security Crypto 加密存储于设备本地，不会上传。

### 2. 设置工作目录

在设置页面选择工作目录，建议选择设备存储中的项目文件夹。应用会自动扫描该目录下的 CLAUDE.md 文件以加载记忆。

### 3. 开始对话

点击主界面右下角的「+」按钮创建新会话，输入你的指令，按发送即可开始与 Claude 交互。

---

## 完整功能说明

### Agent Loop

Agent Loop 是整个系统的核心驱动。每一轮对话都遵循以下流程：

```
用户输入
   ↓
发送至 Claude API（SSE 流式）
   ↓
接收 tool_use 块
   ↓
并发执行所有工具
   ↓
将工具结果作为 tool_result 追加消息
   ↓
再次调用 API（循环）
   ↓
直到 stop_reason = end_turn / pause_turn / refusal / max_tokens
```

支持的停止原因：
- `end_turn`：正常结束
- `pause_turn`：等待用户确认（权限检查）
- `refusal`：模型拒绝执行
- `max_tokens`：Token budget 耗尽，自动触发上下文压缩

### 15 个工具

| 工具 | 说明 |
|------|------|
| `Read` | 读取文件内容，支持行号范围 |
| `Write` | 写入文件（完整覆盖） |
| `Edit` | 精确字符串替换，支持 replace_all |
| `MultiEdit` | 批量编辑单个文件（多个 Edit 操作） |
| `Glob` | 文件模式匹配，按修改时间排序 |
| `Grep` | 正则内容搜索（ripgrep 语义） |
| `LS` | 目录列表 |
| `Bash` | Shell 命令执行，支持超时 |
| `WebSearch` | 网络搜索 |
| `WebFetch` | URL 内容获取（Jsoup HTML 解析） |
| `TodoRead` | 读取任务列表 |
| `TodoWrite` | 写入/更新任务列表 |
| `NotebookRead` | 读取 Jupyter Notebook |
| `NotebookEdit` | 编辑 Notebook 单元格 |
| `Agent` | 启动子 Agent（嵌套执行） |

### 记忆系统

CLAUDE.md 文件按以下优先级加载（高优先级覆盖低优先级）：

```
1. 全局：~/.claude/CLAUDE.md
2. 用户：~/CLAUDE.md
3. 父目录：<workdir>/../CLAUDE.md（递归向上）
4. 项目：<workdir>/CLAUDE.md（最高优先级）
5. 本地覆盖：<workdir>/CLAUDE.local.md
```

支持 `@import path/to/file.md` 指令在 CLAUDE.md 中引入其他文件内容。

MEMORY.md 自动记忆：Agent 可以通过 `Write` 工具向 `.claude/MEMORY.md` 写入持久化记忆，下次启动时自动加载。

### 上下文压缩

当 Token 使用量达到阈值时自动触发：

| 级别 | 阈值 | 策略 |
|------|------|------|
| 1 | 85% | 压缩早期工具调用结果 |
| 2 | 90% | 压缩对话历史摘要 |
| 3 | 95% | 激进压缩，保留核心上下文 |

### Hook 系统

支持 23 个 Hook 事件，3 种 Hook 类型：

**事件列表：**
- `PreToolCall`、`PostToolCall`
- `PreBashExec`、`PostBashExec`
- `PreFileRead`、`PostFileRead`
- `PreFileWrite`、`PostFileWrite`
- `PreWebFetch`、`PostWebFetch`
- `PreAgentLoop`、`PostAgentLoop`
- `OnContextCompression`
- `OnPermissionRequest`
- `OnSessionStart`、`OnSessionEnd`
- `OnMemoryLoad`、`OnMemoryWrite`
- `OnMCPConnect`、`OnMCPDisconnect`
- `OnHeartbeat`
- `OnError`
- `OnTokenBudget`

**Hook 类型：**
- `command`：执行 Shell 命令
- `http`：发送 HTTP 请求
- `prompt`：注入额外的系统提示

### 权限系统

6 种权限模式：

| 模式 | 说明 |
|------|------|
| `auto` | 自动批准安全操作 |
| `ask` | 敏感操作需确认 |
| `deny_all` | 拒绝所有工具调用 |
| `allow_all` | 允许所有操作（危险） |
| `read_only` | 仅允许读操作 |
| `custom` | 自定义规则引擎 |

自定义规则引擎支持 allow/deny/ask 三种动作，可按工具类型、路径模式、命令前缀进行精细控制。

### MCP 客户端

实现完整的 JSON-RPC 2.0 协议，支持：
- `stdio` 传输层：通过 stdin/stdout 与本地 MCP Server 通信
- `http` 传输层：通过 HTTP POST 与远程 MCP Server 通信
- 工具发现（`tools/list`）
- 工具调用（`tools/call`）
- 资源读取（`resources/read`）
- 提示模板（`prompts/get`）

### Skills 系统

在项目的 `.claude/skills/` 目录下创建 Markdown 文件即可定义斜杠命令：

```
.claude/
  skills/
    commit.md      → /commit
    review-pr.md   → /review-pr
    deploy.md      → /deploy
```

用户在对话框输入 `/commit` 时，系统自动加载对应 Markdown 文件内容作为指令前缀。

### 会话持久化

- **JSONL 文件**：每条消息实时追加写入 `.claude/sessions/<session_id>.jsonl`
- **Room DB**：存储会话元数据（ID、标题、创建时间、最后活跃时间、消息数）
- **fork**：从当前会话某条消息处创建分支会话
- **resume**：恢复已有会话，加载完整历史
- **continue**：继续上次会话（自动加载最近会话）

### 定时任务

基于 WorkManager 实现 Cron 表达式调度：

```kotlin
// 示例：每天早上 9 点执行
scheduleTask(
    prompt = "检查项目构建状态并发送报告",
    cronExpression = "0 9 * * *",
    contextMode = ContextMode.ISOLATED
)
```

### 心跳机制

每 30 秒向服务器发送一次心跳，触发 `OnHeartbeat` Hook 事件，可用于：
- 连接保活
- 定期状态同步
- 触发轻量级后台任务

---

## Hook 配置示例

在 `.claude/settings.json` 中配置 Hook：

```json
{
  "hooks": {
    "PreToolCall": [
      {
        "type": "command",
        "command": "echo 'Tool called: $TOOL_NAME' >> /tmp/claude-audit.log"
      }
    ],
    "PostFileWrite": [
      {
        "type": "http",
        "url": "https://your-server.com/webhook",
        "method": "POST",
        "headers": {
          "Content-Type": "application/json"
        }
      }
    ],
    "PreBashExec": [
      {
        "type": "prompt",
        "content": "执行 Bash 命令前请先检查命令是否安全，避免破坏性操作。"
      }
    ]
  }
}
```

---

## MCP Server 配置示例

在 `.claude/mcp_servers.json` 中配置 MCP Server：

```json
{
  "servers": [
    {
      "name": "filesystem",
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
    },
    {
      "name": "remote-tools",
      "transport": "http",
      "url": "https://your-mcp-server.com/mcp",
      "headers": {
        "Authorization": "Bearer your-token"
      }
    }
  ]
}
```

---

## 对比真实 Claude Code 的差距

尽管本项目力求完整复现，以下方面仍与官方 Claude Code 存在差距：

| 功能 | 状态 | 原因 |
|------|------|------|
| Computer Use（截图/点击） | 未实现 | Android 无 Desktop 环境 |
| GitHub 原生集成（PR/Issue） | 部分实现 | 缺少 `gh` CLI |
| VS Code / IDE 插件 | 不适用 | 移动端无 IDE |
| 多显示器支持 | 不适用 | 移动端 |
| 原生文件系统通知 | 部分实现 | Android 权限限制 |

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 |
| 网络（流式） | Ktor 3.0.3（SSE） |
| 网络（通用） | OkHttp 4.12.0 |
| HTML 解析 | Jsoup 1.18.3 |
| 代码编辑器 | Sora Editor 0.23.4 |
| Markdown | multiplatform-markdown-renderer 0.27.0 |
| 数据库 | Room 2.6.1 |
| 后台任务 | WorkManager 2.10.0 |
| 依赖注入 | Koin 3.5.6 |
| 安全存储 | AndroidX Security Crypto |
| 序列化 | Kotlinx Serialization 1.7.3 |
| 协程 | Kotlinx Coroutines 1.9.0 |

---

## 项目结构

```
app/
├── src/main/
│   ├── java/com/claudecode/android/
│   │   ├── MainActivity.kt
│   │   ├── ClaudeCodeApp.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── screens/
│   │   │   │   ├── HomeScreen.kt
│   │   │   │   ├── ChatScreen.kt
│   │   │   │   ├── SettingsScreen.kt
│   │   │   │   └── SessionListScreen.kt
│   │   │   ├── components/
│   │   │   │   ├── MessageBubble.kt
│   │   │   │   ├── ToolCallCard.kt
│   │   │   │   ├── MarkdownRenderer.kt
│   │   │   │   └── CodeEditor.kt
│   │   │   └── theme/
│   │   │       ├── Theme.kt
│   │   │       ├── Color.kt
│   │   │       └── Type.kt
│   │   │
│   │   ├── agent/
│   │   │   ├── AgentEngine.kt          # Agent Loop 核心
│   │   │   ├── AgentLoop.kt
│   │   │   ├── ContextCompressor.kt   # 三级压缩
│   │   │   └── HeartbeatManager.kt    # 心跳
│   │   │
│   │   ├── tools/
│   │   │   ├── ToolExecutor.kt        # 并发工具执行器
│   │   │   ├── ReadTool.kt
│   │   │   ├── WriteTool.kt
│   │   │   ├── EditTool.kt
│   │   │   ├── MultiEditTool.kt
│   │   │   ├── GlobTool.kt
│   │   │   ├── GrepTool.kt
│   │   │   ├── LsTool.kt
│   │   │   ├── BashTool.kt
│   │   │   ├── WebSearchTool.kt
│   │   │   ├── WebFetchTool.kt
│   │   │   ├── TodoReadTool.kt
│   │   │   ├── TodoWriteTool.kt
│   │   │   ├── NotebookReadTool.kt
│   │   │   ├── NotebookEditTool.kt
│   │   │   └── AgentTool.kt
│   │   │
│   │   ├── memory/
│   │   │   ├── MemorySystem.kt        # CLAUDE.md 四层加载
│   │   │   ├── MemoryLoader.kt
│   │   │   └── AutoMemoryWriter.kt   # MEMORY.md 自动写入
│   │   │
│   │   ├── hooks/
│   │   │   ├── HookManager.kt         # 23 事件分发
│   │   │   ├── CommandHook.kt
│   │   │   ├── HttpHook.kt
│   │   │   └── PromptHook.kt
│   │   │
│   │   ├── permissions/
│   │   │   ├── PermissionEngine.kt    # 6 模式引擎
│   │   │   └── PermissionRules.kt    # allow/deny/ask 规则
│   │   │
│   │   ├── mcp/
│   │   │   ├── MCPClient.kt           # JSON-RPC 2.0
│   │   │   ├── StdioTransport.kt
│   │   │   └── HttpTransport.kt
│   │   │
│   │   ├── skills/
│   │   │   └── SkillsManager.kt       # .claude/skills/*.md
│   │   │
│   │   ├── session/
│   │   │   ├── SessionManager.kt      # fork/resume/continue
│   │   │   ├── JsonlWriter.kt
│   │   │   └── SessionRepository.kt
│   │   │
│   │   ├── scheduler/
│   │   │   └── TaskScheduler.kt       # WorkManager Cron
│   │   │
│   │   ├── data/
│   │   │   ├── db/
│   │   │   │   ├── AppDatabase.kt
│   │   │   │   ├── SessionDao.kt
│   │   │   │   └── entities/
│   │   │   ├── crypto/
│   │   │   │   └── SecureStorage.kt   # AndroidX Security Crypto
│   │   │   └── preferences/
│   │   │       └── AppPreferences.kt
│   │   │
│   │   ├── network/
│   │   │   ├── ClaudeApiClient.kt     # Ktor SSE 客户端
│   │   │   ├── SseParser.kt
│   │   │   └── OkHttpClient.kt
│   │   │
│   │   └── di/
│   │       └── AppModule.kt           # Koin 模块
│   │
│   └── res/
│       ├── layout/
│       ├── values/
│       └── xml/
│
├── build.gradle.kts
└── proguard-rules.pro

gradle/
├── libs.versions.toml
└── wrapper/

.claude/
├── settings.json      # Hook + 权限配置
├── mcp_servers.json   # MCP Server 配置
├── skills/            # Skills 斜杠命令
│   ├── commit.md
│   └── review.md
└── sessions/          # JSONL 会话文件
```

---

## License

MIT License. Copyright (c) 2024 bluesky1018.
