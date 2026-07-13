package com.tingfeng.starter.model;

/**
 * JVM/CPU 指标快照 — 定时采集后上报给 Agent。
 */
public class JvmMetricsSnapshot {

    private double cpuSystem;       // 系统 CPU 使用率
    private double cpuProcess;      // JVM 进程 CPU 使用率
    private int processors;         // 可用核心数
    private long heapUsedMb;        // 堆内存已用 (MB)
    private long heapMaxMb;         // 堆内存最大 (MB)
    private long nonHeapUsedMb;     // 非堆内存已用 (MB)
    private long sysFreeMb;         // 系统空闲内存 (MB)
    private long sysTotalMb;        // 系统总内存 (MB)
    private int threadCount;        // 当前线程数
    private int threadPeak;         // 峰值线程数
    private int daemonThreads;      // 守护线程数
    private int deadlocked;         // 死锁线程数
    private long gcYoungCount;      // Young GC 次数
    private long gcYoungTimeMs;     // Young GC 总耗时 ms
    private long gcOldCount;        // Full GC 次数
    private long gcOldTimeMs;       // Full GC 总耗时 ms
    private String serverHost;      // 服务器标识
    private long timestamp;         // 采集时间戳

    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    public double getCpuSystem() { return cpuSystem; }
    public void setCpuSystem(double cpuSystem) { this.cpuSystem = cpuSystem; }

    public double getCpuProcess() { return cpuProcess; }
    public void setCpuProcess(double cpuProcess) { this.cpuProcess = cpuProcess; }

    public int getProcessors() { return processors; }
    public void setProcessors(int processors) { this.processors = processors; }

    public long getHeapUsedMb() { return heapUsedMb; }
    public void setHeapUsedMb(long heapUsedMb) { this.heapUsedMb = heapUsedMb; }

    public long getHeapMaxMb() { return heapMaxMb; }
    public void setHeapMaxMb(long heapMaxMb) { this.heapMaxMb = heapMaxMb; }

    public long getNonHeapUsedMb() { return nonHeapUsedMb; }
    public void setNonHeapUsedMb(long nonHeapUsedMb) { this.nonHeapUsedMb = nonHeapUsedMb; }

    public long getSysFreeMb() { return sysFreeMb; }
    public void setSysFreeMb(long sysFreeMb) { this.sysFreeMb = sysFreeMb; }

    public long getSysTotalMb() { return sysTotalMb; }
    public void setSysTotalMb(long sysTotalMb) { this.sysTotalMb = sysTotalMb; }

    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }

    public int getThreadPeak() { return threadPeak; }
    public void setThreadPeak(int threadPeak) { this.threadPeak = threadPeak; }

    public int getDaemonThreads() { return daemonThreads; }
    public void setDaemonThreads(int daemonThreads) { this.daemonThreads = daemonThreads; }

    public int getDeadlocked() { return deadlocked; }
    public void setDeadlocked(int deadlocked) { this.deadlocked = deadlocked; }

    public long getGcYoungCount() { return gcYoungCount; }
    public void setGcYoungCount(long gcYoungCount) { this.gcYoungCount = gcYoungCount; }

    public long getGcYoungTimeMs() { return gcYoungTimeMs; }
    public void setGcYoungTimeMs(long gcYoungTimeMs) { this.gcYoungTimeMs = gcYoungTimeMs; }

    public long getGcOldCount() { return gcOldCount; }
    public void setGcOldCount(long gcOldCount) { this.gcOldCount = gcOldCount; }

    public long getGcOldTimeMs() { return gcOldTimeMs; }
    public void setGcOldTimeMs(long gcOldTimeMs) { this.gcOldTimeMs = gcOldTimeMs; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
