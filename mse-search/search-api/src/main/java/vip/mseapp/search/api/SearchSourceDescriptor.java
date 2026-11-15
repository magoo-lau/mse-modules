package vip.mseapp.search.api;

import java.io.Serializable;
import java.util.List;

/**
 * 可搜索源描述（业务积木向搜索引擎自注册的目录条目）。
 *
 * <p>业务积木经 {@link SearchSourceContributor} 声明本对象清单，由 search-api 的推送器在应用
 * 就绪后推送至 mse-search 落入 search_source 目录（幂等 upsert：按 sourceCode 新增或更新）。
 * mse-search 按源配置定时扫描增量（经 {@link RemoteSearchDataProvider} 拉取）或消费 MQ 索引事件，
 * 业务工程零被依赖。
 *
 * <p>约束：sourceCode 全局唯一（建议 search:&lt;biz&gt;:&lt;resource&gt;）；jumpUrlTemplate 为基座
 * 路由模板（如 /sub/oa/task?id={bizId}，支持 {bizId} 与 {extra.字段名} 占位），搜索结果点击即跳转。
 */
public class SearchSourceDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源标识（全局唯一，索引名与游标锚点） */
    private String sourceCode;

    /** 源名称（管理端与搜索结果分组展示） */
    private String sourceName;

    /** 业务域显示名（如「OA域」「微信公众平台域」；空 = 不分域，仅参与「全部域」搜索；前端域选项由已注册源自动去重得出） */
    private String domain;

    /** 数据提供者分组（业务服务名 spring.application.name；dubbo 模式按此 group 泛化寻址 Provider） */
    private String providerGroup;

    /** 跳转模板：/sub/{app}/... 支持 {bizId}、{extra.字段} 占位 */
    private String jumpUrlTemplate;

    /** 结果可见权限码（空 = 登录即可见；搜索时按当前用户权限集过滤整源） */
    private String permissionCode;

    /** 是否启用个人数据范围过滤（null/true = 启用，按当前用户角色数据范围过滤归属；公告类无归属源可关） */
    private Boolean dataScopeEnabled;

    /** 增量扫描 cron（秒 分 时 日 月 周；空 = 用搜索引擎侧默认值） */
    private String scanCron;

    /** 增量水位列名（默认 update_time，Provider 按此列做下界过滤） */
    private String incrementalColumn;

    /** 每批拉取条数（空 = 用搜索引擎侧默认值） */
    private Integer batchSize;

    /** 分片数（按 bizId 散列并行拉取，空 = 1） */
    private Integer shardCount;

    /** 说明（落目录 remark） */
    private String description;

    /** 字段元数据编码默认值（本源文档 extra 会携带的字段及默认用法；空 = 无预声明，管理端界面可后续添加） */
    private List<SearchFieldDescriptor> fields;

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getProviderGroup() {
        return providerGroup;
    }

    public void setProviderGroup(String providerGroup) {
        this.providerGroup = providerGroup;
    }

    public String getJumpUrlTemplate() {
        return jumpUrlTemplate;
    }

    public void setJumpUrlTemplate(String jumpUrlTemplate) {
        this.jumpUrlTemplate = jumpUrlTemplate;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public Boolean getDataScopeEnabled() {
        return dataScopeEnabled;
    }

    public void setDataScopeEnabled(Boolean dataScopeEnabled) {
        this.dataScopeEnabled = dataScopeEnabled;
    }

    public String getScanCron() {
        return scanCron;
    }

    public void setScanCron(String scanCron) {
        this.scanCron = scanCron;
    }

    public String getIncrementalColumn() {
        return incrementalColumn;
    }

    public void setIncrementalColumn(String incrementalColumn) {
        this.incrementalColumn = incrementalColumn;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getShardCount() {
        return shardCount;
    }

    public void setShardCount(Integer shardCount) {
        this.shardCount = shardCount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<SearchFieldDescriptor> getFields() {
        return fields;
    }

    public void setFields(List<SearchFieldDescriptor> fields) {
        this.fields = fields;
    }
}
