/**
 * mse-im 跨服务 RPC 契约包（预留）。
 *
 * <p>P0（PoC）阶段 mse-im 不对外提供 RPC 契约，本包仅占位保持模块结构；
 * 后续（webhook 消费/在线状态同步期）按基座契约规范在此登记
 * 「接口 + Serializable DTO」，实现侧 {@code @RpcService} 注册、消费侧 {@code @RpcReference} 注入。
 */
package vip.mseapp.im.api;
