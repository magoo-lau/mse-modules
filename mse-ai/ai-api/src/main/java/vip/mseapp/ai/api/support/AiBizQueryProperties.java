package vip.mseapp.ai.api.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模块 biz 查询工具（{@link AbstractBizQueryTool} 子类）受控执行配置：表访问控制 =
 * 模块声明前缀白名单放行 + 本配置硬黑名单兜底（系统/凭证/AI 自身/贴源等表永远拒绝，
 * 配置不可越过）。配置键 {@code mse.ai.biz-query.*}，各业务工程可按需覆盖。
 */
@ConfigurationProperties(prefix = "mse.ai.biz-query")
public class AiBizQueryProperties {

    /** 硬拒绝表前缀（安全底线，优先于模块前缀白名单判定，配置不可越过） */
    private List<String> denyPrefixes = new ArrayList<>(List.of(
            "sys_", "ai_", "dat_", "open_", "fs_", "search_", "im_", "mon_", "ods_", "undo_"));

    /** 硬拒绝精确表名（补充前缀覆盖不到的散表，小写归一化） */
    private List<String> denyTables = new ArrayList<>();

    /** 单次返回行数上限 */
    private int maxRows = 200;

    /** 执行超时毫秒 */
    private int timeoutMillis = 10000;

    public List<String> getDenyPrefixes() {
        return denyPrefixes;
    }

    public void setDenyPrefixes(List<String> denyPrefixes) {
        this.denyPrefixes = denyPrefixes;
    }

    public List<String> getDenyTables() {
        return denyTables;
    }

    public void setDenyTables(List<String> denyTables) {
        this.denyTables = denyTables;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 归一化取值：trim + 小写 + 去空（SQL 防护/元数据过滤统一按小写比对） */
    public Set<String> denyPrefixSet() {
        return normalize(denyPrefixes);
    }

    public Set<String> denyTableSet() {
        return normalize(denyTables);
    }

    /** 表名（小写）是否命中硬黑名单（安全底线，优先于模块前缀白名单判定） */
    public boolean isDenied(String lowerTableName) {
        return denyTableSet().contains(lowerTableName)
                || denyPrefixSet().stream().anyMatch(lowerTableName::startsWith);
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
