package vip.mseapp.search.scope;

import vip.mseapp.mybatis.datascope.DataScopeParam;
import vip.mseapp.mybatis.datascope.DataScopeParamResolver;

/**
 * 本地数据范围解析（单体聚合形态）：容器内存在 service-system 提供的
 * {@link DataScopeParamResolver} 时直接复用，与业务列表页 @DataPermission 语义零偏差。
 */
public class LocalDataScopeResolver implements SearchDataScopeResolver {

    private final DataScopeParamResolver delegate;

    public LocalDataScopeResolver(DataScopeParamResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public DataScopeParam resolve() {
        return delegate.resolve();
    }
}
