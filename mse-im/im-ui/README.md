# im-ui —— 即时通讯子应用（mse-im）

租户端自助配置子应用（wujie 微前端，模板 mse-ui-sub）：「IM 设置」（实例台账自助录入/连通性测试）与「机器人」（bot 账号绑定 mse-ai 智能体）。后端端点见 `../im-service`，设计见 `../../../docs/tech/openim-integration.md`。

- dev 端口 4030（`npm run dev`），基座注册见 im-service 的 base 通道脚本（micro_app id=12）
- 构建：`npm run build`（产物 base `/sub-apps/im/`）
- 登录态/主题随基座下发（props + bus），模板协议内置
