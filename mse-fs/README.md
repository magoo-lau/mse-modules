# mse-fs —— 文件存储服务（RustFS 1.x / 本地文件系统可配置回退）

独立 Maven 业务工程（不进 ai-mse 根 pom 聚合），基于基座 `mse-starter-parent` 组装，拼法与 `mse-sample/mse-demo` 一致。

## 一、能力一览

| 能力 | 说明 |
| --- | --- |
| 统一存储抽象 | `FileStorage` 接口（store/open/delete/presign），两实现按配置互斥装配 |
| 本地文件系统（缺省） | `mse.fs.storage.type=local`（或完全不配置）：目录 `{baseDir}/{tenantId}/{yyyyMM}/{fileId}.{ext}`，零外部依赖 |
| RustFS 1.x 对象存储 | `mse.fs.storage.type=rustfs`：经 AWS SDK for Java v2（`software.amazon.awssdk:s3`）走 S3 兼容协议；同样适用于 MinIO 等其他 S3 兼容存储 |
| 租户隔离（双层） | ① 元数据层：`fs_file` 行级隔离（继承 BaseEntity，租户插件自动填充/改写，跨租户 fileId 直接 404）；② 物理层：存储键首段恒为租户 ID（`{tenantId}/...`），本地目录与桶内对象互不可见 |
| 文件管理 | 上传（multipart）/ 分页检索（文件名模糊/业务分类/时间范围）/ 代理下载（流式 + 鉴权 + 审计）/ 删除（逻辑删元数据 + 尽力删物理对象） |
| 预签名直链（可选） | 仅 rustfs 模式且 `presign.enabled=true`：按单键限时签发，减轻服务流量；默认关闭，下载统一走服务端代理 |
| MD5 秒传 | 上传链路服务端流式计算内容 MD5（`fs_file.content_md5`），同租户内同 MD5 同大小的重复上传直接复用已有物理对象（新元数据行指向同一键）；删除时按引用计数保留共享对象 |
| 图片缩略图 | `GET /fs/file/{id}/thumbnail?width=`：JDK ImageIO 惰性生成（jpeg 白底 / png 保透明），产物按 `{storageKey}.th{width}` 派生键缓存（档位 128/256/512），永久强缓存；svg/webp 与 >20MB 源图返回 400 |
| 预签名分片直传（rustfs） | `POST /fs/file/multipart/init` 登记元数据并一次签发全部分片 PUT URL（8MB 起、≤1000 片自动放大）→ 浏览器并发直传对象存储 → `complete` 凭 ETag 组装 / `abort` 放弃清理；`fs_file.upload_id` 标记进行中，列表可见「直传中」 |
| 存储迁移工具 | `POST /fs/storage/migrate?from=local|rustfs`（权限点 fs:storage:migrate）：把历史对象按原键搬移到当前装配的存储实现并改写 `storage_type`，游标分页逐条容错，返回成功/失败/跳过统计 |
| RPC 契约 | 框架区 `mse-api/api-fs` 的 `RemoteFileService`（uploadFile/getFileMeta/downloadFile/deleteFile，JSON 进出 + base64 内容），消费方 `@RpcReference` 即用；单体 local 模式本地直调、微服务 dubbo 模式远程调用，消费方零改动 |
| 开放 API（mse-open 自注册） | `FsOpenApiContributor` 声明上传/查询/下载/删除四条描述（`open:fs:file-*`，复用 RPC 契约），应用就绪后自动推送 mse-open 目录——第三方应用经 `/open/api/fs/**` 调用，零 SQL 种子 |
| 公开素材通道 | 上传时声明 `accessLevel=public` → 可经免登录端点 `GET /fs/asset/{tenantId}/{fileId}.{后缀}` 读取（URL 规范拼法 `FsAssetUrls.publicAssetUrl`：首段租户号定位素材所属世界 0=平台世界，服务端按该段语句级路由寻址，租户段与行不符即 404；inline + 永久强缓存 + nosniff/CSP；登录页 Logo/背景图等场景专用，网关放行 `/fs/asset/**`；非 public 行与不存在同语义 404） |
| 业务统一接入 | oa 附件 / 基座品牌素材 / 用户头像（service-system，公开素材通道优先）/ ai 知识库归档均已按「mse-fs 优先、本地降级」标准接入（见第五节接入标准）：未引入/未部署 mse-fs 时自动回落各模块本地存储 |
| 可整体替换 | 存储实现与 RPC 实现均为 `@Bean + @ConditionalOnMissingBean(契约类型)`：声明同类型/同契约 Bean 即整体替换（如自研 OSS 实现），全程不改积木代码 |

**安全模型**：全部端点 `@SaCheckPermission`（租户端登录 + 权限点 fs:file:* / fs:storage:migrate）；下载/预签名/缩略图先经元数据校验（租户插件过滤）；预签名与分片直传 URL 按对象 key 限时签发，泄露面限于单文件且默认关闭。

## 二、工程结构

```
mse-fs/
├── pom.xml                  # 独立父 pom（parent=mse-starter-parent）
├── fs-service/              # 业务积木（纯 jar，SPI 自装配；契约在框架区 mse-api/api-fs，本模块为实现方）
│   ├── storage/             # FileStorage 抽象（storeAs/分片直传/presign）+ LocalFileStorage + RustFsFileStorage（AWS SDK v2）
│   ├── config/              # FsStorageProperties（mse.fs.storage.*）
│   ├── entity/ mapper/      # FsFile（BaseEntity 租户基类，含 content_md5/upload_id）+ Mapper（无 XML）
│   ├── service/ controller/ # FsFileService + FsFileController（/fs/file/**）+ FsPublicAssetController（/fs/asset/{tenantId}/{id}.{ext} 免登录）+ FsStorageController（/fs/storage/migrate）+ FsStorageMigrationService
│   ├── dto/ openapi/        # 分片直传请求/响应 DTO + FsOpenApiContributor（mse-open 目录自注册）
│   ├── rpc/                 # RemoteFileServiceImpl（@RpcService，装配类 @Bean 注册）
│   └── autoconfigure/       # FsService/FsStorage/FsController 三个自动装配 + imports 登记
├── fs-boot/                 # 启动器（vip.mseapp.boot.fs.FsBootApplication，micro-starter + fs-service + s3 SDK）
├── fs-ui/                   # Vue3 子应用（dev 3900，文件管理页）
├── docker/nacos/config/mse-fs.yaml   # 服务专属 Nacos 配置留档
├── docker/rustfs/compose.yaml        # RustFS 1.x 单机部署（可选，不部署即本地模式）
└── fs-service/src/main/resources/db/mysql/ # jar 内版本化迁移脚本：
                                            #   V20261001_02_fs__fs_init.sql（mse-target: master 业务库）+ V20261001_01_fs__fs_base.sql（mse-target: base 基座库）
```

依赖语义（"没引入 RustFS 就用本地文件系统"在 Maven 与配置两层同时成立）：

- **fs-service** 的 `software.amazon.awssdk:s3` 为 `<optional>true</optional>`——嵌入方（如单体）只引 fs-service 时 classpath 无 SDK，自动为纯本地模式；
- **fs-boot** 显式引入 SDK——标准微服务部署中 local/rustfs 两种模式可经 Nacos 配置随时切换，无需重打包；
- `type=rustfs` 但 classpath 缺 SDK 时启动即失败，报错信息给出明确修复指引。

## 三、快速开始（MySQL 单库演示）

```bash
# 0. 基座就绪：根目录 mvn install -DskipTests（BOM/发布件），docker compose 起 MySQL/Redis/Nacos
# 1. 初始化 SQL：免手工执行——业务表脚本（mse-target: master）随服务启动自动应用（mse.tenant.schema.auto-migrate=true，失败阻断启动）；
#    基座接入脚本（mse-target: base）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈），重复推送无害。

# 2. 构建本工程
mvn install -DskipTests

# 3a.（可选）部署 RustFS 并切换存储模式
docker compose -f docker/rustfs/compose.yaml up -d
# Nacos 控制台（localhost:18080）修改 Data ID mse-fs.yaml：mse.fs.storage.type=rustfs（或改本文件后重导）

# 3. 导入服务专属配置到 Nacos
CONFIG_DIR="$PWD/docker/nacos/config" sh ../../mse-framework/docker/nacos/init-configs.sh

# 4. 启动（微服务形态；网关路由 /fs/** → lb://mse-fs 已在 mse-gateway.yaml 留档）
java -jar fs-boot/target/fs-boot-1.0.0-SNAPSHOT.jar

# 5. 前端子应用
cd fs-ui && npm install && npm run dev    # http://localhost:3900（独立调试）
```

子应用生产接入：jar 内版本化脚本（`db/mysql/V20261001_01_fs__fs_base.sql`，mse-target: base）已幂等登记 `sys_micro_app`（id=8，`/sub-apps/fs/`）与页面菜单（470 分组 + 471 文件管理），随 starter-bridge 自动推送生效，`npm run build` 产物按 `--base=/sub-apps/fs/` 部署即可；租户端页面需在「套餐管理」勾选可见。单体聚合：`mse-sample/mse-monolith` 已引入 `fs-service`（本地存储模式）。

## 四、API 调用示例

```bash
# 上传（multipart，权限点 fs:file:upload）
curl -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  -F "file=@./report.pdf" -F "bizType=oa/attachment" -F "bizId=123" \
  http://localhost:8080/fs/file/upload
# → {"code":0,"data":{"id":1943...,"fileName":"report.pdf","storageType":"local",...}}

# 分页（fs:file:list）
curl -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/file/page?pageNum=1&pageSize=10&keyword=report"

# 代理下载（fs:file:download，流式透传）
curl -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/file/{id}/download" -o report.pdf

# 预签名直链（fs:file:download；仅 rustfs 模式且 presign.enabled=true）
curl -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/file/{id}/presign-url"

# 删除（fs:file:delete：逻辑删元数据 + 尽力删物理对象；秒传共享对象仍有引用时保留）
curl -X DELETE -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/file/{id}"

# 缩略图（fs:file:download；仅 jpeg/png/gif/bmp 且 ≤20MB，width 归一到 128/256/512 档）
curl -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/file/{id}/thumbnail?width=256" -o thumb.jpg

# 大文件分片直传（fs:file:upload；仅 rustfs 模式，对象存储需 CORS expose ETag）
# ① init：登记元数据并签发全部分片 PUT URL
curl -X POST -H "Authorization: <token>" -H "X-Tenant-Id: 1" -H "Content-Type: application/json" \
  -d '{"fileName":"big.zip","contentType":"application/zip","size":104857600}' \
  "http://localhost:8080/fs/file/multipart/init"
# → {"code":0,"data":{"fileId":"...","partSize":8388608,"parts":[{"partNumber":1,"url":"http://...:9000/..."},...]}}
# ② 浏览器/客户端逐片 PUT parts[].url（收集响应 ETag）
# ③ complete：组装对象（失败可用 abort 放弃并清理）
curl -X POST -H "Authorization: <token>" -H "X-Tenant-Id: 1" -H "Content-Type: application/json" \
  -d '{"parts":[{"partNumber":1,"etag":"\"d41d8...\""},{"partNumber":2,"etag":"\"02b1c...\""}]}' \
  "http://localhost:8080/fs/file/multipart/{fileId}/complete"

# 存储迁移（fs:storage:migrate；把 from 存储的历史对象搬移到当前存储实现，同步执行）
curl -X POST -H "Authorization: <token>" -H "X-Tenant-Id: 1" \
  "http://localhost:8080/fs/storage/migrate?from=local"
# → {"code":0,"data":{"from":"local","to":"rustfs","migrated":12,"failed":0,"skipped":0,"failedIds":[]}}
```

其他业务服务消费（RPC 契约，内容 base64，适合小文件；大文件走 HTTP 端点）：

```java
@RpcReference
private RemoteFileService fileService;

String result = fileService.uploadFile("""
    {"fileName":"a.txt","contentType":"text/plain","contentBase64":"SGVsbG8=","bizType":"demo","bizId":"1"}
    """);
```

## 五、业务文件存储接入标准（mse-fs 优先、无 mse-fs 自动降级本地）

业务模块需要文件存储时**不要自造落盘逻辑**，按以下模式接入（oa-form 附件 / service-platform 品牌素材 / service-system 头像 FsAvatarStorage/LocalAvatarStorage / ai-service 知识库归档均为现成样板）：

1. **依赖**：模块引框架契约 `api-fs`（BOM 仲裁版本，仅接口 + DTO，无实现依赖）；
2. **存储契约接口化**：模块内定义自己的存储接口（如 `OaFileStorage`/`KbStorageService`/`DomainAssetStorage`），本地磁盘实现兜底；
3. **条件装配**（自动降级语义）：自动装配类 `@AutoConfiguration(afterName = "vip.mseapp.fs.autoconfigure.FsServiceAutoConfiguration")` 内放两个嵌套配置，**两侧条件必须互斥**——
   - Fs 实现 Bean：`@Conditional(FsAvailableCondition.class)` + `@ConditionalOnMissingBean(存储接口)`，内部 `@RpcReference(dubbo = @RpcReference.Dubbo(timeout = 60000))` 注入 `RemoteFileService`（`FsAvailableCondition` 三处样板：classpath 有契约 且（dubbo 模式 或 local 模式容器已有契约实现）；模块显式开关可强制 `local`）；
   - Local 实现 Bean：`@Conditional(FsUnavailableCondition.class)`（上一条件取反）+ `@ConditionalOnMissingBean(存储接口)`；
   - **勿依赖嵌套类声明顺序兜底**：嵌套配置类解析顺序跟随 class 文件 InnerClasses 属性，javac 不保证与源码声明一致（实测四模块全部 Local 在前）；Local 若只写 `@ConditionalOnMissingBean`，先注册时 Fs 会整体让位、文件静默落本地磁盘（2026-09 头像/品牌素材/知识库/附件四处同源缺陷，已修）。
4. **元数据约定**：业务侧行留存自己的元数据并记录 `fsFileId`（或 `fs:{fileId}` 归档串）；历史本地行下载时按行分流，切换存储模式后旧文件不丢；
5. **RPC 边界**：base64 内容适合小文件（≤20MB），共享 `mse-micro-dubbo.yaml` 已将 dubbo payload 提至 32MB；更大文件走 mse-fs HTTP 端点。契约 DTO 的日期字段以 `@JsonFormat` 钉死载荷格式（`FileMetaResult.createTime` = `yyyy-MM-dd HH:mm:ss`），消费端无论容器 mapper 还是裸 mapper 解析均一致，勿在消费端自行适配日期格式。

显式开关（默认 `auto`）：`mse.oa.storage-mode` / `mse.tenant.branding-storage` / `mse.ai.kb.storage-mode` / `mse.system.avatar-storage`，取值 `auto|fs|local`。

## 六、配置项（Nacos Data ID: mse-fs.yaml，留档 `docker/nacos/config/mse-fs.yaml`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `mse.fs.storage.type` | `local` | 存储实现：`local` / `rustfs`（缺省即本地，文件留档带 `${MSE_FS_STORAGE_TYPE:local}` 环境变量覆盖） |
| `mse.fs.storage.local.base-dir` | `./fs-files` | 本地存储根目录（生产挂载持久卷） |
| `mse.fs.storage.rustfs.endpoint` | `http://localhost:9000` | RustFS S3 兼容端点 |
| `mse.fs.storage.rustfs.access-key / secret-key` | `rustfsadmin` | 对应 compose 的 `RUSTFS_ACCESS_KEY/SECRET_KEY`，生产必须修改 |
| `mse.fs.storage.rustfs.bucket` | `mse-fs` | 桶名（启动自动创建），桶内按 `{tenantId}/` 前缀隔离 |
| `mse.fs.storage.rustfs.region / path-style-access` | `us-east-1` / `true` | S3 兼容存储固定 path-style |
| `mse.fs.storage.presign.enabled / ttl-seconds` | `false` / `3600` | 预签名直链开关与有效期（仅 rustfs 模式生效）；分片直传的 PUT URL 有效期同源取 `ttl-seconds`（不看 enabled 开关，下限 300 秒） |
| `spring.servlet.multipart.max-file-size` | `100MB` | 上传大小上限 |
| `mse.service.fs.controller.enabled` | `true` | 默认控制器开关（false 时由接入方自有控制器替代） |

信创切换：达梦/金仓由共享 `mse-micro-db.yaml` 的 profile 提供，启动加 `--spring.profiles.active=dm|kingbase`；数据源/Redis/Sa-Token/Dubbo 等共享项见 `mse-micro-common/db/dubbo.yaml`。

## 七、全链路验证要点

1. **本地模式**：`type=local` 上传 → 工作目录 `fs-files/{tenantId}/{yyyyMM}/` 出现对象；下载内容一致；删除后文件与列表同步消失；
2. **RustFS 模式**：`docker compose -f docker/rustfs/compose.yaml up -d` → Nacos 改 `type=rustfs` 重启 → 上传/下载/删除重复验证；控制台（9001）可见 `mse-fs` 桶与 `{tenantId}/` 前缀；
3. **租户隔离**：租户 A 上传 → 登录租户 B 请求同一 fileId 下载/删除 → 应 404（元数据与物理键双层隔离）；B 的文件列表看不到 A 的文件；
4. **权限点**：无 `fs:file:upload` 权限的角色上传应 403；预签名关闭时调 `presign-url` 应 400 提示；无 `fs:storage:migrate` 权限调迁移应 403；
5. **RPC 契约**：单体模式在消费方 `@RpcReference` 调 `uploadFile/getFileMeta/downloadFile/deleteFile` 验证本地直调；
6. **公开素材**：`accessLevel=public` 的文件未登录 `GET /fs/asset/{tenantId}/{fileId}.{后缀}` 应 200（inline + immutable），非 public、跨租户段与不存在均 404；业务侧验证：oa 附件上传落 fs_file + oa_file.fs_file_id，品牌素材上传返回 `/fs/asset/{tenantId}/{fileId}.{后缀}` 且未登录可加载，知识库归档串为 `fs:{fileId}` 且原文可回下载；
7. **降级回归**：mse-monolith pom 临时去掉 fs-service 依赖重启 → oa 附件/品牌素材/知识库归档全部回落本地实现，功能不受影响（验证后恢复依赖）；
8. **秒传**：同租户上传同一文件两次 → 第二次应立即返回（服务端日志出现「秒传命中」）且桶/目录内不新增对象；删除其中一条元数据后另一条仍可下载；
9. **缩略图**：上传 png/jpg 后请求 `/thumbnail?width=128|256|512` → 首次生成、二次命中缓存（响应带 immutable 强缓存头）；svg/webp 应 400；
10. **分片直传**：fs-ui「大文件直传」上传 >8MB 文件 → 进度走完 → 列表出现该文件（存储列 RustFS、无「直传中」标记）且可下载；中途失败应触发 abort 清理；
11. **存储迁移**：local 模式上传若干文件 → 切 rustfs 重启 → 「迁移历史文件」→ 迁移后列表所有文件存储列均为 RustFS 且可下载，`storage_type` 已改写。

## 八、已知边界

- 上传为「物理对象先落、元数据后插」：元数据插入失败会遗留孤儿物理对象（本地可定时清理目录，对象存储可配生命周期规则）；删除为「逻辑删元数据、物理尽力删」：物理删除失败仅告警，不阻塞；
- `storage_type` 记录上传当时的实现：切换存储模式后历史文件仍按原实现寻址，用「迁移历史文件」（`POST /fs/storage/migrate`，见第七节 11）一键搬移；分片进行中的行（upload_id 非空）不参与迁移；
- 秒传以「同租户 + 同 MD5 + 同大小」判定并复用物理对象：删除共享对象中的某一条元数据不影响其余引用；MD5 理论碰撞风险以大小双条件 + 复用对象内容不可变兜底；
- 缩略图基于 JDK ImageIO：仅 jpeg/png/gif/bmp（gif 取首帧丢动画），svg/webp 及 >20MB 源图直接 400，前端对不支持类型显示纯文件名；
- RPC 契约经 base64 携带内容：共享 `mse-micro-dubbo.yaml` 已将 dubbo payload 提至 32MB，可承载 ≤20MB 文件（base64 膨胀约 1.33 倍）；更大文件走 HTTP 端点或分片直传；
- 分片直传为一次性签发全部分片 URL（有效期 `presign.ttl-seconds`），暂不支持断点续传——失败即 abort 清理重传；浏览器直传对象存储要求存储侧 CORS 允许来源并 `Access-Control-Expose-Headers: ETag`（compose 留档待运维按域开启）。

## 九、演进路线

- 分片断点续传（前端持久化已传分片 ETag，续传时仅补传缺失片）与分片直传进度秒级回写；
- 独立租户库：`FsFile` 加 `@TenantDatasource` 即随 `sys_tenant.ds_key` 路由（解析器由基座/bridge 提供）。
