package vip.mseapp.search.autoconfigure;

import org.apache.ibatis.annotations.Mapper;import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.mybatis.datascope.DataScopeParamResolver;
import vip.mseapp.search.engine.EsSearchEngineClient;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.rpc.RemoteSearchSourceServiceImpl;
import vip.mseapp.search.api.RemoteSearchSourceService;
import vip.mseapp.search.scope.LocalDataScopeResolver;
import vip.mseapp.search.scope.RpcDataScopeResolver;
import vip.mseapp.search.scope.SearchDataScopeResolver;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * 搜索引擎业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 search-service 依赖：Service / 引擎适配 / 调度 / MQ 消费 / Mapper 均由此
 * 注册，无需 component-scan 业务包名，也无需 @MapperScan。控制器由
 * {@link SearchControllerAutoConfiguration} 装配，两者均经
 * {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 *
 * <p><b>可覆盖性</b>：契约实现与引擎端口采用 {@code @Bean + @ConditionalOnMissingBean} 显式注册——
 * 换搜索引擎（SearchEngineClient）、自定义个人数据范围解析（SearchDataScopeResolver）、
 * 替换源目录（RemoteSearchSourceService）均只需声明自己的同契约 Bean。
 *
 * <p><b>个人数据范围解析装配链</b>：容器存在 {@link DataScopeParamResolver}（单体聚合形态，
 * service-system 在场）直接复用（语义零偏差）；否则经 api-system 契约 RPC 解析（微服务形态，
 * 异常保守降级 SELF）。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.search.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.search.service", "vip.mseapp.search.engine", "vip.mseapp.search.mq",
        "vip.mseapp.search.scope", "vip.mseapp.search.dispatch", "vip.mseapp.search.config",
        "vip.mseapp.search.tenant"})
@TenantServiceModule("mse-search")
public class SearchServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RemoteSearchSourceService.class)
    public RemoteSearchSourceService remoteSearchSourceServiceImpl(
            vip.mseapp.search.tenant.SearchSourceFleetSeeder fleetSeeder) {
        return new RemoteSearchSourceServiceImpl(fleetSeeder);
    }

    /** 搜索引擎端口默认实现（Elasticsearch）；换引擎声明同接口 Bean 即可整体替换 */
    @Bean
    @ConditionalOnMissingBean(SearchEngineClient.class)
    public SearchEngineClient esSearchEngineClient(ElasticsearchClient elasticsearchClient) {
        return new EsSearchEngineClient(elasticsearchClient);
    }

    /** 个人数据范围解析：本地 DataScopeParamResolver 优先（单体），否则 RPC 契约（微服务） */
    @Bean
    @ConditionalOnMissingBean(SearchDataScopeResolver.class)
    public SearchDataScopeResolver searchDataScopeResolver(ObjectProvider<DataScopeParamResolver> localResolver) {
        DataScopeParamResolver delegate = localResolver.getIfAvailable();
        if (delegate != null) {
            return new LocalDataScopeResolver(delegate);
        }
        return new RpcDataScopeResolver();
    }
}
