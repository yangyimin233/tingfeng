package com.tingfeng.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        send(requestId, result);
    }

    // ── 执行工具 ──
    private static void sendToolCallResult(Object requestId, JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.path("arguments");

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
                default:
                    sendError(requestId, -32602, "Unknown tool: " + toolName);
                    return;
            }

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

    // ── MySQL 诊断方法 ──
    private static Connection getConnection() throws Exception {
        String host = env("MYSQL_HOST", "localhost");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String pass = env("MYSQL_PASS", "1234");
        String db   = env("MYSQL_DB", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000";
        return DriverManager.getConnection(url, user, pass);
    }

    private static String executeQuery(String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误：SQL 语句为空";
        }
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE") || upper.startsWith("DROP")
                || upper.startsWith("ALTER") || upper.startsWith("CREATE")
                || upper.startsWith("TRUNCATE")) {
            return "错误：仅允许只读查询（SELECT/SHOW/DESCRIBE/EXPLAIN）";
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            StringBuilder sb = new StringBuilder();
            sb.append("=== 查询结果 ===\n\n");

            // 表头
            List<String> headers = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                headers.add(meta.getColumnName(i));
            }
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|").append(String.join("|", headers.stream().map(h -> "---").toList())).append("|\n");

            // 数据行
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

        } catch (Exception e) {
            return "查询执行失败: " + e.getMessage();
        }
    }

    private static String checkSlowQueries() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MySQL 慢查询诊断 ===\n\n");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

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
