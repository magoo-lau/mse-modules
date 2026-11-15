# OpenIM 实例部署（mse-im，单机单套）

基于官方 [openimsdk/openim-docker](https://github.com/openimsdk/openim-docker) 裁剪的部署物，配套设计见 `docs/tech/openim-integration.md`（仓库根 docs/）。

## 与官方 compose 的差异

| 项 | 说明 |
| --- | --- |
| 去掉 `openim-chat` | 账号层由 ai-mse 承担（`/auth/login` → `/im/client/session` 直连 IM server），不部署官方参考账号系统 |
| 去掉 web/admin 前端 | demo 改造后的聊天客户端直连 API/WS，不需要官方 Web 前端与管理后台 |
| 去掉 prometheus/grafana/alertmanager/node-exporter | 监控栈省资源（约省 1GB+ 内存），开通编排按需加回 |
| 容器名/网络内主机名加 `im-` 前缀 | 避免与基座 `mse-framework/docker/docker-compose.yml` 的 `redis` 等撞名 |
| redis 去掉官方 `redis.conf` 挂载 | 官方仓库不含该文件（挂载会生成目录导致启动失败），改为纯命令行参数 |
| 端口映射保持官方默认 | 10001/10002/10005/10004/12379/12380 与基座已占端口零冲突（见下表） |

## 端口核对表

| 宿主端口 | 容器 | 用途 | 与基座冲突检查 |
| --- | --- | --- | --- |
| 10001 | im-openim-server | msggateway WebSocket（客户端 `wsAddr`） | 无冲突 |
| 10002 | im-openim-server | openim-api HTTP（客户端 `apiAddr`；mse-im 内网同端口调用；**媒体上传预签名/对象读取也走它**） | 无冲突 |
| 10005 / 10004 | im-minio | 对象存储 API / 控制台（聊天图片/文件/语音/视频直传落这里，`MINIO_EXTERNAL_ADDRESS` 必须浏览器可达） | 无冲突 |
| 7880 / 7881 | im-livekit | 音视频通话信令（ws）/ RTC ICE-TCP 兜底；UDP 7880-7889 为媒体流端口段 | 无冲突 |
| 12379 / 12380 | im-etcd | 服务发现（仅联调需要暴露，生产可去掉映射） | 无冲突 |
| — | im-mongo / im-redis / im-kafka | 不映射宿主端口，仅容器网络内访问 | 无冲突 |

基座已占端口（3306/6379/8848/9848/18080/9876/10909/10911/8091/9200/8080/48080/8090-8099）均不在上表。

## 启动与验证

```bash
cd mse-modules/mse-im/docker/openim
docker compose up -d                 # 首次拉镜像约 1~2GB
docker exec im-openim-server mage check   # 健康检查
./verify.sh                          # 接口语义速查复现（Git Bash / WSL）
```

停止与数据重置：`docker compose down`（加 `-v` 不适用，数据在 `./data/` 卷——整目录删除即重置）。

## 与 mse 侧的配置对应

| OpenIM 侧（.env） | mse 侧（mse.im.server.* / 环境变量） |
| --- | --- |
| `OPENIM_SECRET` | `MSE_IM_SECRET`（两者必须一致，生产强随机） |
| `OPENIM_API_PORT` → 127.0.0.1:10002 | `MSE_IM_API_URL`（默认 http://127.0.0.1:10002） |
| `OPENIM_MSG_GATEWAY_PORT` → 10001 | `MSE_IM_WS_URL`（默认 ws://127.0.0.1:10001） |
| 生产域名反代（方案 4.1） | `MSE_IM_PUBLIC_API_URL` / `MSE_IM_PUBLIC_WS_URL` |
| `imAdminUserID`（镜像默认 `imAdmin`） | `MSE_IM_ADMIN_USER_ID`（默认 imAdmin） |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | `mse.im.livekit.api-key / api-secret`（必须一致——token 由 mse-im 签发，客户端据此进房） |
| `LIVEKIT_PORT` → 7880 | `mse.im.livekit.url`（如 `ws://127.0.0.1:7880`；生产 `wss://im.xxx.com/rtc` 反代） |

## 媒体上传与音视频通话（RTC）

- **聊天媒体（图片/语音/视频/文件）**：客户端 `sendMessage` 对 ByFile 消息自动经 openim-api `/object/*`（预签名分片直传）落 MinIO，浏览器直传需要 `MINIO_EXTERNAL_ADDRESS` 浏览器可达且反代保留 CORS 头；`publicRead=false` 读取经 `/object/<key>` 302。
- **音视频通话（1v1，LiveKit）**：`im-livekit` 容器即 LiveKit Server，`--keys` 与 mse 侧一致；信令不经 LiveKit（走 OpenIM custom 在线消息），mse-im 只签发进房 JWT。UDP 段（7880-7889）与 ICE-TCP 7881 需在防火墙/云安全组放通；Docker Desktop 下 UDP 直通通常可用，异常时客户端自动回落 ICE-TCP（质量下降但不中断）。生产建议：LiveKit 独立域名 `wss://` 反代信令 + 公网 IP 作 `LIVEKIT_NODE_IP`。

## token 策略与多端互踢

- IM token 有效期：官方默认 90 天且无法主动吊销（方案风险 #1）。**`IMENV_TOKENPOLICY_EXPIRE` 环境变量经实测不生效**，以挂载覆盖镜像内 `config/openim-rpc-auth.yml`（本目录 `config/openim-rpc-auth.yml`，`tokenPolicy.expire: 7` → 实测签发返回 `expireTimeSeconds=604800`）实现，compose volumes 已挂载。
- 多端互踢策略在镜像内 `config/share.yml` 的 `multiLogin.policy`（默认同端互踢）；联调多端场景如需调整，同样以挂载覆盖方式处理（默认不改动）。

## 接口语义速查（v3.8.3-patch.15 实测，`./verify.sh` 可复现）

| 项 | 语义 |
| --- | --- |
| get_admin_token | `POST /auth/get_admin_token {secret, userID:"imAdmin"}`，白名单免 token；返回 `token + expireTimeSeconds` |
| 首次 user_register | `{users:[{userID, nickname, faceURL}]}` → errCode=0（faceURL 空串/缺省均合法） |
| **重复 user_register** | **errCode=1102 `RegisteredAlreadyError`**（"userID registered already"）——懒供给按 1102 短路 |
| get_users_info | data 键为 **`usersInfo`** 数组（非 users） |
| get_user_token | `{userID, platformID}` → IM token；platformID 1-7，与 token 绑定 |
| **auth/force_logout** | **存在且可用**：body 须 `{userID, platformID}`（缺 platformID 报 ArgsError）——禁用账号即时踢线可行 |
| **user/update_user_info** | **可用**：body 须 `{userInfo:{userID, nickname, faceURL}}`（userID 在 userInfo 内）——资料同步可行 |
| IMENV_TOKENPOLICY_EXPIRE | **不生效**——须挂载覆盖 `config/openim-rpc-auth.yml`（本目录已带，expire=7 实测签发 604800s） |

注：Windows 控制台（Git Bash GBK）显示中文昵称乱码为终端编码问题，存储为 UTF-8，客户端显示正常。

## 开通平台编排注意事项（ImProvisionService 复用本目录模板）

以本目录 compose 为模板按租户渲染独立工作目录（`data/provisioned/tenant-{id}/`），经 docker CLI 起栈。实测踩坑与对策（已固化进编排实现）：

| 坑 | 对策 |
| --- | --- |
| 模板 `container_name` 全局唯一，多栈冲突 | 渲染时剥离 container_name（compose 项目名前缀接管命名） |
| etcd 固定端口映射多栈冲突，整块剥离后 `ports:` 空键非法 | 剥离时把裸 `ports:` 键改写为 `ports: []` |
| 单文件挂载 `./config/openim-rpc-auth.yml` 在工作目录不存在 → Docker 建成目录挂载失败 | 渲染时一并复制该覆盖文件 |
| 数据卷是 bind mount，`down -v` 不清；失败重开时残留数据与新随机凭据失配（mongo AuthenticationFailed） | 开通前清孤儿工作目录重装；失败自动 down -v + 删目录清场 |
| **Windows 端口排除段**（`netsh interface ipv4 show excludedportrange protocol=tcp`，本机 10588-11287 被保留） | `mse.im.provision.port-base` 避开排除段（本机回归用 11500）；生产 Linux 无此问题 |
| 租户编号是雪花大数 | 端口块不按租户号运算，扫既有托管实例端口取最大顺延 |

## Webhook（可选，默认不启用）

消息/在线回调需在实例 `config/webhooks.yml` 配置 url 指向 mse-im 的 `/im/callback/{tenantId}/{callbackToken}`（完整地址见租户「IM 设置」页回填值，tenantId 段用于路由租户库），并订阅 after 类事件。手动栈默认不配置（机器人/停用踢线依赖此回调）；开通编排创建的实例后续同样按需配置。
