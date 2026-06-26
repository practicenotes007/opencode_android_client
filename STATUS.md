# OpenCode Android Client — 项目状态

> 更新：2026-06-25 | 分支：master

## 当前阶段

v1.x 功能迭代中。

## 最近完成

- **TTS Provider 抽象重构**（2026-06-25）：将 `TextToSpeechManager`（Android 系统 TTS）抽象为 `TtsProvider` 接口，新增 `TtsManager` 协调层统一管理多 Provider。预留 百炼（Alibaba Bailian）和 火山方舟（ByteDance Volcano Ark）云 TTS Provider 接口，含 API 端点文档、配置界面和音频缓存架构。Settings 页面新增 TTS 提供商选择（系统 TTS / 百炼 / 火山方舟）及对应 API Key 配置字段。
- **语音朗读功能**（2026-06-13）：文件预览页右上角音量按钮 → AI 总结文档提纲 → TTS 逐段朗读 → 按文件路径缓存进度 → 断点续读。CI 通过，已合并 master。

## 待优化

| 项目 | 优先级 | 说明 |
|------|--------|------|
| 百炼 TTS 真实 API 接入 | P2 | `BailianTtsProvider` 已预留接口和 API 文档，需实现 STS 鉴权 + HTTP 请求 + 音频流解析 |
| 火山方舟 TTS 真实 API 接入 | P2 | `VolcanoArkTtsProvider` 已预留接口和 API 文档，需实现 Bearer 鉴权 + HTTP 请求 + 音频解码 |

## 阻塞

无。
