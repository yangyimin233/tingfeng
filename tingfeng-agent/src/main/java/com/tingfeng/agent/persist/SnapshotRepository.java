package com.tingfeng.agent.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Agent 端探针快照 MySQL 持久化, 首次启动自动建表。
 */
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS tingfeng_snapshot (" +
            "  id            BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "  trace_id      VARCHAR(32)  NOT NULL," +
            "  server_host   VARCHAR(100) DEFAULT 'unknown'," +
            "  method_name   VARCHAR(255) NOT NULL," +
            "  args          MEDIUMTEXT," +
            "  return_value  MEDIUMTEXT," +
            "  request_time  BIGINT       NOT NULL," +
            "  rt_ms         BIGINT       NOT NULL," +
            "  success       TINYINT(1)   NOT NULL," +
            "  error_msg     TEXT," +
            "  error_stack   MEDIUMTEXT," +
            "  sql_statements MEDIUMTEXT," +
            "  timestamp     BIGINT       NOT NULL," +
            "  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP," +
            "  INDEX idx_request (request_time)," +
            "  INDEX idx_timestamp (timestamp)," +
            "  INDEX idx_method   (method_name)," +
            "  INDEX idx_success  (success)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String INSERT_SQL =
            "INSERT INTO tingfeng_snapshot" +
            "  (trace_id, server_host, method_name, args, return_value, request_time, rt_ms, success, error_msg, error_stack, sql_statements, timestamp)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public SnapshotRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        ensureTable();
    }

    public void save(Map<String, Object> snapshot) {
        try {
            jdbc.update(INSERT_SQL,
                    snapshot.get("traceId"),
                    snapshot.getOrDefault("serverHost", "unknown"),
                    snapshot.get("methodName"),
                    truncate((String) snapshot.get("args"), 20000),
                    truncate((String) snapshot.get("returnValue"), 20000),
                    toLong(snapshot.get("requestTime")),
                    toLong(snapshot.get("rt")),
                    Boolean.TRUE.equals(snapshot.get("success")) ? 1 : 0,
                    snapshot.get("errorMsg"),
                    truncate((String) snapshot.get("errorStack"), 20000),
                    truncate((String) snapshot.get("sqlStatements"), 20000),
                    toLong(snapshot.get("timestamp")));
        } catch (Exception e) {
            log.debug("探针快照落库失败: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        try {
            jdbc.execute(DDL);
            // 兼容已有表: 补齐 request_time 列
            try { jdbc.execute("ALTER TABLE tingfeng_snapshot ADD COLUMN request_time BIGINT"); }
            catch (Exception ignored) { /* 列已存在 */ }
            try { jdbc.execute("ALTER TABLE tingfeng_snapshot ADD COLUMN sql_statements MEDIUMTEXT"); }
            catch (Exception ignored) { /* 列已存在 */ }
            log.info("探针快照表 tingfeng_snapshot 已就绪");
        } catch (Exception e) {
            log.warn("建表失败, MySQL 持久化不可用: {}", e.getMessage());
        }
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) return Long.parseLong(s);
        return 0L;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
