# mse-modules 文档索引

模块区七工程（微服务形态，各工程独立 Maven 项目）。

## 工程一览

| 工程 | 业务 | 端口 | 文档入口 |
|---|---|---|---|
| mse-ai | AI 智能体平台（Spring AI 2.x：智能体/工作流/知识库/MCP） | 8094 | ai/README.md |
| mse-data | 数据中台（元数据/质量/引擎） | 8093 | mse-data/README.md |
| mse-fs | 文件存储（RustFS S3 兼容/本地降级） | 8095 | mse-fs/README.md |
| mse-im | 即时通讯（OpenIM 集成 + web/desktop 客户端） | 8099 | mse-im/README.md、[openim.md](openim.md) |
| mse-monitor | 监控中心（平台端子应用：慢 SQL/探针/记录） | 8097 | mse-monitor/README.md |
| mse-open | 开放平台（凭证/目录/授权/限流/审计，对外统一窗口） | 8092 | mse-open/README.md |
| mse-search | 搜索引擎（Elasticsearch 9） | 8096 | mse-search/README.md |

mse-mes / mse-oa / mse-mp 已拆至兄弟仓库（端口 8098 / 8090 / 8091）。

## 专题文档

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 工程拼法、跨仓契约归属、能力开放与 AI 接入、租户红线 |
| [build-and-run.md](build-and-run.md) | 构建脚本、本地运行（依赖 framework 仓基础设施）、端口与配置 |
| [verification.md](verification.md) | 各业务验证要点 |
| [openim.md](openim.md) | mse-im 的 OpenIM 集成 |

## 跨仓关系

- 底座与基础设施单一真源：mse-framework 仓（构建前置 + docker compose + Nacos 基线）
- 消费方：mse-oa 仓依赖本仓 ai-api / open-api / search-api；mse-mp 仓依赖 open-api / ai-api；mse-mes 仓依赖 ai-api / open-api——**本仓先行构建发版**
- 资源登记（权限号段/子应用 id）：mse-agent-plugin 仓 references/alloc-tables.md
