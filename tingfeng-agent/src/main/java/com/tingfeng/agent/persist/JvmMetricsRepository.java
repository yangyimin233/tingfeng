package com.tingfeng.agent.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿主 JVM 指标 MySQL 持久化, 首次启动自动建表。
 */
public class JvmMetricsRepository {

    private static final Logger log = LoggerFactory.getLogger(JvmMetricsRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS tingfeng_jvm_metrics (" +
            "  id             BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "  cpu_system     DOUBLE," +
            "  cpu_process    DOUBLE," +
            "  processors     INT," +
            "  heap_used_mb   BIGINT," +
            "  heap_max_mb    BIGINT," +
            "  non_heap_used_mb BIGINT," +
            "  sys_free_mb    BIGINT," +
            "  sys_total_mb   BIGINT," +
            "  thread_count   INT," +
            "  thread_peak    INT," +
            "  daemon_threads INT," +
            "  deadlocked     TINYINT," +
            "  gc_young_count BIGINT," +
            "  gc_young_time_ms BIGINT," +
            "  gc_old_count   BIGINT," +
            "  gc_old_time_ms BIGINT," +
            "  timestamp      BIGINT NOT NULL," +
            "  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  INDEX idx_timestamp (timestamp)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String INSERT_SQL =
            "INSERT INTO tingfeng_jvm_metrics" +
            " (cpu_system, cpu_process, processors, heap_used_mb, heap_max_mb," +
            "  non_heap_used_mb, sys_free_mb, sys_total_mb, thread_count, thread_peak," +
            "  daemon_threads, deadlocked, gc_young_count, gc_young_time_ms," +
            "  gc_old_count, gc_old_time_ms, timestamp)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    public JvmMetricsRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        ensureTable();
    }

    public void save(Map<String, Object> metrics) {
        try {
            jdbc.update(INSERT_SQL,
                    toDouble(metrics.get("cpuSystem")),
                    toDouble(metrics.get("cpuProcess")),
                    toInt(metrics.get("processors")),
                    toLong(metrics.get("heapUsedMb")),
                    toLong(metrics.get("heapMaxMb")),
                    toLong(metrics.get("nonHeapUsedMb")),
                    toLong(metrics.get("sysFreeMb")),
                    toLong(metrics.get("sysTotalMb")),
                    toInt(metrics.get("threadCount")),
                    toInt(metrics.get("threadPeak")),
                    toInt(metrics.get("daemonThreads")),
                    toInt(metrics.get("deadlocked")),
                    toLong(metrics.get("gcYoungCount")),
                    toLong(metrics.get("gcYoungTimeMs")),
                    toLong(metrics.get("gcOldCount")),
                    toLong(metrics.get("gcOldTimeMs")),
                    toLong(metrics.get("timestamp")));
        } catch (Exception e) {
            log.debug("JVM 指标落库失败: {}", e.getMessage());
        }
    }

    /** 查询最近 N 条 JVM 指标 */
    public List<Map<String, Object>> queryRecent(int limit) {
        try {
            return jdbc.queryForList(
                    "SELECT * FROM tingfeng_jvm_metrics ORDER BY timestamp DESC LIMIT ?",
                    Math.min(limit, 200));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 查询最近一条 JVM 指标 */
    public Map<String, Object> queryLatest() {
        try {
            List<Map<String, Object>> list = queryRecent(1);
            return list.isEmpty() ? new LinkedHashMap<>() : list.get(0);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 查询指定时间范围内的 JVM 指标 */
    public List<Map<String, Object>> queryRange(long fromTime, int limit) {
        try {
            return jdbc.queryForList(
                    "SELECT * FROM tingfeng_jvm_metrics WHERE timestamp > ? ORDER BY timestamp DESC LIMIT ?",
                    fromTime, Math.min(limit, 200));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void ensureTable() {
        try {
            jdbc.execute(DDL);
            jdbc.execute("CREATE TABLE IF NOT EXISTS tingfeng_tool_call_log (" +
                    "  id            BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  tool_name     VARCHAR(100) NOT NULL," +
                    "  arguments     MEDIUMTEXT," +
                    "  result_summary VARCHAR(500)," +
                    "  duration_ms   INT," +
                    "  success       TINYINT(1) NOT NULL," +
                    "  error_msg     TEXT," +
                    "  call_time     BIGINT NOT NULL," +
                    "  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "  INDEX idx_tool (tool_name)," +
                    "  INDEX idx_call_time (call_time)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("JVM 指标表 + 工具调用日志表 已就绪");
        } catch (Exception e) {
            log.warn("建表失败: {}", e.getMessage());
        }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0;
    }
}
