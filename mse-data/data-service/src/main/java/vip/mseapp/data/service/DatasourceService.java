package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.config.DataCrypt;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.support.DataConnectorManager;
import vip.mseapp.data.support.MasterDbGuard;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 外部数据源管理：CRUD + 连通性测试；口令加密落库、接口不回显，编辑留空表示不修改。
 * 仅允许登记租户自有/外部业务库，平台主库由 {@link MasterDbGuard} 防呆拒绝。
 */
@Service
public class DatasourceService {

    private final DatDatasourceMapper datasourceMapper;
    private final DataConnectorManager connectorManager;
    private final DataCrypt crypt;
    private final MasterDbGuard masterDbGuard;

    public DatasourceService(DatDatasourceMapper datasourceMapper,
                             DataConnectorManager connectorManager, DataCrypt crypt,
                             MasterDbGuard masterDbGuard) {
        this.datasourceMapper = datasourceMapper;
        this.connectorManager = connectorManager;
        this.crypt = crypt;
        this.masterDbGuard = masterDbGuard;
    }

    public PageResult<DatDatasource> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatDatasource> wrapper = new LambdaQueryWrapper<DatDatasource>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(DatDatasource::getName, keyword).or().like(DatDatasource::getHost, keyword))
                .eq(status != null, DatDatasource::getStatus, status)
                .orderByDesc(DatDatasource::getId);
        Page<DatDatasource> page = datasourceMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        page.getRecords().forEach(ds -> ds.setPassword(null));
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public DatDatasource get(Long id) {
        DatDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ServiceException(404, "数据源不存在");
        }
        ds.setPassword(null);
        return ds;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(DatDatasource ds) {
        validate(ds);
        ds.setPassword(crypt.encrypt(ds.getPassword()));
        datasourceMapper.insert(ds);
        return ds.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(DatDatasource ds) {
        DatDatasource existing = getRaw(ds.getId());
        validate(ds);
        if (ds.getPassword() == null || ds.getPassword().isBlank()) {
            // 留空 = 口令不变
            ds.setPassword(existing.getPassword());
        } else {
            ds.setPassword(crypt.encrypt(ds.getPassword()));
        }
        datasourceMapper.updateById(ds);
        connectorManager.evict(ds.getTenantId(), ds.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, boolean enable) {
        DatDatasource ds = getRaw(id);
        ds.setStatus(enable ? DatDatasource.STATUS_ENABLED : DatDatasource.STATUS_DISABLED);
        datasourceMapper.updateById(ds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DatDatasource ds = getRaw(id);
        datasourceMapper.deleteById(id);
        connectorManager.evict(ds.getTenantId(), id);
    }

    /** 连通性测试：ok=false 时 message 带驱动层错误详情 */
    public TestResult test(Long id) {
        DatDatasource ds = getRaw(id);
        String error = connectorManager.testConnection(ds);
        ds.setLastTestTime(LocalDateTime.now());
        ds.setLastTestOk(error == null ? 1 : 0);
        ds.setLastTestMsg(error);
        datasourceMapper.updateById(ds);
        return new TestResult(error == null, error);
    }

    private void validate(DatDatasource ds) {
        if (ds.getName() == null || ds.getName().isBlank()) {
            throw new ServiceException(400, "数据源名称不能为空");
        }
        Set<String> types = DatDatasource.SUPPORTED_TYPES;
        if (ds.getType() == null || !types.contains(ds.getType())) {
            throw new ServiceException(400, "数据源类型必须为：" + String.join("/", types));
        }
        if (ds.getHost() == null || ds.getHost().isBlank()
                || ds.getPort() == null || ds.getPort() <= 0
                || ds.getDbName() == null || ds.getDbName().isBlank()) {
            throw new ServiceException(400, "主机/端口/库名不能为空");
        }
        if (ds.getUsername() == null || ds.getUsername().isBlank()) {
            throw new ServiceException(400, "用户名不能为空");
        }
        masterDbGuard.assertNotMaster(ds);
        if (ds.getStatus() == null) {
            ds.setStatus(DatDatasource.STATUS_ENABLED);
        }
    }

    /** 原始记录（含密文，内部用） */
    public DatDatasource getRaw(Long id) {
        DatDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ServiceException(404, "数据源不存在");
        }
        return ds;
    }

    public record TestResult(boolean ok, String message) {
    }
}
