package vip.mseapp.ai.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体编排（Flow）配置（mse.ai.flow.*）。
 */
@Component
@ConfigurationProperties(prefix = "mse.ai.flow")
public class FlowProperties {

    /** 单次工作流执行全局超时毫秒 */
    private long timeoutMillis = 120_000L;

    /** 并行分支：多活跃路径并发执行（false 回退按声明顺序串行） */
    private boolean parallel = true;

    /** 数据查询节点默认工具编码（复用工具中心通道，微服务模式在工具中心登记 rpc 工具） */
    private String datasetToolCode = "data_query";

    /** 数据查询节点默认行数上限 */
    private int datasetRowLimit = 200;

    /** 迭代节点单次最大条目数（防爆炸） */
    private int iterationMaxItems = 50;

    /** HTTP 请求节点超时毫秒 */
    private long httpTimeoutMillis = 15_000L;

    /** HTTP 请求节点允许的域名白名单（命中则放行内网地址；默认空 = 全部禁内网） */
    private List<String> httpAllowedHosts = List.of();

    /** 代码节点单次执行超时秒 */
    private int codeTimeoutSeconds = 10;

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public boolean isParallel() {
        return parallel;
    }

    public void setParallel(boolean parallel) {
        this.parallel = parallel;
    }

    public String getDatasetToolCode() {
        return datasetToolCode;
    }

    public void setDatasetToolCode(String datasetToolCode) {
        this.datasetToolCode = datasetToolCode;
    }

    public int getDatasetRowLimit() {
        return datasetRowLimit;
    }

    public void setDatasetRowLimit(int datasetRowLimit) {
        this.datasetRowLimit = datasetRowLimit;
    }

    public int getIterationMaxItems() {
        return iterationMaxItems;
    }

    public void setIterationMaxItems(int iterationMaxItems) {
        this.iterationMaxItems = iterationMaxItems;
    }

    public long getHttpTimeoutMillis() {
        return httpTimeoutMillis;
    }

    public void setHttpTimeoutMillis(long httpTimeoutMillis) {
        this.httpTimeoutMillis = httpTimeoutMillis;
    }

    public List<String> getHttpAllowedHosts() {
        return httpAllowedHosts;
    }

    public void setHttpAllowedHosts(List<String> httpAllowedHosts) {
        this.httpAllowedHosts = httpAllowedHosts;
    }

    public int getCodeTimeoutSeconds() {
        return codeTimeoutSeconds;
    }

    public void setCodeTimeoutSeconds(int codeTimeoutSeconds) {
        this.codeTimeoutSeconds = codeTimeoutSeconds;
    }
}
