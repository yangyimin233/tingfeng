package com.tingfeng.agent.controller;

import com.tingfeng.agent.config.TingFengProperties;
import com.tingfeng.agent.config.ToolRegistryManager;
import org.springframework.web.bind.annotation.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具热插拔管理接口 — 运行时注册/注销/查看/测试 MCP 工具源。
 *
 * <pre>
 *   GET    /admin/tools              → 列出所有工具源 (内置 + 额外注册)
 *   POST   /admin/tools/register     → 注册新工具源 body: {"name":"xxx","mainClass":"com.xx.YyyMcp","env":{...}}
 *   DELETE /admin/tools/{name}       → 注销指定工具源
 *   POST   /admin/tools/test?q=xxx   → 用 fullExecutor 快速测试诊断效果
 * </pre>
 */
@RestController
@RequestMapping("/admin/tools")
public class ToolAdminController {

    private final ToolRegistryManager manager;
    private final JedisPool jedisPool;

    public ToolAdminController(ToolRegistryManager manager,
                                TingFengProperties props) {
        this.manager = manager;
        var redis = props.getRedis();
        String pass = redis.getPassword();
        if (pass != null && !pass.isBlank()) {
            this.jedisPool = new JedisPool(new JedisPoolConfig(), redis.getHost(),
                    redis.getPort(), 3000, pass, redis.getDatabase());
        } else {
            this.jedisPool = new JedisPool(new JedisPoolConfig(), redis.getHost(),
                    redis.getPort(), 3000, null, redis.getDatabase());
        }
    }

    // ── Redis 测试辅助 (不走 MCP, 直连验证) ──

    /** 塞测试数据 */
    @PostMapping("/redis-seed")
    public Map<String, Object> redisSeed(@RequestBody Map<String, Object> body) {
        try (Jedis j = jedisPool.getResource()) {
            @SuppressWarnings("unchecked")
            Map<String, String> kvs = (Map<String, String>) body.get("data");
            if (kvs == null) return Map.of("ok", false, "error", "data 字段必填, 如 {\"key1\":\"val1\"}");
            for (var e : kvs.entrySet()) {
                j.set(e.getKey(), e.getValue());
            }
            return Map.of("ok", true, "written", kvs.size(), "keys", kvs.keySet());
        }
    }

    /** 查看 Key 状态 */
    @GetMapping("/redis-check")
    public Map<String, Object> redisCheck(@RequestParam(defaultValue = "test:*") String pattern) {
        try (Jedis j = jedisPool.getResource()) {
            var keys = j.keys(pattern);
            var list = new LinkedHashMap<String, Object>();
            for (String k : keys) {
                list.put(k, Map.of("type", j.type(k), "ttl", j.ttl(k),
                        "len", j.strlen(k), "exists", true));
            }
            return Map.of("ok", true, "count", keys.size(), "keys", list);
        }
    }

    /** 列出所有工具源（内置 + 额外），以及 fullExecutor 当前 delegate 的 hashCode */
    @GetMapping
    public Map<String, Object> list() {
        return manager.listToolSources();
    }

    /**
     * 注册新 MCP 工具源（额外的子进程）。
     * 可选 tag 字段创建新的诊断类别（如 elasticsearch），Planner 会自动感知该标签。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String mainClass = (String) body.get("mainClass");
        String jar = (String) body.get("jar");
        String tag = (String) body.get("tag");
        String tagDesc = (String) body.get("tagDesc");
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) body.get("env");
        if (name == null || name.isBlank()) return Map.of("ok", false, "error", "name 不能为空");
        if (mainClass == null || mainClass.isBlank()) return Map.of("ok", false, "error", "mainClass 不能为空");
        return manager.register(name.trim(), mainClass.trim(), jar,
                tag != null ? tag.trim() : null,
                tagDesc != null ? tagDesc.trim() : null, env);
    }

    /** 注销指定工具源（关闭子进程 + 重建 executor） */
    @DeleteMapping("/{name}")
    public Map<String, Object> unregister(@PathVariable String name) {
        return manager.unregister(name);
    }

    /** 直调 MCP 执行工具（不经过 LLM），用于批准后的自动操作 */
    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> body) {
        String client = (String) body.get("client");
        String tool = (String) body.get("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.get("args");
        if (client == null || client.isBlank()) return Map.of("ok", false, "error", "client 不能为空");
        if (tool == null || tool.isBlank()) return Map.of("ok", false, "error", "tool 不能为空");
        return manager.executeAction(client.trim(), tool.trim(),
                args != null ? args : Map.of());
    }

    /** 快速测试指定 Executor 的诊断效果, target 可选: mysql/redis/system/full(默认) */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam(defaultValue = "检查当前系统运行状态") String q,
                                     @RequestParam(required = false) String target) {
        return manager.test(q, target);
    }
}
