package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.SimpleHttp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作系统探测（monitor 所在主机，OSHI 本机采集）：CPU/内存/各挂载点磁盘/网卡速率/uptime。
 *
 * <p>CPU 占用按两次 ticks 差值计算（相邻采样间隔过近时补 300ms 观察窗）；
 * 网络速率按网卡累计字节数差值计算，首次采样无数值（null，前端跳过该点）。
 * 采样状态为进程内缓存，仅本类 synchronized 访问（OS 目标只有一条）。
 */
@Component
public class OsProbe implements Probe {

    private SystemInfo systemInfo;

    private long[] prevCpuTicks;
    private long prevCpuTime;

    /** 网卡名 → [累计收发字节, 上次采样 epoch ms] */
    private final Map<String, long[]> netCache = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_OS.equals(targetType);
    }

    @Override
    public synchronized ProbeResult probe(MonTarget target, int timeoutMs) {
        try {
            return doProbe();
        } catch (Exception e) {
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    private ProbeResult doProbe() throws InterruptedException {
        if (systemInfo == null) {
            systemInfo = new SystemInfo();
        }
        HardwareAbstractionLayer hal = systemInfo.getHardware();
        OperatingSystem os = systemInfo.getOperatingSystem();
        Map<String, Object> metrics = new LinkedHashMap<>();

        // CPU：两次 ticks 差值（百分比）
        CentralProcessor cpu = hal.getProcessor();
        long[] ticks = cpu.getSystemCpuLoadTicks();
        double cpuPct;
        if (prevCpuTicks == null || System.currentTimeMillis() - prevCpuTime < 1_000) {
            Thread.sleep(300);
            ticks = cpu.getSystemCpuLoadTicks();
            cpuPct = cpu.getSystemCpuLoadBetweenTicks(ticks) * 100;
            prevCpuTicks = cpu.getSystemCpuLoadTicks();
        } else {
            cpuPct = cpu.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100;
            prevCpuTicks = ticks;
        }
        prevCpuTime = System.currentTimeMillis();
        metrics.put("cpu_usage_pct", round1(cpuPct));

        // 内存
        GlobalMemory memory = hal.getMemory();
        long totalMem = memory.getTotal();
        long usedMem = totalMem - memory.getAvailable();
        metrics.put("mem_total_gb", round1(totalMem / 1073741824.0));
        metrics.put("mem_used_gb", round1(usedMem / 1073741824.0));
        metrics.put("mem_usage_pct", round1(usedMem * 100.0 / totalMem));

        // 磁盘：全部挂载点，取最大使用率 + 明细串
        double maxDiskPct = 0;
        StringJoiner detail = new StringJoiner("，");
        List<OSFileStore> fileStores = os.getFileSystem().getFileStores();
        for (OSFileStore store : fileStores) {
            long total = store.getTotalSpace();
            if (total <= 0) {
                continue;
            }
            String name = store.getMount() == null || store.getMount().isBlank()
                    ? store.getName() : store.getMount();
            double pct = (total - store.getUsableSpace()) * 100.0 / total;
            maxDiskPct = Math.max(maxDiskPct, pct);
            detail.add(name + " " + round1(pct) + "%");
        }
        metrics.put("disk_usage_pct", round1(maxDiskPct));
        metrics.put("disk_detail", detail.toString());

        // 网络：收/发累计字节差值 → KB/s（首次采样无数值，前端跳过该点）
        long now = System.currentTimeMillis();
        double rxKbps = 0;
        double txKbps = 0;
        boolean netSampled = false;
        for (NetworkIF netIf : hal.getNetworkIFs()) {
            String name = netIf.getName();
            if (name == null || name.startsWith("lo")) {
                continue;
            }
            netIf.updateAttributes();
            long rx = netIf.getBytesRecv();
            long tx = netIf.getBytesSent();
            long[] prev = netCache.put(name, new long[]{rx, tx, now});
            if (prev != null && now > prev[2] + 500 && rx >= prev[0] && tx >= prev[1]) {
                double seconds = (now - prev[2]) / 1000.0;
                rxKbps += (rx - prev[0]) / 1024.0 / seconds;
                txKbps += (tx - prev[1]) / 1024.0 / seconds;
                netSampled = true;
            }
        }
        if (netSampled) {
            metrics.put("net_rx_kbps", round1(rxKbps));
            metrics.put("net_tx_kbps", round1(txKbps));
        }

        // uptime（小时）
        metrics.put("uptime_hours", round1(os.getSystemUptime() / 3600.0));
        return ProbeResult.up(0, metrics);
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
