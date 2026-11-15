package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.engine.probe.ProbeResult;
import vip.mseapp.monitor.entity.MonRecord;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonRecordMapper;
import vip.mseapp.monitor.mapper.MonTargetMapper;
import vip.mseapp.monitor.support.AlertConfig;
import vip.mseapp.monitor.support.MonitorWorld;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 监控目标管理：CRUD + 启停 + 手动探测；查询返回统一脱敏密码（******，保存时识别保留原值）。
 */
@Service
public class TargetService {

    public static final String PASSWORD_MASK = "******";

    private static final Set<String> TARGET_TYPES = Set.of(
            MonTarget.TYPE_SERVICE, MonTarget.TYPE_HTTP, MonTarget.TYPE_JVM, MonTarget.TYPE_SLOWSQL,
            MonTarget.TYPE_MYSQL, MonTarget.TYPE_REDIS, MonTarget.TYPE_ROCKETMQ, MonTarget.TYPE_OS);

    private final MonTargetMapper targetMapper;
    private final MonRecordMapper recordMapper;
    private final CollectService collectService;
    private final JsonMapper jsonMapper;
    private final MonitorProperties properties;
    private final MonitorWorld world;

    public TargetService(MonTargetMapper targetMapper, MonRecordMapper recordMapper,
                         CollectService collectService, JsonMapper jsonMapper,
                         MonitorProperties properties, MonitorWorld world) {
        this.targetMapper = targetMapper;
        this.recordMapper = recordMapper;
        this.collectService = collectService;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.world = world;
    }

    public PageResult<MonTarget> page(long pageNum, long pageSize, String keyword,
                                      String targetType, Integer enabled) {
        Page<MonTarget> page = world.scope(() -> targetMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MonTarget>()
                        .like(keyword != null && !keyword.isBlank(), MonTarget::getName, keyword)
                        .eq(targetType != null && !targetType.isBlank(), MonTarget::getTargetType, targetType)
                        .eq(enabled != null, MonTarget::getEnabled, enabled)
                        .orderByAsc(MonTarget::getTargetType)
                        .orderByAsc(MonTarget::getName)));
        page.getRecords().forEach(this::maskPassword);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public MonTarget get(Long id) {
        MonTarget target = world.scope(() -> targetMapper.selectById(id));
        if (target == null) {
            throw new ServiceException(404, "监控目标不存在");
        }
        maskPassword(target);
        return target;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(MonTarget target) {
        validate(target);
        target.setId(null);
        target.setCollectInterval(normalizeInterval(target.getCollectInterval()));
        if (target.getEnabled() == null) {
            target.setEnabled(MonTarget.ENABLED);
        }
        target.setStatus(MonTarget.STATUS_UNKNOWN);
        target.setStatusTime(LocalDateTime.now().withNano(0));
        world.scope(() -> targetMapper.insert(target));
        return target.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(MonTarget input) {
        if (input.getId() == null) {
            throw new ServiceException(400, "编号不能为空");
        }
        validate(input);
        MonTarget existing = world.scope(() -> targetMapper.selectById(input.getId()));
        if (existing == null) {
            throw new ServiceException(404, "监控目标不存在");
        }
        existing.setName(input.getName());
        existing.setTargetType(input.getTargetType());
        existing.setServiceUrl(input.getServiceUrl());
        existing.setHost(input.getHost());
        existing.setPort(input.getPort());
        existing.setUsername(input.getUsername());
        existing.setPassword(PASSWORD_MASK.equals(input.getPassword())
                ? existing.getPassword() : input.getPassword());
        existing.setCollectInterval(normalizeInterval(input.getCollectInterval()));
        existing.setEnabled(input.getEnabled() == null ? existing.getEnabled() : input.getEnabled());
        existing.setAlertConfig(input.getAlertConfig());
        existing.setRemark(input.getRemark());
        world.scope(() -> targetMapper.updateById(existing));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        world.scope(() -> {
            targetMapper.deleteById(id);
            recordMapper.delete(new LambdaQueryWrapper<MonRecord>().eq(MonRecord::getTargetId, id));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, boolean enable) {
        MonTarget patch = new MonTarget();
        patch.setId(id);
        patch.setEnabled(enable ? MonTarget.ENABLED : MonTarget.DISABLED);
        world.scope(() -> targetMapper.updateById(patch));
    }

    /** 手动立即探测（同步，最长探测超时），返回结果；采样/告警走与调度一致的完整链路 */
    public ProbeResult probeNow(Long id) {
        MonTarget target = world.scope(() -> targetMapper.selectById(id));
        if (target == null) {
            throw new ServiceException(404, "监控目标不存在");
        }
        return collectService.collect(target);
    }

    /**
     * 确保本机 OS 目标存在（调度首轮调用，幂等）。
     * 在线：OS 目标属平台世界（平台库 tenant 0 行）——调度线程无租户上下文，裸跑会在行级插件
     * getRequiredTenantId 处抛错（目标永远建不出、每轮 WARN），须显式 runAsPlatform 落平台库，
     * 与 scanAllDataSources 对平台库的收集闭环；离线：runIgnore 落本地离线库
     * （本地世界的平台行，租户会话同世界可见）。
     */
    public void ensureOsTarget() {
        Runnable ensure = () -> {
            Long count = targetMapper.selectCount(
                    new LambdaQueryWrapper<MonTarget>().eq(MonTarget::getTargetType, MonTarget.TYPE_OS));
            if (count != null && count > 0) {
                return;
            }
            MonTarget target = new MonTarget();
            target.setName("本机操作系统");
            target.setTargetType(MonTarget.TYPE_OS);
            target.setHost("127.0.0.1");
            target.setCollectInterval(properties.getDefaultCollectInterval());
            target.setEnabled(MonTarget.ENABLED);
            target.setStatus(MonTarget.STATUS_UNKNOWN);
            target.setStatusTime(LocalDateTime.now().withNano(0));
            target.setRemark("monitor 所在主机（OSHI 自动创建）");
            targetMapper.insert(target);
        };
        if (world.offline()) {
            ensure.run();
        } else {
            TenantContext.runAsPlatform(ensure);
        }
    }

    public List<MonTarget> listEnabled() {
        return world.scope(() -> targetMapper.selectList(
                new LambdaQueryWrapper<MonTarget>().eq(MonTarget::getEnabled, MonTarget.ENABLED)
                        .orderByAsc(MonTarget::getTargetType)));
    }

    private void validate(MonTarget target) {
        if (target.getName() == null || target.getName().isBlank()) {
            throw new ServiceException(400, "目标名称不能为空");
        }
        if (target.getTargetType() == null || !TARGET_TYPES.contains(target.getTargetType())) {
            throw new ServiceException(400, "目标类型不合法");
        }
        if (MonTarget.TYPE_SERVICE.equals(target.getTargetType())
                || MonTarget.TYPE_HTTP.equals(target.getTargetType())
                || MonTarget.TYPE_JVM.equals(target.getTargetType())
                || MonTarget.TYPE_SLOWSQL.equals(target.getTargetType())) {
            if (target.getServiceUrl() == null || !target.getServiceUrl().trim().toLowerCase().startsWith("http")) {
                throw new ServiceException(400, "该类型必须填写 http/https 地址");
            }
        }
        if (MonTarget.TYPE_MYSQL.equals(target.getTargetType())
                || MonTarget.TYPE_REDIS.equals(target.getTargetType())
                || MonTarget.TYPE_ROCKETMQ.equals(target.getTargetType())) {
            if (target.getHost() == null || target.getHost().isBlank() || target.getPort() == null) {
                throw new ServiceException(400, "该类型必须填写主机与端口");
            }
        }
        if (target.getAlertConfig() != null && !target.getAlertConfig().isBlank()) {
            try {
                jsonMapper.readValue(target.getAlertConfig(), AlertConfig.class);
            } catch (Exception e) {
                throw new ServiceException(400, "告警规则 JSON 不合法");
            }
        }
    }

    private Integer normalizeInterval(Integer interval) {
        if (interval == null || interval < 5) {
            return properties.getDefaultCollectInterval();
        }
        return Math.min(interval, 86_400);
    }

    private void maskPassword(MonTarget target) {
        if (target.getPassword() != null && !target.getPassword().isBlank()) {
            target.setPassword(PASSWORD_MASK);
        }
    }
}
