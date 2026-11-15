package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.ai.api.support.AiBizQueryProperties;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.entity.DatQueryAllowlist;
import vip.mseapp.data.mapper.DatQueryAllowlistMapper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 中台库通道查询白名单：各租户维护允许经 datasource_id=0 查询的中台库业务表
 * （ods_ 贴源表天然放行，无需登记）。
 *
 * <p>表名统一小写归一化落库，与 {@link ApiSqlGuard} 的小写比对规则一致；
 * 行级租户隔离随 BaseEntity 自动生效。保存/更新经硬黑名单校验（mse.ai.biz-query.deny-*
 * 底线，与模块 biz 查询工具共用）——sys_/ai_/dat_ 等系统与凭证表即使手工登记也会被拒绝，
 * 防止白名单成为敏感数据的旁路。
 */
@Service
public class QueryAllowlistService {

    private final DatQueryAllowlistMapper allowlistMapper;
    private final AiBizQueryProperties bizQueryProperties;

    public QueryAllowlistService(DatQueryAllowlistMapper allowlistMapper, AiBizQueryProperties bizQueryProperties) {
        this.allowlistMapper = allowlistMapper;
        this.bizQueryProperties = bizQueryProperties;
    }

    public PageResult<DatQueryAllowlist> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatQueryAllowlist> wrapper = new LambdaQueryWrapper<DatQueryAllowlist>()
                .like(keyword != null && !keyword.isBlank(), DatQueryAllowlist::getTableName, keyword)
                .eq(status != null, DatQueryAllowlist::getStatus, status)
                .orderByDesc(DatQueryAllowlist::getId);
        Page<DatQueryAllowlist> page = allowlistMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(DatQueryAllowlist row) {
        row.setId(null);
        normalizeAndValidate(row);
        allowlistMapper.insert(row);
        return row.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(DatQueryAllowlist row) {
        getRaw(row.getId());
        normalizeAndValidate(row);
        allowlistMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, boolean enable) {
        DatQueryAllowlist row = getRaw(id);
        row.setStatus(enable ? DatQueryAllowlist.STATUS_ENABLED : DatQueryAllowlist.STATUS_DISABLED);
        allowlistMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getRaw(id);
        allowlistMapper.deleteById(id);
    }

    /** 启用白名单的小写集合（执行链路取用；空集 = 仅放行 ods_ 贴源表，即默认行为） */
    public Set<String> enabledTableNames() {
        Set<String> names = new LinkedHashSet<>();
        allowlistMapper.selectList(new LambdaQueryWrapper<DatQueryAllowlist>()
                        .eq(DatQueryAllowlist::getStatus, DatQueryAllowlist.STATUS_ENABLED))
                .forEach(row -> names.add(row.getTableName().toLowerCase(Locale.ROOT)));
        return names;
    }

    private DatQueryAllowlist getRaw(Long id) {
        DatQueryAllowlist row = allowlistMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(404, "白名单记录不存在");
        }
        return row;
    }

    private void normalizeAndValidate(DatQueryAllowlist row) {
        String table = row.getTableName() == null ? "" : row.getTableName().trim().replace("`", "");
        row.setTableName(table.toLowerCase(Locale.ROOT));
        if (table.isEmpty()) {
            throw new ServiceException(400, "表名不能为空");
        }
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ServiceException(400, "表名仅允许字母/数字/下划线且以字母或下划线开头：" + table);
        }
        if (bizQueryProperties.isDenied(table)) {
            throw new ServiceException(400, "系统/凭证等敏感表禁止加入查询白名单：" + table);
        }
        if (row.getStatus() == null) {
            row.setStatus(DatQueryAllowlist.STATUS_ENABLED);
        }
        Long exists = allowlistMapper.selectCount(new LambdaQueryWrapper<DatQueryAllowlist>()
                .eq(DatQueryAllowlist::getTableName, row.getTableName())
                .ne(row.getId() != null, DatQueryAllowlist::getId, row.getId() == null ? -1L : row.getId()));
        if (exists != null && exists > 0) {
            throw new ServiceException(400, "该表已在白名单中：" + row.getTableName());
        }
    }
}
