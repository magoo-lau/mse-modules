# docker/ —— 模块区七工程镜像资产与服务配置

本目录只含本仓业务自有内容。**基础设施（MySQL/Redis/Nacos/RocketMQ/Seata/ES）与 9 个共享 Nacos 基线配置的单一真源在 mse-framework 仓 `docker/`**，本仓不复制基础设施。

## 内容

- `<biz>-boot/Dockerfile`：各启动器源码多阶段构建（context=**本仓根**，ARG MODULE/PORT 已按工程注入）
- `<biz>-boot/k8s/<biz>.yaml`：Deployment + Service 清单（复用 framework 仓 `mse-boot/k8s/configmap.yaml` 的共享 mse-config）
- `<biz>-boot/src/main/resources/application.yaml`：仅应用名 + spring.config.import（真实配置在 Nacos）
- `docker/nacos/config/mse-<biz>.yaml`：本服务专属 Data ID 留档（端口/业务参数）——即下方「服务配置」表

| 工程 | 应用名 | 端口 | 专属配置 Data ID | 依赖跨仓契约 |
|---|---|---|---|---|
| mse-ai | mse-ai | 8094 | mse-ai | api-notify（framework） |
| mse-data | mse-data | 8093 | mse-data | - |
| mse-fs | mse-fs | 8095 | mse-fs | api-fs（framework） |
| mse-im | mse-im | 8099 | mse-im | - |
| mse-monitor | mse-monitor | 8097 | mse-monitor | - |
| mse-open | mse-open | 8092 | mse-open | - |
| mse-search | mse-search | 8096 | mse-search | - |

（mse-mes / mse-oa / mse-mp 已拆至各自仓库；端口 8098 / 8090 / 8091）

## 本地运行（微服务形态，以 mse-open 为例）

1. **基础设施**（framework 仓单一真源）：
   `cd <兄弟目录>/mse-framework/docker && docker compose up -d mysql redis nacos`
   （nacos-config-init 自动导入 9 个框架基线 Data ID）
2. **导入本仓服务配置**：
   `CONFIG_DIR=<本仓>/docker/nacos/config sh <framework仓>/docker/nacos/init-configs.sh`
   （只增量导入 mse-ai / mse-data / mse-fs / mse-im / mse-monitor / mse-open / mse-search）
3. **启动服务**：`mvn install` 后 `java -jar mse-open/open-boot/target/open-boot-1.0.0-SNAPSHOT.jar`（8092）
4. 网关 mse-gateway（48080）基线路由已含全部业务（`lb://mse-open`）。
5. 中间件扩展：mse-fs 需对象存储时起 `mse-fs/docker/rustfs/compose.yaml`；mse-ai 需向量库起 `mse-ai/docker/milvus/compose.yaml`；mse-im 见 `mse-im/docker/openim/README.md`（自带全套 compose）。

## 镜像构建

```sh
# 源码构建（context=本仓根）
docker build -f mse-open/open-boot/Dockerfile -t mse/open-boot:1.0.0 .
# 快速通道：本机已 mvn package，用 framework 仓 mse-boot/Dockerfile.prebuilt（context=模块目录）
```

K8s：`kubectl apply -f mse-open/open-boot/k8s/open.yaml`（先 apply framework 仓 configmap）。
