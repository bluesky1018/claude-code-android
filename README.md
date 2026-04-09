# Claude Code Android

> **Kotlin 原生实现的 Claude Code Android 版本**，像素级复现 Anthropic Claude Code 的所有核心机制。

[![Android](https://img.shields.io/badge/Platform-Android-green)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## ✨ 核心特性

| 特性 | 说明 |
|------|------|
| 🔄 **Agent Loop** | 完整的工具调用循环，支持多轮工具调用直到任务完成 |
| 🛠️ **14个工具** | Read/Write/Edit/Glob/Grep/Bash/WebSearch/WebFetch/Todo/Notebook/Agent 等 |
| 🧠 **记忆系统** | CLAUDE.md 4层记忆（企业/用户/项目/子目录）+ Room DB 跨会话记忆 |
| 📦 **上下文压缩** | Token 计数 → 自动摘要压缩 → 防止超出 200K 上下文窗口 |
| 🪝 **Hook 系统** | PreToolUse/PostToolUse/Stop/SubagentStop，支持阻断/修改工具行为 |
| ⏰ **定时任务** | WorkManager Cron，后台自动运行 Agent 任务 |
| 💓 **心跳机制** | 30秒心跳，Token 告警，长时间运行保活 |
| 🔐 **权限系统** | DEFAULT/ACCEPT_EDITS/BYPASS_ALL/PLAN_ONLY 四种模式 |

## 🏗️ 架构

```
app/src/main/kotlin/com/claudecode/android/
├── api/          # Anthropic API 通信（SSE 流式）
├── agent/        # Agent Loop 核心引擎
├── tools/        # 14个工具实现
├── memory/       # CLAUDE.md 记忆系统
├── context/      # 上下文压缩管理
├── hooks/        # Hook 系统
├── scheduler/    # 定时任务（WorkManager）
├── heartbeat/    # 心跳机制
├── session/      # 会话 + 权限管理
├── storage/      # Room DB 持久化
└── ui/           # Jetpack Compose UI
```

## 🚀 快速开始

1. Clone 仓库
2. 用 Android Studio 打开
3. 在 Settings 页填入 Anthropic API Key
4. 运行 App

## 📋 开发计划

- [x] 项目骨架搭建
- [ ] Phase 1: API Client + Agent Loop
- [ ] Phase 2: 完整工具集
- [ ] Phase 3: 记忆 + 压缩 + Hooks
- [ ] Phase 4: 定时任务 + 心跳
- [ ] Phase 5: 完整 UI

## 📄 License

MIT License
