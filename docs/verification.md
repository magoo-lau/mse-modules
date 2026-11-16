# 验证要点（七工程回归清单）

完整用例目录（每业务数千条）保留在 ai-mse 仓 docs/test/（历史存档，21-35 编号对应各业务）；本仓维护最终态回归要点。

## 通用回归（每工程）

1. 登录后访问本服务页面：SQL 自动拼 tenant_id（共享租户），伪造 X-Tenant-Id 被覆盖。
2. `mse.service.<biz>.controller.enabled=false` 后默认控制器 404，自有控制器接管。
3. 租户独立库：开通独立库租户 → 业务数据落专属库，共享租户互不可见。
4. base 脚本推送：新增版本化脚本随服务启动进入租户库车队副本（mse_<biz>_schema_history 分册）。
5. 镜像：`docker build` 各 boot Dockerfile 成功；k8s 清单探针通过。

## mse-ai（8094）

- 智能体对话：平台内置智能体清单懒补齐租户可编辑副本；agentCode 他方已登记跳过不触碰。
- 知识库：上传/切片/检索（Milvus 未启动时降级语义可用）；工具中心登记 RemoteAiTool（周期重推自愈）。
- biz_query 路由门面：module 转发到各模块工具，list_modules 自描述；deny 黑名单拦截。

## mse-data（8093）

- 元数据检索/血缘、质量规则执行、引擎任务：租户隔离（行级+独立库）；调度闸门 `data-engine` 热更生效。

## mse-fs（8095）

- 上传/下载/删除：RustFS 启用走 S3，未启动自动本地降级（fs-files/ 目录）。
- 业务文件接入范式：oa-form/storage 走 api-fs 契约，FsUnavailableCondition 降级路径正确。
- 公开素材通道：`/fs/asset/{tenantId}/{fileId}.<后缀>` 免登录按租户段寻址。

## mse-im（8099）

- OpenIM 全套 compose 起停；租户开通（im-provision 备份/恢复）；web 客户端收发。
- 回调 `/im/callback/{tenantId}/{token}`：无会话凭证按 URL 租户段切库校验。
- 互斥：mobile device 登录与 PC 同端互斥。

## mse-monitor（8097）

- 平台端登录（tenant 0）可见：慢 SQL 捕获、探针状态、记录轮（MonitorScheduler 平台库轮范式）。
- 业务库扫描不落平台库（显式 push 口径）；`monitor-` 闸门热更。

## mse-open（8092）

- 凭证签发（`mse-{tenantId}-{hex}` 世界段）/目录/授权/限流/审计全链路。
- 泛化分发：目录启停即时生效；业务 Contributor 自动推送幂等。

## mse-search（8096）

- ES 索引建立/全文检索（smartcn 分词）；ES 未启动降级不影响其他工程。
- search-api 消费方（mse-oa）检索源注册可用。
