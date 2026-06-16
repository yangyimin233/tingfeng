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
import java.util.List;

/**
 * 探针快照 MCP Resource Server — 通过 stdio 暴露 tingfeng_snapshot 表数据。
 * <p>
 * Resource URI:
 *   snapshot://errors   — 最近错误 (默认 30min)
 *   snapshot://slow     — 慢调用 (默认 rt > 1000ms, 30min)
 *   snapshot://method/NAME — 指定方法最近调用
 * <p>
 * 环境变量:
 *   PERSISTENCE_URL  (必填)
 *   PERSISTENCE_USER (默认 root)
 *   PERSISTENCE_PASS (默认 123456)
 */
public class SnapshotMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode request;
                try { request = MAPPER.readTree(line); }
                catch (Exception e) { System.err.println("[SnapshotMcp] invalid JSON"); continue; }

                String method = request.path("method").asText();
                Object id = request.has("id") && !request.get("id").isNull() ? request.get("id").asText() : null;
                if (id == null) continue;
                JsonNode params = request.path("params");

                switch (method) {
                    case "initialize" -> sendInit(id);
                    case "tools/list"   -> sendToolList(id);
                    case "tools/call"   -> sendToolCall(id, params);
                    case "resources/list" -> sendResources(id);
                    case "resources/read" -> sendResourceRead(id, params);
                    default -> sendError(id, -32601, "Unknown: " + method);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ── MCP 握手 ──

    private static void sendInit(Object id) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("resources");
        caps.putObject("tools");          // 声明 tool 能力
        result.putObject("protocolVersion").put("version", "2025-03-26");
        send(id, result);
    }

    // ── Tool List + Tool Call ──

    private static void sendToolList(Object id) {
        ArrayNode tools = MAPPER.createArrayNode();
        ObjectNode tool = tools.addObject();
        tool.put("name", "query_snapshots");
        tool.put("description", "查询探针快照表, 获取方法调用历史。参数: method(可选, 方法名模糊过滤), errorOnly(可选, true=仅查失败), limit(可选, 默认20 最大50)");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("method").put("type", "string").put("description", "方法名模糊匹配, 可选");
        props.putObject("errorOnly").put("type", "boolean").put("description", "仅查错误记录, 可选");
        props.putObject("limit").put("type", "integer").put("description", "返回条数, 默认20 最大50");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        send(id, result);
    }

    private static void sendToolCall(Object id, JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode args = params.path("arguments");
        long start = System.currentTimeMillis();
        try {
            String text = toolName.equals("query_snapshots") ? doQuerySnapshots(args) : "Unknown tool: " + toolName;
            logToolCall(toolName, args.toString(), text, (int)(System.currentTimeMillis() - start), true, null);
            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", text);
            send(id, result);
        } catch (Exception e) {
            logToolCall(toolName, args.toString(), null, (int)(System.currentTimeMillis() - start), false, e.getMessage());
            sendError(id, -32000, e.getMessage());
        }
    }

    private static String doQuerySnapshots(JsonNode args) {
        String method = args.has("method") && !args.get("method").isNull() ? args.get("method").asText() : null;
        boolean errorOnly = args.has("errorOnly") && args.get("errorOnly").asBoolean();
        int limit = args.has("limit") && !args.get("limit").isNull()
                ? Math.min(args.get("limit").asInt(), 50) : 20;

        StringBuilder sql = new StringBuilder(
                "SELECT method_name, request_time, rt_ms, success, error_msg FROM tingfeng_snapshot WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (method != null && !method.isBlank()) {
            sql.append(" AND method_name LIKE ?");
            params.add("%" + method + "%");
        }
        if (errorOnly) sql.append(" AND success=0");
        sql.append(" ORDER BY request_time DESC LIMIT ?");
        params.add(limit);

        try {
            return queryToJson(sql.toString(), params.toArray());
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    // ── Resource List ──

    private static void sendResources(Object id) {
        ArrayNode resources = MAPPER.createArrayNode();
        addResource(resources, "snapshot://errors", "近期错误列表",
                "最近 30 分钟内方法调用失败记录 (success=0)");
        addResource(resources, "snapshot://slow", "慢调用列表",
                "最近 30 分钟内响应时间超过 1000ms 的方法调用");
        addResource(resources, "snapshot://method/{name}", "指定方法调用",
                "查询某个方法最近 60 分钟的调用记录, name 为方法全名如 LoginController#login");
        addResource(resources, "snapshot://recent?limit={n}", "最近全部调用",
                "最近 30 分钟的所有方法调用记录, limit 控制返回条数(默认10)");

        ObjectNode result = MAPPER.createObjectNode();
        result.set("resources", resources);
        send(id, result);
    }

    private static void addResource(ArrayNode arr, String uri, String name, String desc) {
        ObjectNode r = arr.addObject();
        r.put("uri", uri);
        r.put("name", name);
        r.put("description", desc);
        r.put("mimeType", "application/json");
    }

    // ── Resource Read ──

    private static void sendResourceRead(Object id, JsonNode params) {
        String uri = params.path("uri").asText();
        try {
            String json;
            if (uri.startsWith("snapshot://method/")) {
                String name = uri.substring("snapshot://method/".length());
                String sql = "SELECT method_name, args, return_value, request_time, rt_ms, success, error_msg " +
                       "FROM tingfeng_snapshot WHERE method_name = ? " +
                       "AND request_time >" + unixMinus(60) + " ORDER BY request_time DESC LIMIT 20";
                json = queryToJson(sql, name);
            } else {
                json = queryToJson(parseUri(uri));
            }
            ArrayNode content = MAPPER.createArrayNode();
            ObjectNode item = content.addObject();
            item.put("uri", uri);
            item.put("mimeType", "application/json");
            item.put("text", json);
            ObjectNode result = MAPPER.createObjectNode();
            result.set("contents", content);
            send(id, result);
        } catch (Exception e) {
            sendError(id, -32000, e.getMessage());
        }
    }

    /** URI 路由 → SQL (不含用户输入参数, 参数由 sendResourceRead 单独处理) */
    private static String parseUri(String uri) {
        if ("snapshot://errors".equals(uri)) {
            return "SELECT method_name, args, return_value, request_time, rt_ms, error_msg " +
                   "FROM tingfeng_snapshot WHERE success=0 AND request_time >" +
                   unixMinus(30) + " ORDER BY request_time DESC LIMIT 30";
        }
        if ("snapshot://slow".equals(uri)) {
            return "SELECT method_name, request_time, rt_ms, success " +
                   "FROM tingfeng_snapshot WHERE rt_ms > 1000 AND request_time >" +
                   unixMinus(30) + " ORDER BY rt_ms DESC LIMIT 30";
        }
        if (uri.startsWith("snapshot://recent")) {
            int limit = 10;
            if (uri.contains("limit=")) {
                try { limit = Integer.parseInt(uri.substring(uri.indexOf("limit=") + 6).replaceAll("[^0-9]", "")); }
                catch (NumberFormatException ignored) {}
                limit = Math.min(limit, 50);
            }
            return "SELECT method_name, request_time, rt_ms, success, error_msg " +
                   "FROM tingfeng_snapshot WHERE request_time >" + unixMinus(30) +
                   " ORDER BY request_time DESC LIMIT " + limit;
        }
        throw new IllegalArgumentException("未知资源: " + uri);
    }

    // ── 查询转 JSON (PreparedStatement 防注入) ──

    private static String queryToJson(String sql, Object... params) throws Exception {
        try (Connection conn = getPersistenceConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setQueryTimeout(3);
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                ArrayNode rows = MAPPER.createArrayNode();

                while (rs.next()) {
                    ObjectNode row = MAPPER.createObjectNode();
                    for (int i = 1; i <= cols; i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof Number n) {
                            row.put(meta.getColumnName(i), n.doubleValue());
                        } else if (val instanceof Boolean b) {
                            row.put(meta.getColumnName(i), b);
                        } else {
                            row.put(meta.getColumnName(i), val != null ? val.toString() : null);
                        }
                    }
                    rows.add(row);
                }
                return MAPPER.writeValueAsString(rows);
            }
        }
    }

    private static Connection getPersistenceConnection() throws Exception {
        String url = env("PERSISTENCE_URL", null);
        if (url == null || url.isBlank()) throw new IllegalStateException("PERSISTENCE_URL 未配置");
        String user = env("PERSISTENCE_USER", "root");
        String pass = env("PERSISTENCE_PASS", "123456");
        String sep = url.contains("?") ? "&" : "?";
        return DriverManager.getConnection(url + sep + "connectTimeout=3000&socketTimeout=3000", user, pass);
    }

    private static long unixMinus(int minutes) {
        return (System.currentTimeMillis() - minutes * 60_000L);
    }

    // ── JSON-RPC ──

    private static void send(Object id, ObjectNode result) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.put("id", String.valueOf(id));
        resp.set("result", result);
        System.out.println(resp);
        System.out.flush();
    }

    private static void sendError(Object id, int code, String msg) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.put("id", String.valueOf(id));
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", msg);
        System.out.println(resp);
        System.out.flush();
    }

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

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
