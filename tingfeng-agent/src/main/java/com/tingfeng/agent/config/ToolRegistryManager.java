package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具源全生命周期管理器。
 * 内置 + 动态注册的工具源统一管理, 标签/路由/重建全部数据驱动。
 */
@Component
public class ToolRegistryManager {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryManager.class);

    private final ChatModel model;
    private final TingFengProperties props;
    private final TingFengPersistenceProperties persistProps;
    private final DynamicPlannerHolder plannerHolder;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;

    /** 所有活跃客户端: name → McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 客户端元数据: name → Meta */
    private final Map<String, ClientMeta> metas = new ConcurrentHashMap<>();

    /** 标签定义: tag → (holder, description, required client names) */
    private final Map<String, TagDef> tagDefs = new LinkedHashMap<>();
    /** fullExecutor — 兜底路由, 不参与 tagDefs */
    private final DynamicExecutorHolder fullHolder;
    /** 内置客户端的原始环境变量, 用于重新注册时自动恢复 */
    private final Map<String, Map<String, String>> builtinEnvs = new ConcurrentHashMap<>();

    public record ClientMeta(String name, String mainClass, String jarPath,
                             boolean builtin, String tag, String tagDesc,
                             Map<String, String> env) {}
    record TagDef(String tag, String desc, DynamicExecutorHolder holder, List<String> requires) {}

    public ToolRegistryManager(ChatModel model,
                               TingFengProperties props,
                               TingFengPersistenceProperties persistProps,
                               @Qualifier("mysqlExecutor") DynamicExecutorHolder mysqlHolder,
                               @Qualifier("redisExecutor") DynamicExecutorHolder redisHolder,
                               @Qualifier("systemExecutor") DynamicExecutorHolder cpuHolder,
                               @Qualifier("snapshotExecutor") DynamicExecutorHolder snapshotHolder,
                               @Qualifier("fullExecutor") DynamicExecutorHolder fullHolder,
                               DynamicPlannerHolder plannerHolder,
                               @Value("${spring.data.redis.host:localhost}") String redisHost,
                               @Value("${spring.data.redis.port:6379}") int redisPort,
                               @Value("${spring.data.redis.password:}") String redisPassword,
                               @Value("${spring.data.redis.database:0}") int redisDatabase) {
        this.model = model;
        this.props = props;
        this.persistProps = persistProps;
        this.fullHolder = fullHolder;
        this.plannerHolder = plannerHolder;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.redisDatabase = redisDatabase;

        // 内置标签定义: tag → (holder, Planner 描述, 依赖的 client names)
        tagDefs.put("mysql", new TagDef("mysql",
                "MySQL 诊断（连接数、慢查询、Buffer Pool 命中率、锁等待、主从复制、当前查询等）",
                mysqlHolder, List.of("mysql-mcp")));
        tagDefs.put("redis", new TagDef("redis",
                "Redis 诊断（内存使用、命中率、慢查询、大Key扫描等）",
                redisHolder, List.of("redis-mcp")));
        tagDefs.put("cpu", new TagDef("cpu",
                "CPU与JVM诊断（CPU负载、JVM内存/线程/死锁、GC统计、磁盘空间等）",
                cpuHolder, List.of("cpu-mcp")));
        tagDefs.put("snapshot", new TagDef("snapshot",
                "探针快照诊断（方法调用历史、异常记录、慢调用、响应时间等）",
                snapshotHolder, List.of("snapshot-mcp")));
    }

    // ── 启动时自动注册内置客户端 ──

    @PostConstruct
    public void init() {
        registerBuiltinClient("mysql-mcp", "com.tingfeng.agent.mcp.MySqlMcpServer",
                Map.of("MYSQL_HOST", props.getMysql().getHost(),
                       "MYSQL_PORT", String.valueOf(props.getMysql().getPort()),
                       "MYSQL_USER", props.getMysql().getUser(),
                       "MYSQL_PASS", props.getMysql().getPass(),
                       "MYSQL_DB", props.getMysql().getDb()));

        registerBuiltinClient("redis-mcp", "com.tingfeng.agent.mcp.RedisMcpServer",
                Map.of("REDIS_HOST", redisHost,
                       "REDIS_PORT", String.valueOf(redisPort),
                       "REDIS_PASSWORD", redisPassword != null ? redisPassword : "",
                       "REDIS_DATABASE", String.valueOf(redisDatabase)));

        registerBuiltinClient("cpu-mcp", "com.tingfeng.agent.mcp.CpuMcpServer", Map.of());

        if (persistProps.isConfigured()) {
            registerBuiltinClient("snapshot-mcp", "com.tingfeng.agent.mcp.SnapshotMcpServer",
                    Map.of("PERSISTENCE_URL", persistProps.getUrl(),
                           "PERSISTENCE_USER", persistProps.getUsername(),
                           "PERSISTENCE_PASS", persistProps.getPassword()));
        }

        rebuildAll();
        rebuildPlanner();
        log.info("ToolRegistryManager 初始化完成: clients={}, activeTags={}",
                clients.size(), activeTags());
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

    public ExecutorAgent route(List<String> tags, ExecutorAgent fallback) {
        if (tags == null || tags.size() != 1) return fallback;
        String tag = tags.get(0).toLowerCase();

        TagDef def = tagDefs.get(tag);
        if (def != null) {
            return def.holder; // 即使 inactive 也用自己的空 Executor, 不乱投 full
        }
        return fallback;
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

        // 如果重新注册已删除的内置工具源, 自动恢复原始 env 配置
        if ((env == null || env.isEmpty()) && builtinEnvs.containsKey(name)) {
            env = builtinEnvs.get(name);
        }

        McpClient client = launchClient(name, mainClass, jarPath, env);
        if (client == null) {
            return Map.of("ok", false, "error", "子进程启动失败");
        }

        clients.put(name, client);
        metas.put(name, new ClientMeta(name, mainClass, jarPath, false, tag, tagDesc, env));

        // 如果指定了 tag → 注册到 tagDefs + 创建专属 Executor
        if (tag != null && !tag.isBlank()) {
            DynamicExecutorHolder tagHolder;
            TagDef existing = tagDefs.get(tag);
            if (existing != null) {
                // 内置标签重新激活: 复用已有的 holder
                tagHolder = existing.holder;
                log.info("标签 '{}' 重新激活 (requires={})", tag, existing.requires);
            } else {
                // 全新动态标签
                tagHolder = new DynamicExecutorHolder(buildOne(client));
                tagDefs.put(tag, new TagDef(tag,
                        tagDesc != null ? tagDesc : tag + " 诊断工具",
                        tagHolder, List.of(name)));
            }
        }

        rebuildAll();
        rebuildPlanner();
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

        // 清理动态标签
        if (meta != null && meta.tag != null && !isBuiltinTag(meta.tag)) {
            tagDefs.remove(meta.tag);
        }

        rebuildAll();
        rebuildPlanner();
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
                // 列出哪些 client 缺失
                List<String> missing = new ArrayList<>();
                for (String r : def.requires) {
                    if (!clients.containsKey(r)) missing.add(r);
                }
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
        DynamicExecutorHolder holder;
        if (target != null && !target.isBlank()) {
            TagDef def = tagDefs.get(target);
            holder = (def != null && isTagActive(def)) ? def.holder : fullHolder;
        } else {
            holder = fullHolder;
        }
        long start = System.currentTimeMillis();
        String answer;
        try {
            answer = holder.execute(question);
        } catch (Exception e) {
            return Map.of("ok", false, "question", question, "target", target,
                    "error", e.getMessage(), "elapsedMs", System.currentTimeMillis() - start);
        }
        return Map.of("ok", true, "question", question, "target", target,
                "answer", answer, "elapsedMs", System.currentTimeMillis() - start);
    }

    public McpClient getSnapshotClient() { return clients.get("snapshot-mcp"); }

    // ── 内部: 是否活动 ──

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
        // 内置标签: 在构造函数中注册的, 不是通过 register() 动态添加的
        return tagDefs.containsKey(tag) && tagDefs.get(tag) != null;
    }

    private List<String> activeTags() {
        List<String> list = new ArrayList<>();
        for (var entry : tagDefs.entrySet()) {
            if (isTagActive(entry.getValue())) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    private List<TagDef> activeTagDefs() {
        List<TagDef> list = new ArrayList<>();
        for (TagDef def : tagDefs.values()) {
            if (isTagActive(def)) list.add(def);
        }
        return list;
    }

    // ── 内部: 子进程启动 ──

    private McpClient launchClient(String name, String mainClass,
                                    String jarPath, Map<String, String> env) {
        try {
            String classpath = System.getProperty("java.class.path");
            if (jarPath != null && !jarPath.isBlank()) {
                classpath = classpath + java.io.File.pathSeparator + jarPath.trim();
            }
            String javaExe = ProcessHandle.current()
                    .info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe); cmd.add("-cp"); cmd.add(classpath); cmd.add(mainClass);

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

    // ── 内部: 重建 Executor ──

    private void rebuildAll() {
        // 数据驱动: 每个标签用其 requires 中的 client
        for (TagDef def : tagDefs.values()) {
            if (isTagActive(def)) {
                rebuildOne(def.holder, "tag:" + def.tag, def.requires);
            } else {
                // 标签不活动 → 给空工具集, Executor 会如实报告
                rebuildOne(def.holder, "tag:" + def.tag, List.of());
            }
        }

        // fullExecutor: 所有活跃 client 的全集
        List<String> allClientNames = new ArrayList<>(clients.keySet());
        rebuildOne(fullHolder, "full", allClientNames);
    }

    private ExecutorAgent buildOne(McpClient client) {
        // 为动态 tag 构建 Executor: 基础 client 全集 + 该 extra
        List<String> names = new ArrayList<>(clients.keySet());
        names.add("__dynamic__"); // 占位, 实际用 client 数组构建
        List<McpClient> all = new ArrayList<>(clients.values());
        all.add(client);
        return buildAgent(all, "dynamic");
    }

    private void rebuildOne(DynamicExecutorHolder holder, String label, List<String> requiredNames) {
        List<McpClient> requiredClients = new ArrayList<>();
        for (String name : requiredNames) {
            McpClient c = clients.get(name);
            if (c != null) requiredClients.add(c);
        }

        ExecutorAgent agent = buildAgent(requiredClients, label);
        holder.swap(agent);
        log.info("{}-Executor 已重建, clients={}", label, requiredClients.size());
    }

    private ExecutorAgent buildAgent(List<McpClient> clientList, String label) {
        if (clientList.isEmpty()) {
            return AiServices.builder(ExecutorAgent.class)
                    .chatModel(model)
                    .build();
        }
        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(clientList.toArray(McpClient[]::new))
                .failIfOneServerFails(false)
                .build();
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .toolProvider(provider)
                .build();
    }

    private void rebuildPlanner() {
        StringBuilder msg = new StringBuilder();
        msg.append("""
                你是一个运维诊断规划师。根据用户问题，输出结构化的排查计划。

                如果用户问题与IT运维、Redis、MySQL、服务器诊断无关（比如闲聊、育儿、医疗等），返回空数组 [].

                每条排查任务由 task (任务描述) 和 tags (工具标签列表) 组成。
                每个任务通常只有一个标签，不同工具的问题拆成独立步骤。

                """);

        List<TagDef> active = activeTagDefs();
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
                1. 只有与运维相关的问题才生成排查计划
                2. task 描述要具体，指明要检查什么指标
                3. 不超过5个步骤
                4. 严格返回 JSON 数组格式，只输出 JSON，不要其他内容
                5. 只能使用上面列出的标签，不要编造标签名
                """);

        // 动态示例: 只展示当前 active 的标签
        if (!active.isEmpty()) {
            msg.append("\n输出格式示例：\n[\n");
            int n = Math.min(active.size(), 3);
            for (int i = 0; i < n; i++) {
                TagDef def = active.get(i);
                msg.append("  {\"task\":\"...\",\"tags\":[\"").append(def.tag).append("\"]}");
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

    // ── 辅助 ──

    private Map<String, Object> holderHashes() {
        Map<String, Object> h = new LinkedHashMap<>();
        for (var entry : tagDefs.entrySet()) {
            h.put(entry.getKey(), entry.getValue().holder.delegateHash());
        }
        h.put("full", fullHolder.delegateHash());
        return h;
    }
}
