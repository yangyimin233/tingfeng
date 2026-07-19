package com.tingfeng.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.tingfeng.agent.util.SensitiveDataMasker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小化 Java MCP stdio Server，提供 MySQL 诊断工具。
 * <p>
 * 传输: stdin/stdout 逐行 JSON-RPC 2.0
 * 环境变量:
 *   MYSQL_HOST  (默认 localhost)
 *   MYSQL_PORT  (默认 3306)
 *   MYSQL_USER  (默认 root)
 *   MYSQL_PASS  (默认 1234)
 *   MYSQL_DB    (可选，不指定则列出所有库)
 */
public class MySqlMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SensitiveDataMasker MASKER = SensitiveDataMasker.defaultMasker();

    public static void main(String[] args) {
        // stdout 必须是干净的 JSON —— 日志走 stderr
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;

                JsonNode request;
                try {
                    request = MAPPER.readTree(line);
                } catch (Exception e) {
                    System.err.println("[MySqlMcp] invalid JSON: " + line);
                    continue;
                }

                String method = request.path("method").asText();
                Object requestId = request.has("id") && !request.get("id").isNull()
                        ? request.get("id").asText() : null;

                // 通知消息没有 id，无需回复
                if (requestId == null) {
                    continue;
                }

                JsonNode params = request.path("params");

                switch (method) {
                    case "initialize":
                        sendInitializeResponse(requestId);
                        break;
                    case "tools/list":
                        sendToolList(requestId);
                        break;
                    case "tools/call":
                        sendToolCallResult(requestId, params);
                        break;
                    default:
                        sendError(requestId, -32601, "Method not found: " + method);
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
        capabilities.putObject("tools");       // 声明支持 tools
        ObjectNode protocolVersion = result.putObject("protocolVersion");
        protocolVersion.put("version", "2025-03-26");   // MCP 最新协议版本
        send(requestId, result);
    }

    // ── 返回工具列表 ──
    private static void sendToolList(Object requestId) {
        ArrayNode tools = MAPPER.createArrayNode();

        // 工具 1: mysql_query
        ObjectNode queryTool = tools.addObject();
        queryTool.put("name", "mysql_query");
        queryTool.put("description",
                "执行 MySQL 只读查询（SELECT/SHOW/DESCRIBE/EXPLAIN）。"
                + "用于检查表结构、查询数据、分析索引等。"
                + "禁止 INSERT/UPDATE/DELETE/DROP 等写操作。");
        ObjectNode querySchema = queryTool.putObject("inputSchema");
        querySchema.put("type", "object");
        ObjectNode queryProps = querySchema.putObject("properties");
        queryProps.putObject("sql")
                .put("type", "string")
                .put("description", "要执行的 SQL 查询语句（仅限只读）");

        // 工具 2: mysql_slow_queries
        ObjectNode slowTool = tools.addObject();
        slowTool.put("name", "mysql_slow_queries");
        slowTool.put("description",
                "获取 MySQL 慢查询相关指标和配置。"
                + "包括 slow_query_log 是否开启、慢查询阈值 long_query_time、"
                + "慢查询文件位置等。用于诊断 SQL 性能问题。");
        slowTool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("placeholder")
                .put("type", "string")
                .put("description", "此工具无需参数");

        // 工具 3: mysql_connections
        ObjectNode connTool = tools.addObject();
        connTool.put("name", "mysql_connections");
        connTool.put("description",
                "获取 MySQL 当前连接数和连接相关配置。"
                + "包括 Threads_connected、Threads_running、max_connections、"
                + "连接使用率等。用于诊断连接池耗尽或连接泄漏问题。");
        connTool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("placeholder")
                .put("type", "string")
                .put("description", "此工具无需参数");

        // 工具 4: mysql_buffer_pool
        ObjectNode bufTool = tools.addObject();
        bufTool.put("name", "mysql_buffer_pool");
        bufTool.put("description",
                "获取 InnoDB Buffer Pool 命中率 — MySQL 最重要的性能指标。"
                + "命中率低于 99% 意味着大量读取命中磁盘，是'突然变慢'的首要排查项。"
                + "同时返回 Buffer Pool 大小和脏页比例。");
        bufTool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("placeholder")
                .put("type", "string")
                .put("description", "此工具无需参数");

        // 工具 5: mysql_running_queries
        ObjectNode runTool = tools.addObject();
        runTool.put("name", "mysql_running_queries");
        runTool.put("description",
                "获取 MySQL 当前正在执行的查询列表（非 Sleep 状态）。"
                + "包括执行时长、状态、SQL 文本。比慢查询日志更实时，"
                + "直接定位'此时此刻'消耗资源的 SQL。"
                + "可选参数 minTime: 仅返回执行超过 N 秒的查询, 默认 0 返回全部。");
        ObjectNode runSchema = runTool.putObject("inputSchema");
        runSchema.put("type", "object");
        ObjectNode runProps = runSchema.putObject("properties");
        runProps.putObject("minTime")
                .put("type", "integer")
                .put("description", "最小执行秒数过滤, 默认 0");

        // 工具 6: mysql_replication
        ObjectNode replTool = tools.addObject();
        replTool.put("name", "mysql_replication");
        replTool.put("description",
                "获取 MySQL 主从复制状态。"
                + "包括 Slave_IO_Running、Slave_SQL_Running、Seconds_Behind_Master、"
                + "复制延迟秒数等。用于诊断主从同步异常和数据延迟问题。");
        replTool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("placeholder")
                .put("type", "string")
                .put("description", "此工具无需参数");

        // 工具 7: mysql_lock_waits
        ObjectNode lockTool = tools.addObject();
        lockTool.put("name", "mysql_lock_waits");
        lockTool.put("description",
                "获取 InnoDB 锁等待链信息。"
                + "包括等待事务ID、等待SQL、阻塞事务ID、阻塞SQL。"
                + "用于诊断'锁超时'和'事务阻塞'类问题，定位阻塞源头。");
        lockTool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("placeholder")
                .put("type", "string")
                .put("description", "此工具无需参数");

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        send(requestId, result);
    }

    // ── 执行工具 ──
    private static void sendToolCallResult(Object requestId, JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        long start = System.currentTimeMillis();

        try {
            String text;
            switch (toolName) {
                case "mysql_query":
                    text = executeQuery(arguments.path("sql").asText());
                    break;
                case "mysql_slow_queries":
                    text = checkSlowQueries();
                    break;
                case "mysql_connections":
                    text = checkConnections();
                    break;
                case "mysql_buffer_pool":
                    text = checkBufferPool();
                    break;
                case "mysql_running_queries":
                    text = checkRunningQueries(arguments);
                    break;
                case "mysql_replication":
                    text = checkReplication();
                    break;
                case "mysql_lock_waits":
                    text = checkLockWaits();
                    break;
                default:
                    sendError(requestId, -32602, "Unknown tool: " + toolName);
                    return;
            }

            text = MASKER.maskJson(text);

            logToolCall(toolName, arguments.toString(), text, (int)(System.currentTimeMillis() - start), true, null);

            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            send(requestId, result);
        } catch (Exception e) {
            logToolCall(toolName, arguments.toString(), null, (int)(System.currentTimeMillis() - start), false, e.getMessage());
            sendError(requestId, -32000, "Tool execution error: " + e.getMessage());
        }
    }

    // ── MySQL 诊断方法 ──
    private static Connection getConnection() throws Exception {
        String host = env("MYSQL_HOST", "localhost");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String pass = env("MYSQL_PASS", "1234");
        String db   = env("MYSQL_DB", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000"
                + "&socketTimeout=3000&allowMultiQueries=false";
        return DriverManager.getConnection(url, user, pass);
    }

    private static String executeQuery(String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误：SQL 语句为空";
        }
        String upper = sql.trim().toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("SHOW")
                && !upper.startsWith("DESCRIBE") && !upper.startsWith("EXPLAIN")
                && !upper.startsWith("WITH")) {
            return "错误：仅允许只读查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH）";
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);
            try (ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            StringBuilder sb = new StringBuilder();
            sb.append("=== 查询结果 ===\n\n");

            List<String> headers = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                headers.add(meta.getColumnName(i));
            }
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|").append(String.join("|", headers.stream().map(h -> "---").toList())).append("|\n");

            int rowCount = 0;
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    String val = rs.getString(i);
                    row.add(val == null ? "NULL" : val);
                }
                sb.append("| ").append(String.join(" | ", row)).append(" |\n");
                rowCount++;
                if (rowCount >= 50) {
                    sb.append("\n... (结果截断，仅显示前 50 行)\n");
                    break;
                }
            }
            sb.append("\n共 ").append(rowCount).append(" 行");
            return sb.toString();
            }

        } catch (Exception e) {
            return "查询执行失败: " + e.getMessage();
        }
    }

    private static String checkSlowQueries() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MySQL 慢查询诊断 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            // 慢查询配置
            ResultSet vars = stmt.executeQuery(
                    "SHOW VARIABLES LIKE 'slow_query_log%'");
            sb.append("[慢查询配置]\n");
            while (vars.next()) {
                sb.append("  ").append(vars.getString(1))
                        .append(" = ").append(vars.getString(2)).append("\n");
            }
            vars.close();

            // 慢查询阈值
            ResultSet time = stmt.executeQuery(
                    "SHOW VARIABLES LIKE 'long_query_time'");
            sb.append("\n[慢查询阈值]\n");
            while (time.next()) {
                sb.append("  long_query_time = ").append(time.getString(2)).append(" 秒\n");
            }
            time.close();

            // 慢查询计数
            ResultSet status = stmt.executeQuery(
                    "SHOW GLOBAL STATUS LIKE 'Slow_queries'");
            sb.append("\n[慢查询统计]\n");
            while (status.next()) {
                sb.append("  Slow_queries = ").append(status.getString(2)).append("\n");
            }
            status.close();

            // 尝试读取 slow_log 表（MySQL 5.6+ 支持）
            try {
                ResultSet log = stmt.executeQuery(
                        "SELECT start_time, user_host, query_time, lock_time, rows_sent, rows_examined, "
                        + "sql_text FROM mysql.slow_log ORDER BY start_time DESC LIMIT 10");
                sb.append("\n[最近 10 条慢查询]\n");
                sb.append("| 时间 | 来源 | 耗时 | 锁等待 | 行数 | 扫描行 | SQL |\n");
                sb.append("|------|------|------|--------|------|--------|-----|\n");
                boolean hasRows = false;
                while (log.next()) {
                    hasRows = true;
                    sb.append("| ").append(trunc(log.getString(1), 19))
                            .append(" | ").append(trunc(log.getString(2), 15))
                            .append(" | ").append(trunc(log.getString(3), 8))
                            .append(" | ").append(trunc(log.getString(4), 8))
                            .append(" | ").append(trunc(log.getString(5), 6))
                            .append(" | ").append(trunc(log.getString(6), 8))
                            .append(" | ").append(trunc(log.getString(7), 30))
                            .append(" |\n");
                }
                if (!hasRows) sb.append("（无慢查询记录）\n");
                log.close();
            } catch (Exception e) {
                sb.append("\n[慢查询日志表]\n  无法读取 mysql.slow_log 表: ").append(e.getMessage()).append("\n");
                sb.append("  提示: 确保 slow_query_log=ON 且 log_output=TABLE\n");
            }

        } catch (Exception e) {
            return "慢查询诊断失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String checkConnections() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MySQL 连接诊断 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            // 连接数相关变量
            ResultSet vars = stmt.executeQuery(
                    "SHOW VARIABLES LIKE 'max_connections'");
            int maxConn = 0;
            if (vars.next()) maxConn = vars.getInt(2);
            vars.close();

            // 当前连接数
            ResultSet threads = stmt.executeQuery(
                    "SHOW STATUS LIKE 'Threads%'");
            sb.append("[连接统计]\n");
            int connected = 0, running = 0;
            while (threads.next()) {
                String name = threads.getString(1);
                String val  = threads.getString(2);
                sb.append("  ").append(name).append(" = ").append(val).append("\n");
                if ("Threads_connected".equals(name)) connected = Integer.parseInt(val);
                if ("Threads_running".equals(name)) running = Integer.parseInt(val);
            }
            threads.close();

            // 使用率
            sb.append("\n[连接池使用率]\n");
            sb.append(String.format("  最大连接数: %d\n", maxConn));
            sb.append(String.format("  当前连接数: %d\n", connected));
            sb.append(String.format("  活跃连接数: %d\n", running));
            if (maxConn > 0) {
                double usage = (double) connected / maxConn * 100;
                sb.append(String.format("  使用率: %.1f%%  %s\n", usage,
                        usage > 80 ? "⚠️ 接近上限，建议排查或扩容"
                                : usage > 50 ? "  偏高，需关注"
                                : "✅ 正常"));
            }

            // 连接来源分布
            ResultSet processlist = stmt.executeQuery(
                    "SELECT USER, HOST, COUNT(*) as cnt FROM information_schema.PROCESSLIST "
                    + "GROUP BY USER, HOST ORDER BY cnt DESC LIMIT 10");
            sb.append("\n[连接来源 TOP 10]\n");
            sb.append("| 用户 | 来源主机 | 连接数 |\n");
            sb.append("|------|----------|--------|\n");
            while (processlist.next()) {
                sb.append("| ").append(trunc(processlist.getString(1), 15))
                        .append(" | ").append(trunc(processlist.getString(2), 20))
                        .append(" | ").append(processlist.getInt(3))
                        .append(" |\n");
            }
            processlist.close();

        } catch (Exception e) {
            return "连接诊断失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String checkBufferPool() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== InnoDB Buffer Pool 命中率诊断 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            ResultSet rs = stmt.executeQuery(
                    "SHOW STATUS LIKE 'Innodb_buffer_pool_read%'");
            long readRequests = 0;
            long reads = 0;
            while (rs.next()) {
                String name = rs.getString(1);
                long val = rs.getLong(2);
                sb.append("  ").append(name).append(" = ").append(val).append("\n");
                if ("Innodb_buffer_pool_read_requests".equals(name)) readRequests = val;
                if ("Innodb_buffer_pool_reads".equals(name)) reads = val;
            }
            rs.close();

            sb.append("\n[命中率]\n");
            if (readRequests > 0) {
                double hitRate = (1.0 - (double) reads / readRequests) * 100;
                sb.append(String.format("  Buffer Pool 命中率: %.2f%%\n", hitRate));
                sb.append(String.format("  物理读(读磁盘): %d\n", reads));
                sb.append(String.format("  逻辑读(读缓存): %d\n", readRequests));
                if (hitRate < 95) {
                    sb.append("  ⚠️ 命中率严重偏低! 大量读取命中磁盘，建议增大 buffer_pool_size\n");
                } else if (hitRate < 99) {
                    sb.append("    命中率偏低，需关注趋势，考虑增大 buffer_pool_size\n");
                } else if (hitRate < 99.9) {
                    sb.append("    正常，偶有物理读取\n");
                } else {
                    sb.append("  ✅ 命中率优秀\n");
                }
            }

            ResultSet vars = stmt.executeQuery(
                    "SHOW VARIABLES LIKE 'innodb_buffer_pool_size'");
            sb.append("\n[Buffer Pool 配置]\n");
            while (vars.next()) {
                long sizeBytes = vars.getLong(2);
                sb.append("  innodb_buffer_pool_size = ")
                        .append(formatBytes(sizeBytes)).append("\n");
            }
            vars.close();

            ResultSet dirty = stmt.executeQuery(
                    "SHOW STATUS LIKE 'Innodb_buffer_pool_pages_dirty'");
            ResultSet total = stmt.executeQuery(
                    "SHOW STATUS LIKE 'Innodb_buffer_pool_pages_total'");
            if (dirty.next() && total.next()) {
                long dirtyPages = dirty.getLong(2);
                long totalPages = total.getLong(2);
                if (totalPages > 0) {
                    double dirtyPct = (double) dirtyPages / totalPages * 100;
                    sb.append(String.format("  脏页比例: %.1f%% (%d / %d pages)\n",
                            dirtyPct, dirtyPages, totalPages));
                    if (dirtyPct > 50) {
                        sb.append("  ⚠️ 脏页比例偏高，可能有刷盘压力\n");
                    }
                }
            }
            dirty.close();
            total.close();

        } catch (Exception e) {
            return "Buffer Pool 诊断失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String checkRunningQueries(JsonNode args) {
        int minTime = args.has("minTime") ? args.get("minTime").asInt() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("=== 当前运行的查询 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            String sql = "SELECT ID, USER, HOST, DB, TIME, STATE, "
                    + "IF(LENGTH(INFO) > 300, CONCAT(LEFT(INFO, 300), '...'), INFO) AS INFO "
                    + "FROM information_schema.PROCESSLIST "
                    + "WHERE COMMAND != 'Sleep'";
            if (minTime > 0) sql += " AND TIME >= " + minTime;
            sql += " ORDER BY TIME DESC LIMIT 20";

            ResultSet rs = stmt.executeQuery(sql);
            sb.append("| 连接ID | 用户 | 来源 | 数据库 | 执行秒 | 状态 | SQL |\n");
            sb.append("|--------|------|------|--------|--------|------|-----|\n");

            int count = 0;
            while (rs.next()) {
                count++;
                sb.append("| ").append(rs.getInt(1))
                        .append(" | ").append(trunc(rs.getString(2), 10))
                        .append(" | ").append(trunc(rs.getString(3), 18))
                        .append(" | ").append(trunc(rs.getString(4), 10))
                        .append(" | ").append(rs.getInt(5))
                        .append(" | ").append(trunc(rs.getString(6), 12))
                        .append(" | ").append(trunc(rs.getString(7), 40))
                        .append(" |\n");
            }
            rs.close();

            if (count == 0) {
                sb.append("（当前无运行中的查询）\n");
            } else {
                sb.append("\n共 ").append(count).append(" 条运行中的查询\n");
            }

            // 锁等待
            try {
                ResultSet lockRs = stmt.executeQuery(
                        "SELECT COUNT(*) as lock_waits FROM information_schema.PROCESSLIST "
                        + "WHERE STATE LIKE '%lock%'");
                if (lockRs.next()) {
                    int lockWaits = lockRs.getInt(1);
                    if (lockWaits > 0) {
                        sb.append("⚠️ 有 ").append(lockWaits).append(" 个会话处于锁等待状态!\n");
                    }
                }
                lockRs.close();
            } catch (Exception ignored) {}

        } catch (Exception e) {
            return "运行查询诊断失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String checkReplication() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MySQL 主从复制状态 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            // Try SHOW REPLICA STATUS (MySQL 8.0+)
            try {
                ResultSet rs = stmt.executeQuery("SHOW REPLICA STATUS");
                if (rs.next()) {
                    formatReplicationResult(rs, sb);
                    rs.close();
                    return sb.toString();
                }
                rs.close();
            } catch (Exception e) {
                // Fallback to SHOW SLAVE STATUS (MySQL 5.7)
            }

            try {
                ResultSet rs = stmt.executeQuery("SHOW SLAVE STATUS");
                if (rs.next()) {
                    formatReplicationResult(rs, sb);
                    rs.close();
                    return sb.toString();
                }
                rs.close();
            } catch (Exception e) {
                return "未配置主从复制, 或无法获取复制状态: " + e.getMessage();
            }

        } catch (Exception e) {
            return "复制状态诊断失败: " + e.getMessage();
        }
        return "未配置主从复制";
    }

    private static void formatReplicationResult(ResultSet rs, StringBuilder sb) throws Exception {
        sb.append(String.format("  Slave_IO_Running: %s\n",
                rs.getString("Slave_IO_Running")));
        sb.append(String.format("  Slave_SQL_Running: %s\n",
                rs.getString("Slave_SQL_Running")));
        Object behind = rs.getObject("Seconds_Behind_Master");
        String behindStr = behind != null ? behind.toString() : "NULL";
        sb.append(String.format("  Seconds_Behind_Master: %s\n", behindStr));
        sb.append(String.format("  Master_Host: %s\n",
                rs.getString("Master_Host")));
        sb.append(String.format("  Master_Log_File: %s\n",
                rs.getString("Master_Log_File")));
        sb.append(String.format("  Relay_Log_File: %s\n",
                rs.getString("Relay_Log_File")));
        sb.append(String.format("  Last_IO_Errno: %s\n",
                rs.getString("Last_IO_Errno")));
        sb.append(String.format("  Last_IO_Error: %s\n",
                rs.getString("Last_IO_Error")));
        sb.append(String.format("  Last_SQL_Errno: %s\n",
                rs.getString("Last_SQL_Errno")));
        sb.append(String.format("  Last_SQL_Error: %s\n",
                rs.getString("Last_SQL_Error")));

        String ioRunning = rs.getString("Slave_IO_Running");
        String sqlRunning = rs.getString("Slave_SQL_Running");
        try {
            long lag = Long.parseLong(behindStr);
            if (lag > 60) {
                sb.append("\n  ⚠️ 主从延迟超过 60 秒!\n");
            } else if ("No".equals(ioRunning) || "No".equals(sqlRunning)) {
                sb.append("\n  ⚠️ 复制线程已停止!\n");
            } else {
                sb.append("\n  ✅ 复制状态正常\n");
            }
        } catch (NumberFormatException e) {
            if ("No".equals(ioRunning) || "No".equals(sqlRunning)) {
                sb.append("\n  ⚠️ 复制线程已停止!\n");
            }
        }
    }

    private static String checkLockWaits() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== InnoDB 锁等待分析 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);


            // Try MySQL 8.0+ performance_schema approach
            try {
                ResultSet rs = stmt.executeQuery(
                        "SELECT r.PROCESSLIST_ID AS waiting_tid, "
                        + "IF(LENGTH(r.PROCESSLIST_INFO) > 200, "
                        + "CONCAT(LEFT(r.PROCESSLIST_INFO, 200), '...'), "
                        + "r.PROCESSLIST_INFO) AS waiting_query, "
                        + "b.PROCESSLIST_ID AS blocking_tid, "
                        + "IF(LENGTH(b.PROCESSLIST_INFO) > 200, "
                        + "CONCAT(LEFT(b.PROCESSLIST_INFO, 200), '...'), "
                        + "b.PROCESSLIST_INFO) AS blocking_query "
                        + "FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.threads r "
                        + "ON w.REQUESTING_THREAD_ID = r.THREAD_ID "
                        + "JOIN performance_schema.threads b "
                        + "ON w.BLOCKING_THREAD_ID = b.THREAD_ID LIMIT 20");

                return formatLockResults(rs, sb);
            } catch (Exception e) {
                sb.append("  performance_schema 方案不可用, 尝试 sys schema...\n");
            }

            // Fallback: try sys.innodb_lock_waits
            try {
                ResultSet rs = stmt.executeQuery(
                        "SELECT waiting_pid AS waiting_tid, "
                        + "waiting_query, blocking_pid AS blocking_tid, "
                        + "blocking_query FROM sys.innodb_lock_waits LIMIT 20");

                return formatLockResults(rs, sb);
            } catch (Exception e) {
                sb.append("  sys schema 不可用, 尝试传统 INFORMATION_SCHEMA...\n");
            }

            // Fallback: old MySQL 5.x approach
            try {
                ResultSet rs = stmt.executeQuery(
                        "SELECT requesting_trx_id, "
                        + "blocking_trx_id FROM information_schema.INNODB_LOCK_WAITS");
                sb.append("| 等待事务ID | 阻塞事务ID |\n");
                sb.append("|-----------|------------|\n");
                int count = 0;
                while (rs.next()) {
                    count++;
                    sb.append("| ").append(rs.getString(1))
                            .append(" | ").append(rs.getString(2))
                            .append(" |\n");
                }
                if (count == 0) {
                    sb.append("（当前无锁等待）\n");
                } else {
                    sb.append("\n共 ").append(count).append(" 条锁等待关系\n");
                }
                rs.close();
            } catch (Exception ee) {
                return "锁等待分析不可用: " + ee.getMessage();
            }

        } catch (Exception e) {
            return "锁等待诊断失败: " + e.getMessage();
        }
        return sb.toString();
    }

    private static String formatLockResults(ResultSet rs, StringBuilder sb) throws Exception {
        sb.append("| 等待线程ID | 等待SQL | 阻塞线程ID | 阻塞SQL |\n");
        sb.append("|-----------|---------|-----------|--------|\n");
        int count = 0;
        while (rs.next()) {
            count++;
            sb.append("| ").append(rs.getString("waiting_tid"))
                    .append(" | ").append(trunc(rs.getString("waiting_query"), 30))
                    .append(" | ").append(rs.getString("blocking_tid"))
                    .append(" | ").append(trunc(rs.getString("blocking_query"), 30))
                    .append(" |\n");
        }
        if (count == 0) {
            sb.append("（当前无锁等待）\n");
        } else {
            sb.append("\n共 ").append(count).append(" 条锁等待链\n");
            sb.append("⚠️ 存在锁等待! 阻塞线程 ID 是锁的持有者，需要优先处理。\n");
        }
        rs.close();
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
        if (requestId != null) {
            response.put("id", String.valueOf(requestId));
        }
        response.set("result", result);
        System.out.println(response.toString());
        System.out.flush();
    }

    private static void sendError(Object requestId, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (requestId != null) {
            response.put("id", String.valueOf(requestId));
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        System.out.println(response.toString());
        System.out.flush();
    }

    // ── 工具方法 ──
    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }

    private static String trunc(String s, int maxLen) {
        if (s == null) return "NULL";
        s = s.replace("\n", " ").replace("\r", "").replace("|", "\\|");
        return s.length() > maxLen ? s.substring(0, maxLen - 1) + "…" : s;
    }
}
