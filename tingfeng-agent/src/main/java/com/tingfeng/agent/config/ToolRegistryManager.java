package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.DiagnoserAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具源全生命周期管理器。
 * 自建 executors Map, 标签/路由/重建全部数据驱动。
 */
@Component
public class ToolRegistryManager {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryManager.class);

    private final ChatModel model;
    private final TingFengProperties props;
    private final TingFengPersistenceProperties persistProps;
    private final DynamicPlannerHolder plannerHolder;

    /** Reporter Executor holder */
    private final DynamicReporterHolder reporterHolder;
    /** 所有活跃客户端: name → McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 客户端元数据: name → Meta */
    private final Map<String, ClientMeta> metas = new ConcurrentHashMap<>();
    /** 执行工具注册表: clientName → [toolName, ...] */
    private final Map<String, java.util.List<String>> executionTools = new ConcurrentHashMap<>();
    /** 所有 Executor: tag → holder */
    private final Map<String, DynamicDiagnoserHolder> diagnosers = new ConcurrentHashMap<>();
    /** 标签定义: tag → (描述, 依赖的 client names) */
    private final Map<String, TagDef> tagDefs = new ConcurrentHashMap<>();
    /** 内置客户端的原始环境变量, 用于重新注册时自动恢复 */
    private final Map<String, Map<String, String>> builtinEnvs = new ConcurrentHashMap<>();

    public record ClientMeta(String name, String mainClass, String jarPath,
                             boolean builtin, String tag, String tagDesc,
                             Map<String, String> env) {}
    record TagDef(String tag, String desc, List<String> requires) {}

    public ToolRegistryManager(ChatModel model,
                               TingFengProperties props,
                               TingFengPersistenceProperties persistProps,
                               DynamicPlannerHolder plannerHolder,
                               DynamicReporterHolder reporterHolder) {
        this.model = model;
        this.props = props;
        this.persistProps = persistProps;
        this.plannerHolder = plannerHolder;
        this.reporterHolder = reporterHolder;

        // 创建 6 个 Executor holder
        for (String tag : List.of("mysql", "redis", "cpu", "snapshot", "log", "full")) {
            diagnosers.put(tag, new DynamicDiagnoserHolder(placeholderDiagnoser()));
        }

        // 标签定义: tag → (描述, 依赖的 client names)
        tagDefs.put("mysql", new TagDef("mysql",
                "MySQL 诊断（连接数、慢查询、Buffer Pool 命中率、锁等待、主从复制、当前查询等）",
                List.of("mysql-mcp")));
        tagDefs.put("redis", new TagDef("redis",
                "Redis 诊断（内存使用、命中率、慢查询、大Key扫描等）",
                List.of("redis-mcp")));
        tagDefs.put("cpu", new TagDef("cpu",
                "CPU与JVM诊断（CPU负载、JVM内存/线程/死锁、GC统计、磁盘空间等）",
                List.of("cpu-mcp")));
        tagDefs.put("snapshot", new TagDef("snapshot",
                "探针快照诊断（方法调用历史、异常记录、慢调用、响应时间等）",
                List.of("snapshot-mcp")));
        tagDefs.put("log", new TagDef("log",
                "服务器日志诊断（错误日志、异常堆栈、系统日志查询等）",
                List.of("server-log-mcp")));

        // 执行工具注册（后续新增执行工具只需加一行）
        executionTools.put("redis-mcp", List.of("redis_set_expire", "redis_delete_key"));
    }

    /** 包装 McpClient — 从 tools/list 中过滤掉执行工具，Diagnoser LLM 看不到写操作 */
    private McpClient filterReadOnly(McpClient raw, Set<String> excludedTools) {
        return new McpClient() {
            public String key() { return raw.key(); }
            public List<ToolSpecification> listTools() {
                return raw.listTools().stream()
                        .filter(t -> !excludedTools.contains(t.name())).toList();
            }
            public List<ToolSpecification> listTools(InvocationContext c) { return listTools(); }
            public ToolExecutionResult executeTool(ToolExecutionRequest r) { return raw.executeTool(r); }
            public ToolExecutionResult executeTool(ToolExecutionRequest r, InvocationContext c) { return executeTool(r); }
            public List<McpResource> listResources() { return raw.listResources(); }
            public List<McpResource> listResources(InvocationContext c) { return listResources(); }
            public List<McpResourceTemplate> listResourceTemplates() { return raw.listResourceTemplates(); }
            public List<McpResourceTemplate> listResourceTemplates(InvocationContext c) { return listResourceTemplates(); }
            public McpReadResourceResult readResource(String u) { return raw.readResource(u); }
            public McpReadResourceResult readResource(String u, InvocationContext c) { return readResource(u); }
            public void subscribeToResource(String u) { raw.subscribeToResource(u); }
            public void unsubscribeFromResource(String u) { raw.unsubscribeFromResource(u); }
            public List<McpPrompt> listPrompts() { return raw.listPrompts(); }
            public McpGetPromptResult getPrompt(String n, Map<String,Object> a) { return raw.getPrompt(n, a); }
            public void checkHealth() { raw.checkHealth(); }
            public void setRoots(List<McpRoot> r) { raw.setRoots(r); }
            public void close() throws Exception { raw.close(); }
        };
    }

    private DiagnoserAgent placeholderDiagnoser() {
        return AiServices.builder(DiagnoserAgent.class).chatModel(model).build();
    }

    // ── 启动 ──

    @PostConstruct
    public void init() {
        // persistence env (共享给所有 MCP 子进程做工具调用日志)
        Map<String, String> persistEnv = persistProps.isConfigured()
                ? Map.of("PERSISTENCE_URL", persistProps.getUrl(),
                         "PERSISTENCE_USER", persistProps.getUsername(),
                         "PERSISTENCE_PASS", persistProps.getPassword())
                : null;

        registerBuiltinClient("mysql-mcp", "com.tingfeng.agent.mcp.MySqlMcpServer", mergeEnv(
                Map.of("MYSQL_HOST", props.getMysql().getHost(),
                       "MYSQL_PORT", String.valueOf(props.getMysql().getPort()),
                       "MYSQL_USER", props.getMysql().getUser(),
                       "MYSQL_PASS", props.getMysql().getPass(),
                       "MYSQL_DB", props.getMysql().getDb()), persistEnv));

        registerBuiltinClient("redis-mcp", "com.tingfeng.agent.mcp.RedisMcpServer", mergeEnv(
                Map.of("REDIS_HOST", props.getRedis().getHost(),
                       "REDIS_PORT", String.valueOf(props.getRedis().getPort()),
                       "REDIS_PASSWORD", props.getRedis().getPassword() != null
                               ? props.getRedis().getPassword() : "",
                       "REDIS_DATABASE", String.valueOf(props.getRedis().getDatabase())),
                persistEnv));

        registerBuiltinClient("cpu-mcp", "com.tingfeng.agent.mcp.CpuMcpServer", persistEnv != null
                ? persistEnv : Map.of());

        if (persistProps.isConfigured()) {
            registerBuiltinClient("snapshot-mcp", "com.tingfeng.agent.mcp.SnapshotMcpServer", persistEnv);
            registerBuiltinClient("server-log-mcp", "com.tingfeng.agent.mcp.ServerLogMcpServer", persistEnv);
        }

        rebuildAll();
        rebuildPlanner();
        rebuildReporter();
        log.info("ToolRegistryManager 初始化完成: clients={}, activeTags={}",
                clients.size(), activeTags());
    }

    private Map<String, String> mergeEnv(Map<String, String> primary, Map<String, String> secondary) {
        if (secondary == null || secondary.isEmpty()) return primary;
        Map<String, String> merged = new java.util.LinkedHashMap<>(primary);
        merged.putAll(secondary);
        return merged;
    }

    private void registerBuiltinClient(String name, String mainClass, Map<String, String> env) {
        builtinEnvs.put(name, env);
        McpClient client = launchClient(name, mainClass, null, env);
        if (client != null) {
            clients.put(name, client);
            metas.put(name, new ClientMeta(name, mainClass, null, true, null, null, env));
        } else {
            log.warn("内置工具源 '{}' 启动失败, 对应标签将不可用", name);
        }
    }

    // ── 路由 ──

    /** 按标签查找 Executor, 找不到回退 full */
    public DiagnoserAgent route(String tag) {
        if (tag == null) return diagnosers.get("full");
        DynamicDiagnoserHolder h = diagnosers.get(tag.toLowerCase());
        return h != null ? h : diagnosers.get("full");
    }

    // ── 注册 / 注销 ──

    public Map<String, Object> register(String name, String mainClass, String jarPath,
                                         String tag, String tagDesc,
                                         Map<String, String> env) {
        if (clients.containsKey(name)) {
            return Map.of("ok", false, "error", "工具源 '" + name + "' 已存在, 请先注销");
        }
        if (tag != null && tagDefs.containsKey(tag) && isTagActive(tag)) {
            return Map.of("ok", false, "error", "标签 '" + tag + "' 已被活动工具源占用");
        }

        if ((env == null || env.isEmpty()) && builtinEnvs.containsKey(name)) {
            env = builtinEnvs.get(name);
        }

        McpClient client = launchClient(name, mainClass, jarPath, env);
        if (client == null) {
            return Map.of("ok", false, "error", "子进程启动失败");
        }

        clients.put(name, client);
        metas.put(name, new ClientMeta(name, mainClass, jarPath, false, tag, tagDesc, env));

        if (tag != null && !tag.isBlank()) {
            if (!diagnosers.containsKey(tag)) {
                diagnosers.put(tag, new DynamicDiagnoserHolder(placeholderDiagnoser()));
            }
            if (!tagDefs.containsKey(tag)) {
                tagDefs.put(tag, new TagDef(tag,
                        tagDesc != null ? tagDesc : tag + " 诊断工具",
                        List.of(name)));
            }
        }

        rebuildAll();
        rebuildPlanner();
        rebuildReporter();
        log.info("工具源 '{}' 注册成功, tag={}", name, tag);
        return Map.of("ok", true, "name", name, "tag", tag,
                "totalClients", clients.size(), "activeTags", activeTags());
    }

    public Map<String, Object> unregister(String name) {
        McpClient client = clients.remove(name);
        ClientMeta meta = metas.remove(name);
        if (client == null) {
            return Map.of("ok", false, "error", "工具源 '" + name + "' 未找到");
        }

        try { if (client instanceof Closeable c) c.close(); }
        catch (Exception e) { log.warn("关闭 McpClient 失败: {}", e.getMessage()); }

        if (meta != null && meta.tag != null && !isBuiltinTag(meta.tag)) {
            tagDefs.remove(meta.tag);
            diagnosers.remove(meta.tag);
        }

        rebuildAll();
        rebuildPlanner();
        rebuildReporter();
        log.info("工具源 '{}' 已注销", name);
        return Map.of("ok", true, "name", name, "totalClients", clients.size(),
                "activeTags", activeTags());
    }

    // ── 查看 ──

    public Map<String, Object> listToolSources() {
        List<Map<String, Object>> clientList = new ArrayList<>();
        for (var entry : metas.entrySet()) {
            ClientMeta m = entry.getValue();
            var item = new LinkedHashMap<String, Object>();
            item.put("name", m.name);
            item.put("mainClass", m.mainClass);
            item.put("type", m.builtin ? "内置" : "动态");
            item.put("active", clients.containsKey(m.name));
            if (m.tag != null) item.put("tag", m.tag);
            if (m.jarPath != null) item.put("jarPath", m.jarPath);
            clientList.add(item);
        }

        List<Map<String, Object>> tagList = new ArrayList<>();
        for (var entry : tagDefs.entrySet()) {
            TagDef def = entry.getValue();
            boolean active = isTagActive(def);
            var item = new LinkedHashMap<String, Object>();
            item.put("tag", def.tag);
            item.put("desc", def.desc);
            item.put("active", active);
            item.put("requires", def.requires);
            if (!active) {
                List<String> missing = new ArrayList<>();
                for (String r : def.requires) if (!clients.containsKey(r)) missing.add(r);
                item.put("missingClients", missing);
            }
            tagList.add(item);
        }

        return Map.of(
                "totalClients", clients.size(),
                "clients", clientList,
                "tags", tagList,
                "holders", holderHashes(),
                "plannerDelegateHash", plannerHolder.delegateHash()
        );
    }

    // ── 测试 ──

    public Map<String, Object> test(String question, String target) {
        DynamicDiagnoserHolder holder = (target != null && !target.isBlank())
                ? diagnosers.getOrDefault(target, diagnosers.get("full"))
                : diagnosers.get("full");
        long start = System.currentTimeMillis();
        try {
            String answer = holder.execute(question);
            return Map.of("ok", true, "question", question, "target", target,
                    "answer", answer, "elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return Map.of("ok", false, "question", question, "target", target,
                    "error", e.getMessage(), "elapsedMs", System.currentTimeMillis() - start);
        }
    }

    public McpToolProvider getFullToolProvider() {
        List<McpClient> all = new ArrayList<>(clients.values());
        return McpToolProvider.builder()
                .mcpClients(all.toArray(McpClient[]::new))
                .failIfOneServerFails(false)
                .build();
    }

    public McpClient getSnapshotClient() { return clients.get("snapshot-mcp"); }

    /** 获取指定 client（用于直调 MCP 工具，不经过 LLM） */
    public McpClient getClient(String name) { return clients.get(name); }

    /** 直接调用 MCP 工具（不走 Executor/LLM），用于批准后的执行操作 */
    public Map<String, Object> executeAction(String clientName, String toolName,
                                              Map<String, Object> args) {
        McpClient client = clients.get(clientName);
        if (client == null) return Map.of("ok", false, "error", "工具源 '" + clientName + "' 不存在");

        long start = System.currentTimeMillis();
        try {
            // 通过 McpClient 直调 tools/call
            var request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(args))
                    .build();
            var result = client.executeTool(request);
            String output = result != null ? result.resultText() : "OK";
            int elapsed = (int)(System.currentTimeMillis() - start);
            logExecution(clientName, toolName, args.toString(), output, elapsed, true, null);
            return Map.of("ok", true, "toolName", toolName,
                    "output", output, "elapsedMs", elapsed);
        } catch (Exception e) {
            int elapsed = (int)(System.currentTimeMillis() - start);
            logExecution(clientName, toolName, args.toString(), null, elapsed, false, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage(), "elapsedMs", elapsed);
        }
    }

    private void logExecution(String clientName, String toolName, String args,
                               String result, int elapsedMs, boolean success, String errorMsg) {
        Map<String, String> persistEnv = builtinEnvs.get(clientName);
        if (persistEnv == null || !persistEnv.containsKey("PERSISTENCE_URL")) {
            // try snapshot-mcp's env (shared persistence)
            persistEnv = builtinEnvs.get("snapshot-mcp");
        }
        if (persistEnv == null) return;
        String url = persistEnv.get("PERSISTENCE_URL");
        if (url == null || url.isBlank()) return;

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                url + (url.contains("?") ? "&" : "?") + "connectTimeout=1000&socketTimeout=1000",
                persistEnv.getOrDefault("PERSISTENCE_USER", "root"),
                persistEnv.getOrDefault("PERSISTENCE_PASS", "123456"));
             java.sql.PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO tingfeng_execution_log" +
                     " (action_id, tool_name, args, result, success, executed_at)" +
                     " VALUES (?,?,?,?,?,?)")) {
            pstmt.setQueryTimeout(1);
            pstmt.setString(1, toolName + "-" + System.currentTimeMillis());
            pstmt.setString(2, toolName);
            pstmt.setString(3, args != null && args.length() > 2000 ? args.substring(0, 2000) : args);
            pstmt.setString(4, result != null && result.length() > 1000 ? result.substring(0, 1000) : result);
            pstmt.setInt(5, success ? 1 : 0);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (Exception ignored) {}
    }

    // ── 内部: 标签状态 ──

    private boolean isTagActive(TagDef def) {
        for (String r : def.requires) {
            if (!clients.containsKey(r)) return false;
        }
        return true;
    }

    private boolean isTagActive(String tag) {
        TagDef def = tagDefs.get(tag);
        return def != null && isTagActive(def);
    }

    private boolean isBuiltinTag(String tag) {
        return tagDefs.containsKey(tag);
    }

    private List<String> activeTags() {
        List<String> list = new ArrayList<>();
        for (var entry : tagDefs.entrySet()) {
            if (isTagActive(entry.getValue())) list.add(entry.getKey());
        }
        return list;
    }

    private List<TagDef> activeTagDefs() {
        return tagDefs.values().stream().filter(this::isTagActive).toList();
    }

    // ── 子进程启动 ──

    private McpClient launchClient(String name, String mainClass,
                                    String jarPath, Map<String, String> env) {
        try {
            String classpath = System.getProperty("java.class.path");
            if (jarPath != null && !jarPath.isBlank()) {
                classpath = classpath + java.io.File.pathSeparator + jarPath.trim();
            }
            String javaExe = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");

            List<String> cmd = List.of(javaExe, "-cp", classpath, mainClass);
            log.info("启动工具源子进程: name={}, mainClass={}", name, mainClass);
            return DefaultMcpClient.builder()
                    .key("tool-" + name)
                    .transport(StdioMcpTransport.builder()
                            .command(cmd)
                            .environment(env != null ? env : Map.of())
                            .logEvents(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("工具源 '{}' 启动失败: {}", name, e.getMessage());
            return null;
        }
    }

    // ── 重建 ──

    private void rebuildAll() {
        for (TagDef def : tagDefs.values()) {
            DynamicDiagnoserHolder holder = diagnosers.get(def.tag);
            if (holder == null) continue;
            if (isTagActive(def)) {
                rebuildOne(holder, def.tag, def.requires);
            } else {
                rebuildOne(holder, def.tag, List.of());
            }
        }
        // full: 所有活跃 client
        rebuildOne(diagnosers.get("full"), "full", new ArrayList<>(clients.keySet()));
    }

    private void rebuildOne(DynamicDiagnoserHolder holder, String label, List<String> requiredNames) {
        List<McpClient> list = new ArrayList<>();
        // 收集所有执行工具名，Diagnoser 不能看到写工具
        Set<String> execToolNames = new HashSet<>();
        for (var tl : executionTools.values()) execToolNames.addAll(tl);

        for (String name : requiredNames) {
            McpClient c = clients.get(name);
            if (c != null) {
                list.add(execToolNames.isEmpty() ? c : filterReadOnly(c, execToolNames));
            }
        }

        DiagnoserAgent agent;
        if (list.isEmpty()) {
            agent = placeholderDiagnoser();
        } else {
            agent = AiServices.builder(DiagnoserAgent.class)
                    .chatModel(model)
//                    .maxSequentialToolInvocations(5)
                    .toolProvider(McpToolProvider.builder()
                            .mcpClients(list.toArray(McpClient[]::new))
                            .failIfOneServerFails(false)
                            .build())
                    .build();
        }
        holder.swap(agent);
        log.info("{}-Diagnoser 已重建, clients={}", label, list.size());
    }

    private void rebuildPlanner() {
        List<TagDef> active = activeTagDefs();

        StringBuilder msg = new StringBuilder("""
                你是一个运维诊断规划师。根据用户问题，输出结构化的排查计划。

                如果用户问题与IT运维、Redis、MySQL、服务器诊断无关（比如闲聊、育儿、医疗等），返回空数组 [].
                绝不将无关问题强行归类到已有标签下——cpu/mysql/redis/snapshot 标签只能用于对应领域的真实运维排查。

                每条排查任务由 task (任务描述) 和 tags (工具标签列表) 组成。
                每个任务通常只有一个标签，不同工具的问题拆成独立步骤。

                """);

        if (active.isEmpty()) {
            msg.append("当前无可用诊断工具，请先注册工具源。\n");
        } else {
            msg.append("当前可用的标签及对应工具领域：\n");
            for (TagDef def : active) {
                msg.append("  \"").append(def.tag).append("\" → ").append(def.desc).append("\n");
            }
        }
        msg.append("""

                规则：
                1. 首先判断问题是否与 IT 运维相关。如不相关，直接返回 [] 并停止
                2. 只有与运维相关的问题才生成排查计划
                3. task 描述要具体，指明要检查什么指标
                4. 不超过5个步骤
                5. 严格返回 JSON 数组格式，只输出 JSON，不要其他内容
                6. 只能使用上面列出的标签，不要编造标签名
                7. 绝不给无关问题(医学/育儿/法律等)打上运维标签，[] 就是正确回答
                """);

        if (!active.isEmpty()) {
            msg.append("\n输出格式示例：\n[\n");
            int n = Math.min(active.size(), 3);
            for (int i = 0; i < n; i++) {
                msg.append("  {\"task\":\"...\",\"tags\":[\"").append(active.get(i).tag).append("\"]}");
                if (i < n - 1) msg.append(",");
                msg.append("\n");
            }
            msg.append("]\n");
        }

        PlannerAgent newAgent = AiServices.builder(PlannerAgent.class)
                .chatModel(model)
                .systemMessage(msg.toString())
                .build();
        plannerHolder.swap(newAgent);
        log.info("Planner 已重建, activeTags={}", activeTags());
    }

    private void rebuildReporter() {
        StringBuilder msg = new StringBuilder("""
                你是一个资深运维诊断分析师。根据用户问题、排查计划和排查笔记，输出最终诊断报告。

                注意：报告的第一行必须是 "## 一、问题概述"，严禁以任何开场白或自我介绍开头（如"好的"、"作为"等），直接输出报告内容，不需要任何前导文字。

                报告格式：
                ## 一、问题概述
                ## 二、排查过程与发现
                ## 三、根因分析
                ## 四、修复建议
                ## 五、总结
                ## 六、可执行操作

                规则：
                1. 基于排查笔记的真实数据来分析，不要猜测
                2. 如果排查不充分，在报告中指出信息缺口
                3. 修复建议要具体可执行
                4. 在「六、可执行操作」中使用以下 JSON 格式列出可直接执行的修复动作（如果无操作则写「无」）：

                """);

        // 用第一个 client 名构建示例
        String exampleClient = executionTools.isEmpty() ? "no-client" : executionTools.keySet().iterator().next();
        msg.append("```json\n{\n  \"actions\": [\n");
        msg.append("    {\"id\":\"a1\",\"client\":\"").append(exampleClient)
                .append("\",\"tool\":\"redis_set_expire\",\n")
                .append("     \"args\":{\"key\":\"具体Key名\",\"ttlSeconds\":3600},\"desc\":\"给 Key 设过期\",\"risk\":\"safe\"},\n");
        msg.append("    {\"id\":\"a2\",\"client\":\"").append(exampleClient)
                .append("\",\"tool\":\"redis_delete_key\",\n")
                .append("     \"args\":{\"key\":\"具体Key名\"},\"desc\":\"删除无用Key\",\"risk\":\"dangerous\"}\n");
        msg.append("  ]\n}\n```\n\n");

        msg.append("当前可用执行工具（client 必须严格使用下面列出的名称）：\n");

        if (executionTools.isEmpty()) {
            msg.append("  无\n");
        } else {
            for (var entry : executionTools.entrySet()) {
                String client = entry.getKey();
                for (String tool : entry.getValue()) {
                    String desc = switch (tool) {
                        case "redis_set_expire" -> "设 Key 过期时间(秒), args: key, ttlSeconds";
                        case "redis_delete_key" -> "删除 Key (有备份), args: key";
                        default -> "args 见工具定义";
                    };
                    msg.append("  client=\"").append(client).append("\", tool=\"")
                            .append(tool).append("\" — ").append(desc).append("\n");
                }
            }
        }

        msg.append("""

                重要：
                - client 字段必须严格用上面列出的名称，不要编造
                - 只能列出上面已有工具的 action
                - 不要编造配置修改、服务器操作等不存在的工具
                risk: safe=安全  caution=需关注  dangerous=不可逆需确认
                """);

        ReporterAgent newAgent = AiServices.builder(ReporterAgent.class)
                .chatModel(model)
                .systemMessage(msg.toString())
                .build();
        reporterHolder.swap(newAgent);
        log.info("Reporter 已重建, executionTools={}", executionTools.keySet());
    }

    // ── 辅助 ──

    private Map<String, Object> holderHashes() {
        Map<String, Object> h = new LinkedHashMap<>();
        for (var e : diagnosers.entrySet()) {
            h.put(e.getKey(), e.getValue().delegateHash());
        }
        return h;
    }
}
