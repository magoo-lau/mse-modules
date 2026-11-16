# mse-im × OpenIM 集成

## 一、组件

```
mse-im/
├── im-boot / im-service / im-api / im-ui     # MSE 侧：租户开通、账号映射、工作台、消息审计
├── clients/web                                # web 客户端（Vite，/clients/web/ base）
├── clients/desktop                            # Electron 桌面端（平台特定，单独手工构建）
└── docker/openim/                             # OpenIM 全套中间件 compose（im-mongo/im-redis/im-etcd/im-kafka/im-minio/im-livekit/im-openim-server）
```

## 二、本地联调（快速联调步骤）

1. 起 OpenIM 中间件：`docker compose -f mse-im/docker/openim/docker-compose.yaml up -d`（先读该目录 README.md 与 .env 说明）。
2. 验证环境：`sh mse-im/docker/openim/verify.sh`。
3. im-boot 接入 Nacos（mse-im.yaml 基线导入，见 build-and-run.md）后启动（8099）。
4. web 客户端：`cd clients/web && npm ci && npm run dev`（proxy /api 同基座后端）。

## 三、租户与回调约定

- **无上下文凭证进门**：MSE 回调 URL 自带世界段 `/im/callback/{tenantId}/{token}`——解析租户号切库后在所属世界校验。
- 租户开通时 provision OpenIM 账号/租户空间（备份产物 im-provision-backups/，gitignore）。
- 凭证数据主场=租户世界；im_admin 类凭证按租户隔离。
- 文件/图片消息经 mse-fs 存储（api-fs 契约，本地降级 fs-files/）。

## 四、部署

- im-boot 镜像：`docker build -f mse-im/im-boot/Dockerfile -t mse/im-boot:1.0.0 .`（context=本仓根）；k8s 清单 `im-boot/k8s/im.yaml`。
- OpenIM 侧部署与生产参数（kafka/minio/livekit）见 docker/openim/README.md；生产建议独立集群并配置回调地址到网关 `/im/callback/...`。
