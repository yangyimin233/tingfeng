package com.tingfeng.starter.collect;

import com.tingfeng.starter.config.TingFengProperties;
import com.tingfeng.starter.model.JvmMetricsSnapshot;
import com.tingfeng.starter.report.TingFengReportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时采集宿主 JVM 的 CPU/内存/线程/GC 指标，上报给 Agent。
 */
public class JvmMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(JvmMetricsCollector.class);

    private final TingFengReportClient reportClient;
    private final TingFengProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tingfeng-jvm-collector");
        t.setDaemon(true);
        return t;
    });

    public JvmMetricsCollector(TingFengReportClient reportClient, TingFengProperties properties) {
        this.reportClient = reportClient;
        this.properties = properties;
    }

    public void start() {
        int interval = Math.max(properties.getJvmCollectInterval(), 5);
        scheduler.scheduleAtFixedRate(this::collect, 1, interval, TimeUnit.SECONDS);
        log.info("JVM 指标采集已启动, 间隔 {} 秒", interval);
    }

    public void stop() {
        scheduler.shutdown();
    }

    private void collect() {
        try {
            JvmMetricsSnapshot snap = new JvmMetricsSnapshot();
            snap.setTimestamp(System.currentTimeMillis());

            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs =
                        (com.sun.management.OperatingSystemMXBean) os;
                double sysLoad = sunOs.getSystemCpuLoad();
                double procLoad = sunOs.getProcessCpuLoad();
                snap.setCpuSystem(sysLoad > 0 ? Math.round(sysLoad * 10000.0) / 100.0 : 0);
                snap.setCpuProcess(procLoad > 0 ? Math.round(procLoad * 10000.0) / 100.0 : 0);
                snap.setProcessors(sunOs.getAvailableProcessors());
                snap.setSysFreeMb(sunOs.getFreePhysicalMemorySize() / (1024 * 1024));
                snap.setSysTotalMb(sunOs.getTotalPhysicalMemorySize() / (1024 * 1024));
            }

            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long heapUsed = mem.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long heapMax = mem.getHeapMemoryUsage().getMax() / (1024 * 1024);
            snap.setHeapUsedMb(heapUsed);
            snap.setHeapMaxMb(heapMax);
            snap.setNonHeapUsedMb(mem.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));

            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            snap.setThreadCount(threads.getThreadCount());
            snap.setThreadPeak(threads.getPeakThreadCount());
            snap.setDaemonThreads(threads.getDaemonThreadCount());
            long[] deadlocked = threads.findDeadlockedThreads();
            snap.setDeadlocked(deadlocked != null ? deadlocked.length : 0);

            long youngCount = 0, youngTime = 0, oldCount = 0, oldTime = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = gc.getName().toLowerCase();
                if (name.contains("young") || name.contains("scavenge") || name.contains("copy")) {
                    youngCount = gc.getCollectionCount();
                    youngTime = gc.getCollectionTime();
                } else if (name.contains("old") || name.contains("mark") || name.contains("mixed")) {
                    oldCount = gc.getCollectionCount();
                    oldTime = gc.getCollectionTime();
                }
            }
            snap.setGcYoungCount(youngCount);
            snap.setGcYoungTimeMs(youngTime);
            snap.setGcOldCount(oldCount);
            snap.setGcOldTimeMs(oldTime);

            reportClient.reportJvmMetrics(snap);
        } catch (Exception e) {
            log.debug("JVM 指标采集失败: {}", e.getMessage());
        }
    }
}
