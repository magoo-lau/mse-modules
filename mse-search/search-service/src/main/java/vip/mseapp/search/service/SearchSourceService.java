package vip.mseapp.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.api.SearchSourceDescriptor;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.mapper.SearchSourceMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可搜索源管理：业务自注册（幂等 upsert，描述字段随推送更新）+ 管理端调参/启停 + 字段配置。
 *
 * <p>upsert 语义：sourceCode 新增时补默认扫描参数与字段配置；已存在的源仅更新描述字段
 * （sourceName/domain/providerGroup/jumpUrlTemplate/permissionCode/dataScopeEnabled），
 * 扫描参数（scanCron/batchSize/shardCount/incrementalColumn）、可见角色（visibleRoles）与启停状态为管理端资产不覆盖；
 * 字段配置仅在管理端未定制（fields_customized=0）时随编码默认刷新。
 */
@Service
public class SearchSourceService {

    private final SearchSourceMapper sourceMapper;
    private final SearchProperties properties;
    private final SearchFieldConfigService fieldConfigService;
    private final SearchFieldCatalogService fieldCatalogService;

    public SearchSourceService(SearchSourceMapper sourceMapper, SearchProperties properties,
                               SearchFieldConfigService fieldConfigService,
                               SearchFieldCatalogService fieldCatalogService) {
        this.sourceMapper = sourceMapper;
        this.properties = properties;
        this.fieldConfigService = fieldConfigService;
        this.fieldCatalogService = fieldCatalogService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerDescriptors(List<SearchSourceDescriptor> descriptors) {
        if (descriptors == null) {
            return;
        }
        for (SearchSourceDescriptor descriptor : descriptors) {
            try {
                upsertDescriptor(descriptor);
            } catch (Exception e) {
                throw new ServiceException(500, "注册可搜索源 " + descriptor.getSourceCode() + " 失败: " + e.getMessage());
            }
        }
    }

    private void upsertDescriptor(SearchSourceDescriptor descriptor) {
        validate(descriptor);
        SearchSource existing = selectByCode(descriptor.getSourceCode());
        if (existing == null) {
            SearchSource source = new SearchSource();
            source.setSourceCode(descriptor.getSourceCode());
            source.setSourceName(descriptor.getSourceName());
            source.setDomain(blankToNull(descriptor.getDomain()));
            source.setProviderGroup(descriptor.getProviderGroup());
            source.setJumpUrlTemplate(descriptor.getJumpUrlTemplate());
            source.setPermissionCode(blankToNull(descriptor.getPermissionCode()));
            source.setDataScopeEnabled(descriptor.getDataScopeEnabled() == null || descriptor.getDataScopeEnabled() ? 1 : 0);
            source.setScanCron(orDefault(descriptor.getScanCron(), properties.getScan().getDefaultCron()));
            source.setIncrementalColumn(orDefault(descriptor.getIncrementalColumn(), "update_time"));
            source.setBatchSize(descriptor.getBatchSize() == null ? properties.getScan().getDefaultBatchSize() : descriptor.getBatchSize());
            source.setShardCount(descriptor.getShardCount() == null ? properties.getScan().getDefaultShardCount() : descriptor.getShardCount());
            source.setStatus(SearchSource.STATUS_ENABLED);
            source.setFieldsJson(descriptorFieldsJson(descriptor));
            source.setFieldsCustomized(0);
            source.setRemark(descriptor.getDescription());
            sourceMapper.insert(source);
            return;
        }
        existing.setSourceName(descriptor.getSourceName());
        existing.setDomain(blankToNull(descriptor.getDomain()));
        existing.setProviderGroup(descriptor.getProviderGroup());
        existing.setJumpUrlTemplate(descriptor.getJumpUrlTemplate());
        existing.setPermissionCode(blankToNull(descriptor.getPermissionCode()));
        if (descriptor.getDataScopeEnabled() != null) {
            existing.setDataScopeEnabled(descriptor.getDataScopeEnabled() ? 1 : 0);
        }
        // 字段配置：管理端未定制时跟随编码默认刷新（业务加字段 → 平台目录自动补上）
        if (existing.getFieldsCustomized() == null || existing.getFieldsCustomized() == 0) {
            existing.setFieldsJson(descriptorFieldsJson(descriptor));
        }
        if (existing.getScanCron() == null) {
            existing.setScanCron(orDefault(descriptor.getScanCron(), properties.getScan().getDefaultCron()));
        }
        if (existing.getBatchSize() == null) {
            existing.setBatchSize(descriptor.getBatchSize() == null
                    ? properties.getScan().getDefaultBatchSize() : descriptor.getBatchSize());
        }
        if (existing.getShardCount() == null) {
            existing.setShardCount(descriptor.getShardCount() == null
                    ? properties.getScan().getDefaultShardCount() : descriptor.getShardCount());
        }
        if (existing.getIncrementalColumn() == null) {
            existing.setIncrementalColumn(orDefault(descriptor.getIncrementalColumn(), "update_time"));
        }
        sourceMapper.updateById(existing);
    }

    private void validate(SearchSourceDescriptor descriptor) {
        if (descriptor.getSourceCode() == null || descriptor.getSourceCode().isBlank()) {
            throw new ServiceException(400, "sourceCode 不能为空");
        }
        if (descriptor.getProviderGroup() == null || descriptor.getProviderGroup().isBlank()) {
            throw new ServiceException(400, "源 " + descriptor.getSourceCode() + " 缺少 providerGroup");
        }
        if (descriptor.getJumpUrlTemplate() == null || !descriptor.getJumpUrlTemplate().startsWith("/sub/")) {
            throw new ServiceException(400, "源 " + descriptor.getSourceCode() + " 的 jumpUrlTemplate 须为 /sub/ 开头的基座路由模板");
        }
    }

    public SearchSource selectByCode(String sourceCode) {
        return sourceMapper.selectOne(new LambdaQueryWrapper<SearchSource>()
                .eq(SearchSource::getSourceCode, sourceCode));
    }

    public SearchSource getById(Long id) {
        return sourceMapper.selectById(id);
    }

    public Page<SearchSource> page(long pageNum, long pageSize, String keyword) {
        return sourceMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SearchSource>()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SearchSource::getSourceCode, keyword)
                                .or().like(SearchSource::getSourceName, keyword))
                        .orderByAsc(SearchSource::getSourceCode));
    }

    public List<SearchSource> listEnabled() {
        return sourceMapper.selectList(new LambdaQueryWrapper<SearchSource>()
                .eq(SearchSource::getStatus, SearchSource.STATUS_ENABLED));
    }

    /** 全量源（含停用）：调试页源下拉等场景 */
    public List<SearchSource> listAll() {
        return sourceMapper.selectList(new LambdaQueryWrapper<SearchSource>()
                .orderByAsc(SearchSource::getSourceCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, int status) {
        SearchSource source = sourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        source.setStatus(status);
        sourceMapper.updateById(source);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateScanConfig(Long id, String scanCron, Integer batchSize, Integer shardCount,
                                 Integer dataScopeEnabled, String permissionCode, List<String> visibleRoles,
                                 String domain) {
        SearchSource source = sourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        if (scanCron != null) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(scanCron);
            } catch (IllegalArgumentException e) {
                throw new ServiceException(400, "cron 表达式非法: " + e.getMessage());
            }
            source.setScanCron(scanCron);
        }
        if (batchSize != null) {
            source.setBatchSize(Math.max(1, batchSize));
        }
        if (shardCount != null) {
            source.setShardCount(Math.max(1, shardCount));
        }
        if (dataScopeEnabled != null) {
            source.setDataScopeEnabled(dataScopeEnabled);
        }
        if (permissionCode != null) {
            source.setPermissionCode(blankToNull(permissionCode));
        }
        // null = 请求未携带（不动）；空列表 = 清空限制
        if (visibleRoles != null) {
            source.setVisibleRoles(joinRoles(visibleRoles));
        }
        if (domain != null) {
            source.setDomain(blankToNull(domain));
        }
        sourceMapper.updateById(source);
    }

    /**
     * 管理端保存字段配置：校验 + 落库并标记已定制（此后编码默认推送不再覆盖）。
     * mapping 受影响的存量文档需全量重建，重建提示由控制器层返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public SearchSource saveFields(Long id, List<SearchFieldDescriptor> fields) {
        SearchSource source = sourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        source.setFieldsJson(fieldConfigService.serializePlatform(fields));
        source.setFieldsCustomized(1);
        sourceMapper.updateById(source);
        return source;
    }

    /** 平台字段目录视图：按源拉取业务目录并标注平台收录状态（目录不可用时降级返回原因） */
    public SearchFieldCatalogService.CatalogView platformCatalog(Long id) {
        SearchSource source = sourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        Set<String> platformFields = fieldConfigService.parsePlatform(source.getFieldsJson()).stream()
                .map(SearchFieldDescriptor::getField).collect(Collectors.toSet());
        return fieldCatalogService.platformView(source, platformFields);
    }

    /** 描述符字段清单 → 平台配置 JSON（未声明 = null） */
    private String descriptorFieldsJson(SearchSourceDescriptor descriptor) {
        List<SearchFieldDescriptor> fields = descriptor.getFields();
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return fieldConfigService.serializePlatform(fields);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 可见角色编码解析：逗号分隔 → 去空白去重列表（null/空 = 不限制）；查询侧可见性过滤共用 */
    public static List<String> parseRoles(String visibleRoles) {
        if (visibleRoles == null || visibleRoles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(visibleRoles.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    /** 角色编码列表 → 逗号分隔存储（去空白去重；空列表归 null = 不限制） */
    private static String joinRoles(List<String> roles) {
        List<String> cleaned = roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }
}
