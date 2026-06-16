package com.tingfeng.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;

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
        JsonNode args = params.path("arguments");
        long start = System.currentTimeMillis();
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

            logToolCall(toolName, args.toString(), text, (int)(System.currentTimeMillis() - start), true, null);

            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            send(requestId, result);
        } catch (Exception e) {
            logToolCall(toolName, args.toString(), null, (int)(System.currentTimeMillis() - start), false, e.getMessage());
            sendError(requestId, -32000, "Tool execution error: " + e.getMessage());
        }
    }

    // ── 诊断方法 ──

    // ── DB 查询辅助 ──

    private static Map<String, Object> queryLatest() {
        try (Connection conn = getPersistenceConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM tingfeng_jvm_metrics ORDER BY timestamp DESC LIMIT 1")) {
                if (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    return row;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String fmt(Object v) { return v != null ? v.toString() : "N/A"; }

    // ── 工具实现: DB 查询宿主 JVM 的历史指标 ──

    private static String getCpuInfo() {
        Map<String, Object> row = queryLatest();
        if (row == null) return "=== CPU 诊断 ===\n\n暂无宿主 JVM 数据，请确保 tingfeng-starter 已接入。";

        StringBuilder sb = new StringBuilder("=== CPU 诊断（宿主 JVM 最近一次采集）===\n\n");
        sb.append(String.format("  系统 CPU 使用率: %.1f%%\n", toDouble(row, "cpu_system")));
        sb.append(String.format("  JVM 进程 CPU 使用率: %.1f%%\n", toDouble(row, "cpu_process")));
        sb.append(String.format("  可用处理器核心数: %s\n", fmt(row.get("processors"))));

        double sys = toDouble(row, "cpu_system");
        String status;
        if (sys > 90) status = "⚠️ 系统 CPU 接近饱和";
        else if (sys > 70) status = "  偏高, 需关注趋势";
        else status = "✅ 正常";
        sb.append("\n  状态: ").append(status);
        sb.append("\n  采集时间: ").append(fmt(row.get("timestamp")));
        return sb.toString();
    }

    private static String getJavaMemory() {
        Map<String, Object> row = queryLatest();
        if (row == null) return "=== JVM 内存诊断 ===\n\n暂无宿主 JVM 数据。";

        StringBuilder sb = new StringBuilder("=== JVM 内存诊断（宿主 JVM 最近一次采集）===\n\n");
        sb.append(String.format("  堆内存已用: %s MB / 最大 %s MB\n",
                fmt(row.get("heap_used_mb")), fmt(row.get("heap_max_mb"))));
        sb.append(String.format("  非堆内存已用: %s MB\n", fmt(row.get("non_heap_used_mb"))));
        sb.append(String.format("  系统空闲内存: %s MB / 总 %s MB\n",
                fmt(row.get("sys_free_mb")), fmt(row.get("sys_total_mb"))));

        double usage = toDouble(row, "heap_used_mb") / Math.max(toDouble(row, "heap_max_mb"), 1) * 100;
        sb.append(String.format("  堆内存使用率: %.1f%%\n", usage));

        double sysFreeRatio = toDouble(row, "sys_free_mb") / Math.max(toDouble(row, "sys_total_mb"), 1);
        String status;
        if (usage > 85) status = "⚠️ 堆内存使用率过高";
        else if (sysFreeRatio < 0.1) status = "⚠️ 系统内存不足";
        else status = "✅ 正常";
        sb.append("\n  状态: ").append(status);
        sb.append("\n  采集时间: ").append(fmt(row.get("timestamp")));
        return sb.toString();
    }

    private static String getJavaThreads() {
        Map<String, Object> row = queryLatest();
        if (row == null) return "=== JVM 线程诊断 ===\n\n暂无宿主 JVM 数据。";

        StringBuilder sb = new StringBuilder("=== JVM 线程诊断（宿主 JVM 最近一次采集）===\n\n");
        sb.append(String.format("  当前线程数: %s\n", fmt(row.get("thread_count"))));
        sb.append(String.format("  峰值线程数: %s\n", fmt(row.get("thread_peak"))));
        sb.append(String.format("  守护线程数: %s\n", fmt(row.get("daemon_threads"))));

        int deadlocked = toInt(row, "deadlocked");
        sb.append(String.format("  死锁线程数: %d\n", deadlocked));
        if (deadlocked > 0) sb.append("  ⚠️ 存在死锁!\n");
        else sb.append("  ✅ 无死锁\n");

        sb.append("\n  采集时间: ").append(fmt(row.get("timestamp")));
        return sb.toString();
    }

    private static String getGcStats() {
        Map<String, Object> row = queryLatest();
        if (row == null) return "=== JVM GC 统计 ===\n\n暂无宿主 JVM 数据。";

        StringBuilder sb = new StringBuilder("=== JVM GC 统计（宿主 JVM 最近一次采集）===\n\n");
        long youngCount = toLong(row, "gc_young_count");
        long youngTime = toLong(row, "gc_young_time_ms");
        long oldCount = toLong(row, "gc_old_count");
        long oldTime = toLong(row, "gc_old_time_ms");

        sb.append(String.format("[Young GC]\n  回收次数: %d\n  累计耗时: %d ms\n", youngCount, youngTime));
        sb.append(String.format("[Old/Full GC]\n  回收次数: %d\n  累计耗时: %d ms\n", oldCount, oldTime));

        long totalTime = youngTime + oldTime;
        long totalCount = youngCount + oldCount;
        if (totalCount > 0) {
            double gcPct = totalTime > 0 ? 100.0 : 0;
            sb.append(String.format("\n[汇总]\n  GC 总次数: %d\n  GC 总耗时: %d ms\n",
                    totalCount, totalTime));
            if (gcPct > 10) sb.append("  ⚠️ GC 耗时占比过高\n");
            else sb.append("  ✅ 正常\n");
        }

        sb.append("\n  采集时间: ").append(fmt(row.get("timestamp")));
        return sb.toString();
    }

    private static String getSystemInfo() {
        StringBuilder sb = new StringBuilder("=== 系统信息 ===\n\n");
        sb.append("  操作系统: ").append(System.getProperty("os.name"))
                .append(" ").append(System.getProperty("os.version")).append("\n");
        sb.append("  系统架构: ").append(System.getProperty("os.arch")).append("\n");
        sb.append("  Java 版本: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  Java 厂商: ").append(System.getProperty("java.vendor")).append("\n");
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

    // ── 辅助 ──

    private static double toDouble(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static int toInt(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static long toLong(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0;
    }

    private static Connection getPersistenceConnection() throws Exception {
        String url = env("PERSISTENCE_URL", null);
        if (url == null || url.isBlank()) throw new IllegalStateException("PERSISTENCE_URL 未配置");
        String user = env("PERSISTENCE_USER", "root");
        String pass = env("PERSISTENCE_PASS", "123456");
        String sep = url.contains("?") ? "&" : "?";
        return DriverManager.getConnection(url + sep + "connectTimeout=3000&socketTimeout=3000", user, pass);
    }

    // ── 工具调用日志 ──

    private static void logToolCall(String toolName, String args, String result,
                                     int durationMs, boolean success, String errorMsg) {
        String url = env("PERSISTENCE_URL", null);
        if (url == null || url.isBlank()) return;
        try (Connection conn = DriverManager.getConnection(
                     url.contains("?") ? url + "&connectTimeout=1000&socketTimeout=1000"
                                       : url + "?connectTimeout=1000&socketTimeout=1000",
                     env("PERSISTENCE_USER", "root"), env("PERSISTENCE_PASS", "123456"));
             java.sql.PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO tingfeng_tool_call_log" +
                     " (tool_name, arguments, result_summary, duration_ms, success, error_msg, call_time)" +
                     " VALUES (?,?,?,?,?,?,?)")) {
            pstmt.setQueryTimeout(1);
            pstmt.setString(1, toolName);
            pstmt.setString(2, args != null && args.length() > 5000 ? args.substring(0, 5000) : args);
            pstmt.setString(3, result != null && result.length() > 500 ? result.substring(0, 500) : result);
            pstmt.setInt(4, durationMs);
            pstmt.setInt(5, success ? 1 : 0);
            pstmt.setString(6, errorMsg);
            pstmt.setLong(7, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (Exception ignored) {}
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

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
