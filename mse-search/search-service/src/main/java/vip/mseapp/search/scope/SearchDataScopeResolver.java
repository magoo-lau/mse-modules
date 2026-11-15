package vip.mseapp.search.scope;

import vip.mseapp.mybatis.datascope.DataScopeParam;

/**
 * 搜索数据范围解析端口（个人数据过滤，与底座 @DataPermission 五档语义对齐）。
 *
 * <p>返回 {@link DataScopeParam}：CUSTOM（部门并集，含本部门及以下展开）/ SELF（仅本人）；
 * <b>null 表示不过滤</b>——与 {@code DataScopeResolverImpl} 同语义（超管 / 任一角色为
 * 「全部」/ 无用户上下文的系统内部调用）。默认装配链见 SearchServiceAutoConfiguration：
 * 容器存在 {@code DataScopeParamResolver}（单体形态）直接复用，否则经 api-system 契约
 * {@code RemoteDataScopeService} RPC 解析（微服务形态）。
 */
public interface SearchDataScopeResolver {

    /**
     * 解析当前用户的数据范围。
     *
     * @return null = 不过滤（超管/全部/系统内部）
     */
    DataScopeParam resolve();
}
