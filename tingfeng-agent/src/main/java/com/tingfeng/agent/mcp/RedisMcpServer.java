package com.tingfeng.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Redis 诊断 MCP Server — 通过 stdio 暴露 Redis 运维诊断工具。
 * <p>
 * 工具: redis_metrics（指标）, redis_slow_log（慢查询）, redis_big_keys（大Key扫描）
 * <p>
 * 环境变量:
 *   REDIS_HOST     (默认 localhost)
 *   REDIS_PORT     (默认 6379)
 *   REDIS_PASSWORD (默认空)
 *   REDIS_DATABASE (默认 0)
 */
public class RedisMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile JedisPool pool;

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (pool != null && !pool.isClosed()) pool.close();
        }));
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode request;
                try { request = MAPPER.readTree(line); }
                catch (Exception e) { System.err.println("[RedisMcp] invalid JSON"); continue; }

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

        addTool(tools, "redis_metrics",
                "获取 Redis 关键运行指标。包括命中率(hit rate)、内存使用量(used_memory)、" +
                "内存碎片率(mem_fragmentation_ratio)、连接数(connected_clients)、" +
                "过期键(expired_keys)、因内存驱逐的键(evicted_keys)、每秒操作数(ops/sec)。" +
                "用于全面诊断 Redis 运行状态和性能瓶颈。",
                emptySchema());

        ObjectNode slowSchema = MAPPER.createObjectNode();
        slowSchema.put("type", "object");
        ObjectNode slowProps = slowSchema.putObject("properties");
        slowProps.putObject("count")
                .put("type", "integer")
                .put("description", "获取最近 N 条, 建议 5-20, 默认 10");

        addTool(tools, "redis_slow_log",
                "获取 Redis 最近 N 条慢查询日志。包括命令、耗时(微秒)、执行时间。" +
                "用于排查哪些命令执行时间过长导致 Redis 响应变慢。",
                slowSchema);

        ObjectNode bigKeySchema = MAPPER.createObjectNode();
        bigKeySchema.put("type", "object");
        ObjectNode bigKeyProps = bigKeySchema.putObject("properties");
        bigKeyProps.putObject("maxScan")
                .put("type", "integer")
                .put("description", "扫描 key 数量上限, 默认 500, 最大 1000");

        addTool(tools, "redis_big_keys",
                "扫描 Redis 中的大 Key，返回按大小排序的 TOP 20 列表。" +
                "字符串 key 单位为 bytes，集合类型为元素个数。" +
                "用于诊断 Redis 内存占用瓶颈，定位哪些 key 消耗了最多内存。",
                bigKeySchema);

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
        try {
            String text = switch (toolName) {
                case "redis_metrics" -> getMetrics();
                case "redis_slow_log" -> getSlowLog(args);
                case "redis_big_keys" -> getBigKeys(args);
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

    private static String getMetrics() {
        StringBuilder sb = new StringBuilder("=== Redis 运行指标报告 ===\n\n");
        try (Jedis jedis = getConnection()) {
            String info = jedis.info();
            java.util.Properties props = new java.util.Properties();
            for (String line : info.split("\n")) {
                if (line.startsWith("#") || !line.contains(":")) continue;
                int idx = line.indexOf(":");
                props.setProperty(line.substring(0, idx), line.substring(idx + 1));
            }

            long hits = parseLong(props, "keyspace_hits");
            long misses = parseLong(props, "keyspace_misses");
            long total = hits + misses;
            double hitRate = total > 0 ? (double) hits / total * 100 : 0;

            sb.append("[命中率]\n");
            sb.append(String.format("  keyspace_hits: %d\n", hits));
            sb.append(String.format("  keyspace_misses: %d\n", misses));
            sb.append(String.format("  命中率: %.2f%%\n", hitRate));
            sb.append(String.format("  每秒操作数: %s\n",
                    props.getProperty("instantaneous_ops_per_sec", "N/A")));

            sb.append("\n[键空间]\n");
            sb.append(String.format("  已过期键: %s\n", props.getProperty("expired_keys", "0")));
            sb.append(String.format("  因内存驱逐的键: %s\n", props.getProperty("evicted_keys", "0")));

            sb.append("\n[内存]\n");
            sb.append(String.format("  已用内存: %s\n", props.getProperty("used_memory_human", "N/A")));
            sb.append(String.format("  RSS 内存: %s\n", props.getProperty("used_memory_rss_human", "N/A")));
            sb.append(String.format("  内存上限: %s\n", props.getProperty("maxmemory_human", "N/A")));
            sb.append(String.format("  内存碎片率: %s\n",
                    props.getProperty("mem_fragmentation_ratio", "N/A")));

            sb.append("\n[连接]\n");
            sb.append(String.format("  connected_clients: %s\n",
                    props.getProperty("connected_clients", "N/A")));
            sb.append(String.format("  rejected_connections: %s\n",
                    props.getProperty("rejected_connections", "0")));

            String usedMemory = props.getProperty("used_memory", "0");
            String maxMemory = props.getProperty("maxmemory", "0");
            try {
                long used = Long.parseLong(usedMemory);
                long max = Long.parseLong(maxMemory);
                if (max > 0) {
                    double pct = (double) used / max * 100;
                    sb.append(String.format("\n[使用率] %.1f%% (%s / %s)%s\n",
                            pct, props.getProperty("used_memory_human"),
                            props.getProperty("maxmemory_human"),
                            pct > 80 ? " ⚠️ 内存使用率偏高" : ""));
                }
            } catch (NumberFormatException ignored) {}

        } catch (Exception e) {
            return "Redis Metrics 获取失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String getSlowLog(JsonNode args) {
        int count = args.has("count") ? Math.min(args.get("count").asInt(), 100) : 10;
        if (count <= 0) count = 10;

        try (Jedis jedis = getConnection()) {
            var entries = jedis.slowlogGet(count);
            if (entries == null || entries.isEmpty()) {
                return "Redis 慢查询日志为空，当前无慢查询记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Redis 慢查询日志 (共 ").append(entries.size()).append(" 条) ===\n\n");

            int idx = 1;
            for (var entry : entries) {
                long id = entry.getId();
                long timestamp = entry.getTimeStamp();
                long duration = entry.getExecutionTime();
                String timeStr = String.format("%tF %<tT", new Date(timestamp * 1000));

                List<String> cmdParts = new ArrayList<>();
                for (String arg : entry.getArgs()) {
                    cmdParts.add(arg);
                }

                sb.append("[").append(idx++).append("] ");
                sb.append("ID=").append(id);
                sb.append(" | 耗时=").append(duration).append("μs");
                sb.append(" | 时间=").append(timeStr).append("\n");
                sb.append("    命令: ").append(String.join(" ", cmdParts)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Redis SlowLog 获取失败: " + e.getMessage();
        }
    }

    private static String getBigKeys(JsonNode args) {
        int maxScan = args.has("maxScan") ? Math.min(args.get("maxScan").asInt(), 1000) : 500;
        if (maxScan <= 0) maxScan = 500;

        try (Jedis jedis = getConnection()) {
            List<KeyEntry> entries = new ArrayList<>();
            int scanned = 0;
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams scanParams = new ScanParams().count(200);

            while (scanned < maxScan) {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) {
                    if (scanned >= maxScan) break;
                    String type = jedis.type(key);
                    long size = getKeySize(jedis, key, type);
                    entries.add(new KeyEntry(key, type, size));
                    scanned++;
                }
                cursor = result.getCursor();
                if (cursor.equals(ScanParams.SCAN_POINTER_START)) break;
            }

            entries.sort((a, b) -> Long.compare(b.size, a.size));

            StringBuilder sb = new StringBuilder();
            sb.append("=== Redis 大 Key 扫描 ===\n\n");
            sb.append("共扫描 ").append(scanned).append(" 个 key\n\n");
            sb.append("| # | Key (截断) | 类型 | 大小 |\n");
            sb.append("|---|-----------|------|------|\n");

            int show = Math.min(20, entries.size());
            for (int i = 0; i < show; i++) {
                KeyEntry e = entries.get(i);
                String disp = e.key.length() > 38 ? e.key.substring(0, 35) + "..." : e.key;
                String sizeStr = "string".equals(e.type)
                        ? formatBytes(e.size) : e.size + " 个元素";
                sb.append(String.format("| %d | %s | %s | %s |\n",
                        i + 1, disp, e.type, sizeStr));
            }

            long largeCount = entries.stream()
                    .filter(e -> "string".equals(e.type) && e.size > 1024 * 1024).count();
            if (largeCount > 0) {
                sb.append("\n⚠️ 超过 1MB 的大 String Key: ").append(largeCount).append(" 个\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Redis BigKeys 扫描失败: " + e.getMessage();
        }
    }

    private static long getKeySize(Jedis jedis, String key, String type) {
        try {
            return switch (type) {
                case "string" -> jedis.strlen(key);
                case "hash" -> jedis.hlen(key);
                case "list" -> jedis.llen(key);
                case "set" -> jedis.scard(key);
                case "zset" -> jedis.zcard(key);
                default -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Redis 连接 ──

    private static Jedis getConnection() {
        if (pool == null) {
            synchronized (RedisMcpServer.class) {
                if (pool == null) {
                    JedisPoolConfig config = new JedisPoolConfig();
                    config.setMaxTotal(4);
                    config.setMaxIdle(2);
                    config.setMinIdle(1);
                    config.setJmxEnabled(false);
                    String host = env("REDIS_HOST", "localhost");
                    int port = Integer.parseInt(env("REDIS_PORT", "6379"));
                    String password = env("REDIS_PASSWORD", "");
                    int db = Integer.parseInt(env("REDIS_DATABASE", "0"));
                    int timeout = 5000;
                    if (password.isEmpty()) {
                        pool = new JedisPool(config, host, port, timeout);
                    } else {
                        pool = new JedisPool(config, host, port, timeout, password, db);
                    }
                }
            }
        }
        return pool.getResource();
    }

    private record KeyEntry(String key, String type, long size) {}

    private static long parseLong(java.util.Properties props, String key) {
        try { return Long.parseLong(props.getProperty(key, "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (NumberFormatException ignored) {} }
        return 0;
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

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
