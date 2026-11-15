package vip.mseapp.monitor.engine.probe;

import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.stereotype.Component;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.SimpleHttp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ 探测：DefaultMQAdminExt 连 namesrv 拉集群信息（namesrv 存活 + broker 数）。
 *
 * <p>RMQ 客户端调用在不可达 namesrv 上可能阻塞超过 HTTP 级超时，外层 FutureTask 硬超时兜底；
 * instanceName 按 targetId 隔离，避免多目标的客户端实例串扰。
 */
@Component
public class RocketMqProbe implements Probe {

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_ROCKETMQ.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String host = target.getHost();
        Integer port = target.getPort();
        if (host == null || host.isBlank() || port == null) {
            return ProbeResult.down("主机/端口未配置");
        }
        FutureTask<ProbeResult> task = new FutureTask<>(() -> doProbe(target));
        Thread thread = new Thread(task, "mse-monitor-rmq-" + target.getId());
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(timeoutMs + 3_000L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(true);
            return ProbeResult.down("探测超时（" + (timeoutMs + 3_000L) + "ms）");
        } catch (Exception e) {
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    private ProbeResult doProbe(MonTarget target) throws Exception {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(target.getHost() + ":" + target.getPort());
        admin.setInstanceName("mse-monitor-rmq-" + target.getId());
        long start = System.currentTimeMillis();
        try {
            admin.start();
            ClusterInfo cluster = admin.examineBrokerClusterInfo();
            int latency = (int) (System.currentTimeMillis() - start);
            int brokerCount = cluster.getBrokerAddrTable() == null ? 0 : cluster.getBrokerAddrTable().size();
            int clusterCount = cluster.getClusterAddrTable() == null ? 0 : cluster.getClusterAddrTable().size();
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("broker_count", brokerCount);
            metrics.put("cluster_count", clusterCount);
            return ProbeResult.up(latency, metrics);
        } finally {
            try {
                admin.shutdown();
            } catch (Exception ignored) {
                // 关闭失败不影响探测结论
            }
        }
    }
}
