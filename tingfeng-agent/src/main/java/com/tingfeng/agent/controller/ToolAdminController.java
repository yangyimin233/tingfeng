package com.tingfeng.agent.controller;

import com.tingfeng.agent.config.ToolRegistryManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public ToolAdminController(ToolRegistryManager manager) {
        this.manager = manager;
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

    /** 快速测试指定 Executor 的诊断效果, target 可选: mysql/redis/system/full(默认) */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam(defaultValue = "检查当前系统运行状态") String q,
                                     @RequestParam(required = false) String target) {
        return manager.test(q, target);
    }
}
