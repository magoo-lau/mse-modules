# data-ui —— 数据中台子应用

`mse-data` 数据中台的前端（复制 `mse-ui-sub` 模板改造）：嵌入 `mse-ui` 基座运行（登录态由基座经 wujie props 下发），也可独立启动开发调试。Vue3 + Vite + TS + Element Plus（全局 small 紧凑型）。

## 页面

| 路由 | 页面 | 权限点 |
|---|---|---|
| `/overview` | 数据概览（资产计数/今日调用量/最近记录） | `data:overview:list` |
| `/datasource` | 数据源管理（注册/测试连接/启停） | `data:source:*` |
| `/sync` | 数据集成（同步任务 CRUD/执行/运行日志） | `data:sync:*` |
| `/datamap` | 数据地图（元数据采集/检索/详情/采样） | `data:meta:*` |
| `/service` | 数据服务（API 定义/试运行/调用方应用/授权/审批/调用日志） | `data:service:*` |
| `/quality` | 数据质量（规则 CRUD/执行/报告） | `data:quality:*` |
| `/allowlist` | 查询白名单（中台库业务表直查登记/启停） | `data:allow:*` |

按钮级权限用 `v-permission` 指令（权限码来自基座下发）。

## 运行

```bash
npm install
npm run dev     # http://localhost:3700（/api 代理指向网关 8080；需直连 data-boot 调试时改为 8093）
npm run build   # 产物 base=/sub-apps/data/
```

菜单登记在基座 `mse-framework/mse-ui/src/micro/apps.ts` 的 `builtinMicroApps`（本地兜底）或平台端「子应用管理」（`sys_micro_app`，生产推荐），路由前缀 `/sub/data`。
