package com.tingfeng.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

public class RedisDiagnosticTools {

    private final StringRedisTemplate redis;

    public RedisDiagnosticTools(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Tool("获取 Redis 实例关键运行指标，包括命中率(hit rate)、内存使用量(memory)、连接数(connections)、"
          + "过期/驱逐键数量等。用于诊断 Redis 运行状态和性能瓶颈。")
    public String getRedisMetrics() {
        return redis.execute((RedisCallback<String>) connection -> {
            Properties stats = connection.serverCommands().info("stats");
            Properties memory = connection.serverCommands().info("memory");

            StringBuilder sb = new StringBuilder();
            sb.append("=== Redis 运行指标报告 ===\n\n");

            long hits = parseLong(stats, "keyspace_hits");
            long misses = parseLong(stats, "keyspace_misses");
            long total = hits + misses;
            double hitRate = total > 0 ? (double) hits / total * 100 : 0;

            sb.append("[命中率]\n");
            sb.append(String.format("  keyspace_hits: %d\n", hits));
            sb.append(String.format("  keyspace_misses: %d\n", misses));
            sb.append(String.format("  命中率: %.2f%%\n", hitRate));
            sb.append(String.format("  每秒操作数: %s\n", stats.getProperty("instantaneous_ops_per_sec", "N/A")));

            sb.append("\n[键空间]\n");
            sb.append(String.format("  已过期键: %s\n", stats.getProperty("expired_keys", "0")));
            sb.append(String.format("  因内存驱逐的键: %s\n", stats.getProperty("evicted_keys", "0")));

            sb.append("\n[内存]\n");
            sb.append(String.format("  已用内存: %s\n", memory.getProperty("used_memory_human", "N/A")));
            sb.append(String.format("  RSS 内存: %s\n", memory.getProperty("used_memory_rss_human", "N/A")));
            sb.append(String.format("  内存上限: %s\n", memory.getProperty("maxmemory_human", "N/A")));
            sb.append(String.format("  内存碎片率: %s\n", memory.getProperty("mem_fragmentation_ratio", "N/A")));

            sb.append("\n[连接]\n");
            sb.append(String.format("  connected_clients: %s\n", stats.getProperty("connected_clients", "N/A")));
            sb.append(String.format("  rejected_connections: %s\n", stats.getProperty("rejected_connections", "0")));

            return sb.toString();
        });
    }

    @Tool("获取 Redis 最近 N 条慢查询日志。参数 count 为获取条数(建议 5-20)。用于排查哪些命令执行时间过长。")
    public String getRedisSlowLog(int count) {
        return redis.execute((RedisCallback<String>) connection -> {
            List<?> entries;
            try {
                @SuppressWarnings("unchecked")
                List<Object> raw = (List<Object>) connection.scriptingCommands()
                        .eval("return redis.call('SLOWLOG', 'GET', ARGV[1])"
                                        .getBytes(StandardCharsets.UTF_8),
                                ReturnType.MULTI,
                                0,
                                String.valueOf(count).getBytes(StandardCharsets.UTF_8));
                entries = raw;
            } catch (Exception e) {
                return "慢查询日志查询失败: " + e.getMessage()
                        + "\n当前 Redis 驱动可能不支持此命令。";
            }

            if (entries == null || entries.isEmpty()) {
                return "Redis 慢查询日志为空，当前无慢查询记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Redis 慢查询日志 (共 ").append(entries.size()).append(" 条) ===\n\n");

            int idx = 1;
            for (Object entryObj : entries) {
                if (!(entryObj instanceof List)) continue;
                List<?> entry = (List<?>) entryObj;
                if (entry.size() < 4) continue;

                String id = bytesToString(entry.get(0));
                String timestamp = bytesToString(entry.get(1));
                String duration = bytesToString(entry.get(2));

                long ts = Long.parseLong(timestamp);
                String timeStr = String.format("%tF %<tT", new Date(ts * 1000));

                List<String> cmdParts = new ArrayList<>();
                Object cmdArg = entry.get(3);
                if (cmdArg instanceof List) {
                    for (Object part : (List<?>) cmdArg) {
                        cmdParts.add(bytesToString(part));
                    }
                }

                sb.append("[").append(idx++).append("] ");
                sb.append("ID=").append(id);
                sb.append(" | 耗时=").append(duration).append("μs");
                sb.append(" | 时间=").append(timeStr).append("\n");
                sb.append("    命令: ").append(String.join(" ", cmdParts)).append("\n\n");
            }

            return sb.toString();
        });
    }

    @Tool("扫描 Redis 中大 Key，返回按大小排序的 TOP 20 列表。" +
          "扫描样本数 maxScan 默认 500，最大 1000。" +
          "字符串 key 的单位为字节，集合类型为元素个数。" +
          "用于诊断 Redis 内存占用瓶颈，定位哪些 key 消耗了最多内存。")
    public String getRedisBigKeys(int maxScan) {
        if (maxScan <= 0) maxScan = 500;
        int limit = Math.min(maxScan, 1000);

        return redis.execute((RedisCallback<String>) connection -> {
            List<KeySizeEntry> entries = new ArrayList<>();
            int scanned = 0;

            try (Cursor<byte[]> cursor = connection.keyCommands()
                    .scan(ScanOptions.scanOptions().count(200).build())) {
                while (cursor.hasNext() && scanned < limit) {
                    byte[] keyBytes = cursor.next();
                    String key = new String(keyBytes, StandardCharsets.UTF_8);
                    DataType type = connection.keyCommands().type(keyBytes);
                    long size = getSizeByType(connection, keyBytes, type);
                    entries.add(new KeySizeEntry(key, type.name(), size));
                    scanned++;
                }
            }

            entries.sort((a, b) -> Long.compare(b.size, a.size));

            StringBuilder sb = new StringBuilder();
            sb.append("=== Redis 大 Key 扫描 ===\n\n");
            sb.append("共扫描 ").append(scanned).append(" 个 key\n\n");
            sb.append("| # | Key (截断) | 类型 | 大小 |\n");
            sb.append("|---|-----------|------|------|\n");

            int show = Math.min(20, entries.size());
            for (int i = 0; i < show; i++) {
                KeySizeEntry e = entries.get(i);
                String disp = e.key.length() > 38 ? e.key.substring(0, 35) + "..." : e.key;
                String sizeStr = switch (e.type) {
                    case "STRING" -> formatBytes(e.size);
                    default -> String.valueOf(e.size) + " 个元素";
                };
                sb.append(String.format("| %d | %s | %s | %s |\n",
                        i + 1, disp, e.type, sizeStr));
            }

            long largeCount = entries.stream()
                    .filter(e -> "STRING".equals(e.type) && e.size > 1024 * 1024).count();
            if (largeCount > 0) {
                sb.append("\n⚠️ 超过 1MB 的大 String Key: ").append(largeCount).append(" 个\n");
            }
            return sb.toString();
        });
    }

    private static long getSizeByType(
            org.springframework.data.redis.connection.RedisConnection conn,
            byte[] key, DataType type) {
        try {
            return switch (type) {
                case STRING -> {
                    Long len = conn.stringCommands().strLen(key);
                    yield len != null ? len : 0;
                }
                case HASH -> {
                    Long len = conn.hashCommands().hLen(key);
                    yield len != null ? len : 0;
                }
                case LIST -> {
                    Long len = conn.listCommands().lLen(key);
                    yield len != null ? len : 0;
                }
                case SET -> {
                    Long len = conn.setCommands().sCard(key);
                    yield len != null ? len : 0;
                }
                case ZSET -> {
                    Long len = conn.zSetCommands().zCard(key);
                    yield len != null ? len : 0;
                }
                default -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    private record KeySizeEntry(String key, String type, long size) {}

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    private long parseLong(Properties props, String key) {
        String val = props.getProperty(key, "0");
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String bytesToString(Object obj) {
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        }
        return obj != null ? obj.toString() : "";
    }
}
