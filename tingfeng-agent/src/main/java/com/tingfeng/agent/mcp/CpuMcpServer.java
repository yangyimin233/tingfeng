package com.tingfeng.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 最小化 Java MCP stdio Server，提供本地主机 CPU/内存/线程诊断工具。
 * <p>
 * 传输: stdin/stdout 逐行 JSON-RPC 2.0
 * 数据源: java.lang.management MXBeans (零外部依赖)
 */
public class CpuMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode request;
                try {
                    request = MAPPER.readTree(line);
                } catch (Exception e) {
                    System.err.println("[CpuMcp] invalid JSON: " + line);
                    continue;
                }
                String method = request.path("method").asText();
                Object requestId = request.has("id") && !request.get("id").isNull()
                        ? request.get("id").asText() : null;
                if (requestId == null) continue;
                JsonNode params = request.path("params");

                switch (method) {
                    case "initialize" -> sendInitializeResponse(requestId);
                    case "tools/list" -> sendToolList(requestId);
                    case "tools/call" -> sendToolCallResult(requestId, params);
                    default -> sendError(requestId, -32601, "Method not found: " + method);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ── MCP 握手 ──

    private static void sendInitializeResponse(Object requestId) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode protocolVersion = result.putObject("protocolVersion");
        protocolVersion.put("version", "2025-03-26");
        send(requestId, result);
    }

    // ── 工具列表 ──

    private static void sendToolList(Object requestId) {
        ArrayNode tools = MAPPER.createArrayNode();

        addTool(tools, "cpu_info",
                "获取本地 JVM 进程 CPU 负载和系统 CPU 指标。"
                + "包括系统整体 CPU 使用率、JVM 进程 CPU 使用率、可用处理器核心数。"
                + "用于诊断 CPU 瓶颈。",
                emptySchema());

        addTool(tools, "java_memory",
                "获取 JVM 堆内存使用详情。"
                + "包括堆内存已用/最大、非堆内存已用、系统空闲物理内存。"
                + "用于诊断内存泄漏或 OOM 风险。",
                emptySchema());

        addTool(tools, "java_threads",
                "获取 JVM 线程统计信息和死锁检测。"
                + "包括当前线程数、峰值线程数、守护线程数、死锁线程列表。"
                + "用于诊断线程泄漏或死锁。",
                emptySchema());

        addTool(tools, "system_info",
                "获取操作系统和 JVM 基本信息。"
                + "包括操作系统名称/版本、Java 版本、JVM 启动时长。",
                emptySchema());

        addTool(tools, "gc_stats",
                "获取 JVM 垃圾回收统计信息。"
                + "包括各 GC 收集器的名称、回收次数、总耗时、上次 GC 耗时。"
                + "GC 频繁或耗时长是导致应用卡顿的核心原因之一。",
                emptySchema());

        addTool(tools, "disk_usage",
                "获取磁盘空间使用情况，列出所有分区(盘符)。"
                + "包括总空间、已用、剩余空间及使用率。"
                + "用于排查'磁盘满导致写失败'类问题。",
                emptySchema());

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        send(requestId, result);
    }

    private static void addTool(ArrayNode tools, String name, String desc, ObjectNode schema) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", desc);
        tool.set("inputSchema", schema);
    }

    private static ObjectNode emptySchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        return schema;
    }

    // ── 工具调用分发 ──

    private static void sendToolCallResult(Object requestId, JsonNode params) {
        String toolName = params.path("name").asText();
        try {
            String text = switch (toolName) {
                case "cpu_info" -> getCpuInfo();
                case "java_memory" -> getJavaMemory();
                case "java_threads" -> getJavaThreads();
                case "system_info" -> getSystemInfo();
                case "gc_stats" -> getGcStats();
                case "disk_usage" -> getDiskUsage();
                default -> { sendError(requestId, -32602, "Unknown tool: " + toolName); yield null; }
            };
            if (text == null) return;

            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            send(requestId, result);
        } catch (Exception e) {
            sendError(requestId, -32000, "Tool execution error: " + e.getMessage());
        }
    }

    // ── 诊断方法 ──

    private static String getCpuInfo() {
        StringBuilder sb = new StringBuilder("=== CPU 诊断 ===\n\n");
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        double systemLoad = os.getCpuLoad() * 100;
        double processLoad = os.getProcessCpuLoad() * 100;
        int processors = os.getAvailableProcessors();

        sb.append(String.format("  系统 CPU 使用率: %.1f%%\n", systemLoad));
        sb.append(String.format("  JVM 进程 CPU 使用率: %.1f%%\n", processLoad));
        sb.append(String.format("  可用处理器核心数: %d\n", processors));

        String status;
        if (systemLoad > 90) status = "⚠️ 系统 CPU 接近饱和, 建议排查高 CPU 线程或扩容";
        else if (systemLoad > 70) status = "  偏高, 需关注趋势";
        else status = "✅ 正常";
        sb.append("\n  状态: ").append(status);
        return sb.toString();
    }

    private static String getJavaMemory() {
        StringBuilder sb = new StringBuilder("=== JVM 内存诊断 ===\n\n");
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        long heapUsedMB = heap.getUsed() / (1024 * 1024);
        long heapMaxMB = heap.getMax() / (1024 * 1024);
        long nonHeapUsedMB = nonHeap.getUsed() / (1024 * 1024);
        long totalMB = runtime.totalMemory() / (1024 * 1024);
        long freeMB = runtime.freeMemory() / (1024 * 1024);

        sb.append(String.format("  堆内存已用: %d MB / 最大 %d MB\n", heapUsedMB, heapMaxMB));
        sb.append(String.format("  堆内存已分配: %d MB, 空闲: %d MB\n", totalMB, freeMB));
        sb.append(String.format("  非堆内存已用: %d MB\n", nonHeapUsedMB));

        long total = runtime.totalMemory();
        long max = runtime.maxMemory();
        double usage = (double) total / max * 100;
        sb.append(String.format("  堆内存使用率: %.1f%%\n", usage));

        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long sysFreeMB = os.getFreeMemorySize() / (1024 * 1024);
        long sysTotalMB = os.getTotalMemorySize() / (1024 * 1024);
        sb.append(String.format("  系统空闲内存: %d MB / 总 %d MB\n", sysFreeMB, sysTotalMB));

        String status;
        if (usage > 85) status = "⚠️ 堆内存使用率过高, 可能存在内存泄漏";
        else if (sysFreeMB * 1.0 / sysTotalMB < 0.1) status = "⚠️ 系统内存不足";
        else status = "✅ 正常";
        sb.append("\n  状态: ").append(status);
        return sb.toString();
    }

    private static String getJavaThreads() {
        StringBuilder sb = new StringBuilder("=== JVM 线程诊断 ===\n\n");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        int threadCount = threadBean.getThreadCount();
        int peakCount = threadBean.getPeakThreadCount();
        int daemonCount = threadBean.getDaemonThreadCount();
        long totalStarted = threadBean.getTotalStartedThreadCount();

        sb.append(String.format("  当前线程数: %d\n", threadCount));
        sb.append(String.format("  峰值线程数: %d\n", peakCount));
        sb.append(String.format("  守护线程数: %d\n", daemonCount));
        sb.append(String.format("  累计启动线程: %d\n", totalStarted));
        sb.append(String.format("  死锁检测: "));

        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            sb.append("⚠️ 发现死锁线程!\n");
            ThreadInfo[] infos = threadBean.getThreadInfo(deadlockedThreads);
            for (ThreadInfo info : infos) {
                sb.append(String.format("    死锁线程: %s (id=%d)\n",
                        info.getThreadName(), info.getThreadId()));
                sb.append("    堆栈:\n");
                for (StackTraceElement ste : info.getStackTrace()) {
                    sb.append("      ").append(ste).append("\n");
                }
            }
        } else {
            sb.append("✅ 无死锁\n");
        }

        String status;
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            status = "⚠️ 存在死锁";
        } else if (threadCount >= 100 && threadCount > peakCount * 0.9) {
            status = "⚠️ 偏高 (接近历史峰值)";
        } else if (threadCount >= 50 && threadCount > peakCount * 0.85) {
            status = "  偏高, 需关注趋势";
        } else {
            status = "✅ 正常";
        }
        sb.append("\n  状态: ").append(status);
        return sb.toString();
    }

    private static String getSystemInfo() {
        StringBuilder sb = new StringBuilder("=== 系统信息 ===\n\n");
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        sb.append("  操作系统: ").append(System.getProperty("os.name"))
                .append(" ").append(System.getProperty("os.version")).append("\n");
        sb.append("  系统架构: ").append(System.getProperty("os.arch")).append("\n");
        sb.append("  Java 版本: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  Java 厂商: ").append(System.getProperty("java.vendor")).append("\n");

        long uptimeMs = runtimeBean.getUptime();
        long days = uptimeMs / (24 * 3600 * 1000);
        long hours = (uptimeMs % (24 * 3600 * 1000)) / (3600 * 1000);
        long minutes = (uptimeMs % (3600 * 1000)) / (60 * 1000);
        sb.append(String.format("  JVM 启动时长: %d 天 %d 小时 %d 分钟\n", days, hours, minutes));
        return sb.toString();
    }

    private static String getGcStats() {
        StringBuilder sb = new StringBuilder("=== JVM GC 统计 ===\n\n");
        java.util.List<GarbageCollectorMXBean> gcBeans =
                ManagementFactory.getGarbageCollectorMXBeans();

        long totalCollections = 0;
        long totalTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            sb.append("[").append(gc.getName()).append("]\n");
            sb.append("  回收次数: ").append(count).append("\n");
            sb.append("  累计耗时: ").append(time).append(" ms\n");
            if (count > 0) {
                sb.append(String.format("  平均耗时: %.1f ms\n", (double) time / count));
            }
            totalCollections += count;
            totalTime += time;
        }

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptimeMs > 0 && totalCollections > 0) {
            double gcFreq = (double) totalCollections / (uptimeMs / 1000.0);
            double gcPct = (double) totalTime / uptimeMs * 100;
            sb.append(String.format("\n[汇总]\n  GC 总次数: %d\n  GC 总耗时: %d ms\n  GC 频率: %.3f 次/秒\n  GC 时间占比: %.2f%%\n",
                    totalCollections, totalTime, gcFreq, gcPct));
            if (gcPct > 10) {
                sb.append("  ⚠️ GC 耗时占比过高, 可能存在内存泄漏或 GC 配置不当\n");
            } else if (gcPct > 5) {
                sb.append("    偏高, 需关注 GC 压力趋势\n");
            } else {
                sb.append("  ✅ 正常\n");
            }
        }
        return sb.toString();
    }

    private static String getDiskUsage() {
        StringBuilder sb = new StringBuilder("=== 磁盘空间诊断 ===\n\n");
        File[] roots = File.listRoots();
        sb.append(String.format("| 分区 | 文件系统 | 总空间 | 已用 | 剩余 | 使用率 |\n"));
        sb.append("|------|----------|--------|------|------|--------|\n");

        for (File root : roots) {
            try {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                long usable = root.getUsableSpace();
                long used = total - free;
                double usagePct = total > 0 ? (double) used / total * 100 : 0;

                sb.append(String.format("| %s | %s | %s | %s | %s | %.1f%% |\n",
                        root.getPath(),
                        "local",
                        formatBytes(total),
                        formatBytes(used),
                        formatBytes(usable),
                        usagePct));

                if (usagePct > 90) {
                    sb.append("  ⚠️ ").append(root.getPath())
                            .append(" 磁盘使用率超过 90%, 建议清理\n");
                }
            } catch (Exception e) {
                sb.append("| ").append(root.getPath()).append(" | 无法读取 | - | - | - | - |\n");
            }
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    // ── JSON-RPC 发送 ──

    private static void send(Object requestId, ObjectNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (requestId != null) response.put("id", String.valueOf(requestId));
        response.set("result", result);
        System.out.println(response.toString());
        System.out.flush();
    }

    private static void sendError(Object requestId, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (requestId != null) response.put("id", String.valueOf(requestId));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        System.out.println(response.toString());
        System.out.flush();
    }
}
