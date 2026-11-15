# search-ui —— 搜索引擎子应用

`mse-search` 搜索引擎的前端：嵌入 `mse-ui` 基座运行（登录态由基座经 wujie props 下发），也可独立启动开发调试。技术栈与基座一致（Vue3 + Vite + TS + Element Plus，全局 small 紧凑型）。子应用**不自带侧边栏菜单**——应用地址在基座「子应用管理」登记、页面菜单在基座「菜单管理」以类型=子应用页面登记，由基座菜单树渲染；子应用只负责内容页。

## 页面（租户端 6 页，目录/索引按会话租户隔离）

| 路由 | 页面 | 端 | 说明 |
|---|---|---|---|
| `/source` | 源管理 | 租户端（菜单 481） | 可搜索源目录（业务自注册扇出播种 + 手工登记本租户目录）、启用/调度配置、字段配置（含从数据采样与「从业务目录导入」）、保存时提示一键建 FULL_REBUILD |
| `/task` | 任务中心 | 租户端（菜单 482） | 索引构建/增量任务清单与状态（FULL_REBUILD / 增量扫描 / MQ 实时事件落点），按归属租户创建与执行 |
| `/hot` | 热词统计 | 租户端（菜单 483） | 搜索关键词聚合统计（近 7 天/30 天），本租户热词 |
| `/playground` | 搜索测试 | 租户端（菜单 484） | 指定源/关键词试搜，回显命中与高亮、权限过滤调试信息 |
| `/index` | 索引管理 | 租户端（菜单 486） | 本租户各源 ES 索引（`{prefix}-{source}-{tenantId}`）清单/文档数/数据浏览/清空/删除（`SearchIndexController`，前缀 + 租户后缀白名单防护） |
| `/tenant-field` | 搜索配置 | 租户端（菜单 487） | 租户级字段覆盖（仅查询层，不能改类型/新增保留字段）与「从业务目录添加」租户自有可检索字段（保存实时对照目录校验） |

按钮级权限用 `v-permission` 指令（权限码 4201-4211，见 jar 内版本化脚本 `../search-service/src/main/resources/db/mysql/V20260922_01_search__search_base.sql`）。

## 独立运行

```bash
npm install && npm run dev   # http://localhost:4000（/api 代理指向网关 8080；需直连 search-boot 调试时改为 8096）
npm run build                # 产物 base=/sub-apps/search/
```

## 与基座的通信

- **props（基座 → 子应用，初始值）**：`window.$wujie.props.{ urlPrefix, token, tenantId, platform, nickname, permissions, theme }`（permissions 用于按钮级 v-permission，参考 mse-sample/mse-demo/demo-ui/src/directives/permission.ts）
- **bus 事件（双向）**：
  - `auth:change`：基座登录/登出后广播，子应用 `stores/auth.ts` 已订阅；
  - `sub:navigate`：基座菜单点击/直达 URL 时下发目标路径（`{ urlPrefix, path }`），子应用 `App.vue` 已订阅并 `router.push`；
  - `sub:ready`：子应用挂载完成后上报，基座据此补发一次当前目标路由（覆盖直达/刷新场景）；
  - `sub:title`：路由切换后上报 `{ urlPrefix, path, title }` 供基座页签/面包屑显示中文；
  - `sub:auth-expired`：子应用收到 401 后上报，基座统一登出；
  - `theme:change`：基座深浅色/主题色变更广播，子应用 `theme/bridge.ts` 跟随。
