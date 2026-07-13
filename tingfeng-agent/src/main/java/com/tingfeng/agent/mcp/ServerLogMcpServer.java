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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP stdio Server — 从持久化库查询被监控服务器上报的日志。
 * 环境变量 PERSISTENCE_URL / PERSISTENCE_USER / PERSISTENCE_PASS 由 ToolRegistryManager 注入。
 */
public class ServerLogMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode request;
                try { request = MAPPER.readTree(line); }
                catch (Exception e) { continue; }

                String method = request.path("method").asText();
                Object requestId = request.has("id") && !request.get("id").isNull()
                        ? request.get("id").asText() : null;
                if (requestId == null) continue;

                JsonNode params = request.path("params");
                switch (method) {
                    case "initialize"    -> sendInitializeResponse(requestId);
                    case "tools/list"    -> sendToolList(requestId);
                    case "tools/call"    -> sendToolCallResult(requestId, params);
                    default              -> sendError(requestId, -32601, "Method not found");
                }
            }
        } catch (Exception e) { e.printStackTrace(System.err); System.exit(1); }
    }

    // ── MCP 握手 ──

    private static void sendInitializeResponse(Object requestId) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putObject("capabilities").putObject("tools");
        result.putObject("protocolVersion").put("version", "2025-03-26");
        send(requestId, result);
    }

    // ── 工具列表 ──

    private static void sendToolList(Object requestId) {
        ArrayNode tools = MAPPER.createArrayNode();

        addTool(tools, "log_query",
                "分页查询指定服务器的日志。可按时间范围、日志级别过滤，默认返回最近5分钟。",
                Map.of("serverHost",  "服务器主机名/IP, 不传则查全部",
                        "logLevel",   "日志级别过滤: ERROR/WARN/INFO/DEBUG, 不传则查全部",
                        "maxResults", "返回条数, 默认20, 最大100",
                        "page",       "页码, 默认1"));

        addTool(tools, "log_servers",
                "列出已上报日志的服务器列表及其日志统计。",
                Map.of("placeholder", "此工具无需参数"));

        addTool(tools, "log_stats",
                "按服务器和日志级别统计日志数量分布。",
                Map.of("serverHost", "指定服务器统计, 不传则统计全部"));

        addTool(tools, "log_recent_errors",
                "获取最近N条ERROR级别日志（自动获取最新错误）。",
                Map.of("serverHost",  "服务器主机名, 不传则查全部",
                        "maxResults", "返回条数, 默认20, 最大100"));

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        send(requestId, result);
    }

    private static void addTool(ArrayNode tools, String name, String desc, Map<String, String> props) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", desc);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode schemaProps = schema.putObject("properties");
        for (var e : props.entrySet()) {
            schemaProps.putObject(e.getKey()).put("type", "string").put("description", e.getValue());
        }
    }

    // ── 工具调度 ──

    private static void sendToolCallResult(Object requestId, JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode args = params.path("arguments");
        try {
            String result = switch (toolName) {
                case "log_query"        -> logQuery(args);
                case "log_servers"      -> logServers();
                case "log_stats"        -> logStats(args);
                case "log_recent_errors" -> logRecentErrors(args);
                default -> "ERROR: 未知工具 " + toolName;
            };
            sendToolResult(requestId, result);
        } catch (Exception e) {
            sendToolResult(requestId, "ERROR: " + e.getMessage());
        }
    }

    // ── 业务逻辑 ──

    private static String logQuery(JsonNode args) throws Exception {
        String serverHost = argStr(args, "serverHost");
        String logLevel   = argStr(args, "logLevel");
        int maxResults = Math.min(argInt(args, "maxResults", 20), MAX_RESULTS);
        int page       = Math.max(argInt(args, "page", 1), 1);
        int offset     = (page - 1) * maxResults;

        StringBuilder sql = new StringBuilder(
                "SELECT server_host, log_file, log_level, log_timestamp, " +
                "LEFT(log_message, 500) AS summary FROM server_logs WHERE 1=1");
        List<Object> paramsList = new ArrayList<>();

        if (serverHost != null && !serverHost.isBlank()) {
            sql.append(" AND server_host = ?"); paramsList.add(serverHost);
        }
        if (logLevel != null && !logLevel.isBlank()) {
            sql.append(" AND log_level = ?"); paramsList.add(logLevel.toUpperCase());
        }
        sql.append(" AND log_timestamp > ?");
        paramsList.add(System.currentTimeMillis() - 3600_000);  // 至少最近1小时

        sql.append(" ORDER BY log_timestamp DESC LIMIT ? OFFSET ?");
        paramsList.add(maxResults); paramsList.add(offset);

        List<Map<String, Object>> rows = query(sql.toString(), paramsList.toArray());
        String header = String.format("找到 %d 条, 第 %d 页 (每页 %d 条)\n",
                countSame(serverHost, logLevel), page, maxResults);
        return header + toPrettyTable(rows);
    }

    private static String logServers() throws Exception {
        List<Map<String, Object>> rows = query(
                "SELECT server_host, COUNT(*) AS total, " +
                "SUM(log_level='ERROR') AS errors, " +
                "SUM(log_level='WARN') AS warns, " +
                "SUM(log_level='INFO') AS infos, " +
                "MAX(log_timestamp) AS last_seen " +
                "FROM server_logs WHERE log_timestamp > ? " +
                "GROUP BY server_host ORDER BY last_seen DESC",
                System.currentTimeMillis() - 86400_000);
        return rows.isEmpty() ? "(暂无日志数据)" : toPrettyTable(rows);
    }

    private static String logStats(JsonNode args) throws Exception {
        String host = argStr(args, "serverHost");
        String sql = "SELECT server_host, log_level, COUNT(*) AS cnt FROM server_logs " +
                "WHERE log_timestamp > ?";
        List<Object> ps = new ArrayList<>();
        ps.add(System.currentTimeMillis() - 3600_000);
        if (host != null && !host.isBlank()) {
            sql += " AND server_host = ?"; ps.add(host);
        }
        sql += " GROUP BY server_host, log_level ORDER BY server_host, log_level";
        List<Map<String, Object>> rows = query(sql, ps.toArray());
        return rows.isEmpty() ? "(暂无数据)" : toPrettyTable(rows);
    }

    private static String logRecentErrors(JsonNode args) throws Exception {
        String serverHost = argStr(args, "serverHost");
        int maxResults = Math.min(argInt(args, "maxResults", 20), MAX_RESULTS);

        StringBuilder sql = new StringBuilder(
                "SELECT server_host, log_file, log_timestamp, " +
                "LEFT(log_message, 1000) AS summary FROM server_logs " +
                "WHERE log_level='ERROR' AND log_timestamp > ?");
        List<Object> ps = new ArrayList<>();
        ps.add(System.currentTimeMillis() - 24 * 3600_000L);
        if (serverHost != null && !serverHost.isBlank()) {
            sql.append(" AND server_host = ?"); ps.add(serverHost);
        }
        sql.append(" ORDER BY log_timestamp DESC LIMIT ?");
        ps.add(maxResults);
        return toPrettyTable(query(sql.toString(), ps.toArray()));
    }

    // ── JDBC 工具方法 ──

    private static List<Map<String, Object>> query(String sql, Object... params) throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++)
                        row.put(meta.getColumnLabel(c), rs.getObject(c));
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static int countSame(String serverHost, String logLevel) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM server_logs WHERE log_timestamp > ?");
        List<Object> ps = new ArrayList<>();
        ps.add(System.currentTimeMillis() - 3600_000);
        if (serverHost != null && !serverHost.isBlank()) { sql.append(" AND server_host = ?"); ps.add(serverHost); }
        if (logLevel != null && !logLevel.isBlank()) { sql.append(" AND log_level = ?"); ps.add(logLevel.toUpperCase()); }
        try (Connection conn = getConnection();
             PreparedStatement st = conn.prepareStatement(sql.toString())) {
            st.setQueryTimeout(2);
            for (int i = 0; i < ps.size(); i++) st.setObject(i + 1, ps.get(i));
            try (ResultSet rs = st.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private static String toPrettyTable(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "(无数据)";
        StringBuilder sb = new StringBuilder();
        String[] keys = rows.get(0).keySet().toArray(String[]::new);
        for (int i = 0; i < keys.length; i++) { sb.append(keys[i]); if (i < keys.length - 1) sb.append(" | "); }
        sb.append("\n").append("-".repeat(sb.length() - 1)).append("\n");
        for (var row : rows) {
            for (int i = 0; i < keys.length; i++) {
                Object v = row.get(keys[i]);
                String s = v == null ? "-" : v.toString();
                sb.append(s.length() > 60 ? s.substring(0, 60) + "..." : s);
                if (i < keys.length - 1) sb.append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String argStr(JsonNode args, String key) {
        JsonNode n = args.path(key);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
    private static int argInt(JsonNode args, String key, int def) {
        JsonNode n = args.path(key);
        return n.isMissingNode() || n.isNull() ? def : n.asInt(def);
    }

    private static Connection getConnection() throws Exception {
        String url = System.getenv("PERSISTENCE_URL");
        if (url == null || url.isBlank()) throw new RuntimeException("PERSISTENCE_URL 未配置");
        return DriverManager.getConnection(
                url + (url.contains("?") ? "&" : "?") + "connectTimeout=3000&socketTimeout=5000",
                System.getenv().getOrDefault("PERSISTENCE_USER", "root"),
                System.getenv().getOrDefault("PERSISTENCE_PASS", "123456"));
    }

    // ── JSON-RPC 响应 ──

    private static void send(Object id, JsonNode result) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id instanceof String s ? MAPPER.getNodeFactory().textNode(s) : null);
        msg.set("result", result);
        System.out.println(msg);
    }

    private static void sendToolResult(Object id, String text) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        send(id, result);
    }

    private static void sendError(Object id, int code, String message) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id instanceof String s ? MAPPER.getNodeFactory().textNode(s) : null);
        ObjectNode err = msg.putObject("error");
        err.put("code", code); err.put("message", message);
        System.out.println(msg);
    }
}
