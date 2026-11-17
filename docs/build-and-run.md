# 构建与运行

## 一、构建

```sh
sh build.sh                                 # 遍历七工程 mvn install -DskipTests
MSE_MODULES="mse-fs mse-search" sh build.sh # 子集
MSE_RUN_ALL_TESTS=1 sh build.sh             # 放开测试
sh build-ui.sh                              # 8 个前端工程（ai/data/fs/im web+im-ui/monitor/open/search）
```

前置：**mse-framework 仓已 `sh build.sh`**（mse-starter-parent / mse-dependencies 经仓库坐标解析）。
下游：mse-oa / mse-mp / mse-mes 各仓依赖本仓 ai-api / open-api / search-api——本仓先行构建发版。

## 二、本地运行（微服务形态）

基础设施与 9 个共享 Nacos 基线的**单一真源在 mse-framework 仓 docker/**（不在本仓复制）：

```sh
# 1) 基础设施（framework 仓）
cd <兄弟目录>/mse-framework/docker && docker compose up -d mysql redis nacos
#    nacos-config-init 自动导入 mse-micro-common/db/db-biz/dubbo/gateway/system/platform/auth/job

# 2) 导入本仓七服务专属配置（各工程 docker/nacos/config/mse-<biz>.yaml）
CONFIG_DIR=<本仓>/docker/nacos/config sh <framework仓>/docker/nacos/init-configs.sh

# 3) 起服务（构建后）
java -jar mse-open/open-boot/target/open-boot-1.0.0-SNAPSHOT.jar      # 8092，其余同理
```

网关 mse-gateway（48080）基线路由已含全部业务（`lb://mse-<biz>`）。

## 三、中间件扩展（按需，compose 在本仓各工程内）

| 工程 | 中间件 | 启动 |
|---|---|---|
| mse-fs | RustFS 对象存储 | `docker compose -f mse-fs/docker/rustfs/compose.yaml up -d`（不启动=本地磁盘降级） |
| mse-ai | Milvus 向量库 | `docker compose -f mse-ai/docker/milvus/compose.yaml up -d`（etcd+minio+milvus；`MSE_AI_MILVUS_ENABLED`） |
| mse-im | OpenIM 全套 | 见 [openim.md](openim.md)（自带 7 服务 compose） |
| mse-search | Elasticsearch | framework 仓 compose `docker compose up -d elasticsearch`（smartcn 镜像） |

## 四、镜像与 K8s

- 每个启动器自带 Dockerfile（context=本仓根）：`docker build -f mse-open/open-boot/Dockerfile -t mse/open-boot:1.0.0 .`
- 快速通道：本机已 package 后用 framework 仓 `mse-boot/Dockerfile.prebuilt`（context=模块目录）。
- K8s：`kubectl apply -f <framework仓>/mse-boot/k8s/configmap.yaml` 后 `kubectl apply -f mse-open/open-boot/k8s/open.yaml`。

## 五、前端

```sh
sh build-ui.sh mse-open/open-ui     # 单独构建（npm ci + type-check --if-present + build --base=/sub-apps/open/）
```

UI 的 mse-ui-shared 为 npm git 依赖（独立仓 `github:magoo-lau/mse-ui-shared#release/1.0.0`；根 .npmrc 已放行 allow-git，npm ci 原生安装）；本地 dev proxy `/api` → 网关 48080 或单体 8080。
