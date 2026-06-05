package com.tingfeng.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Service
public class AgentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlannerAgent planner;
    private final ExecutorAgent executor;
    private final ReporterAgent reporter;

    public AgentWorkflowService(PlannerAgent planner, ExecutorAgent executor, ReporterAgent reporter) {
        this.planner = planner;
        this.executor = executor;
        this.reporter = reporter;
    }

    public String diagnose(String msg) {
        long start = System.currentTimeMillis();
        log.info("=== Pipeline 诊断开始 ===");

        List<String> todos = plan(msg);
        if (todos.isEmpty()) {
            log.info("非运维问题，已拦截");
            return "我是运维诊断助手，仅能处理 Redis、MySQL、服务器诊断等相关问题。请提供运维相关的排查需求。";
        }

        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < todos.size(); i++) {
            String todo = todos.get(i);
            log.info("  [{}/{}] {}", i + 1, todos.size(), todo);
            try {
                notes.append(executor.execute(todo)).append("\n\n");
            } catch (Exception e) {
                log.warn("  执行失败: {}", e.getMessage());
                notes.append("### ").append(todo).append("\n- 执行失败: ").append(e.getMessage()).append("\n\n");
            }
        }

        String context = String.format("用户原始问题：%s\n\n排查笔记：\n%s", msg, notes);
        String report;
        try {
            report = reporter.report(context);
        } catch (Exception e) {
            log.warn("Reporter 生成失败: {}", e.getMessage());
            report = "## 诊断报告生成失败\n\n" + notes;
        }

        log.info("=== Pipeline 诊断完成 ({}ms) ===", System.currentTimeMillis() - start);
        return report;
    }

    public void diagnoseStream(String msg, SseEmitter emitter) {
        try {
            emitter.send(event("phase", "规划中... 正在分析问题"));

            List<String> todos = plan(msg);
            emitter.send(event("plan", todos));

            if (todos.isEmpty()) {
                emitter.send(event("report", "我是运维诊断助手，仅能处理 Redis、MySQL、服务器诊断等相关问题。请提供运维相关的排查需求。"));
                emitter.complete();
                return;
            }

            StringBuilder notes = new StringBuilder();
            for (int i = 0; i < todos.size(); i++) {
                String todo = todos.get(i);
                emitter.send(event("task-start",
                        String.format("{\"index\":%d,\"total\":%d,\"title\":\"%s\"}",
                                i + 1, todos.size(), escapeJson(todo))));

                try {
                    String note = executor.execute(todo);
                    notes.append(note).append("\n\n");
                    emitter.send(event("task-done",
                            String.format("{\"index\":%d,\"note\":\"%s\"}",
                                    i + 1, escapeJson(truncate(note, 300)))));
                } catch (Exception e) {
                    notes.append("### ").append(todo).append("\n- 执行失败: ").append(e.getMessage()).append("\n\n");
                    emitter.send(event("task-error",
                            String.format("{\"index\":%d,\"error\":\"%s\"}", i + 1, escapeJson(e.getMessage()))));
                }
            }

            emitter.send(event("phase", "汇总中... 正在生成报告"));
            String context = String.format("用户原始问题：%s\n\n排查笔记：\n%s", msg, notes);
            String report;
            try {
                report = reporter.report(context);
            } catch (Exception e) {
                report = "## 诊断报告生成失败\n\n" + notes;
            }
            emitter.send(event("report", report));
            emitter.complete();

        } catch (Exception e) {
            log.error("Pipeline 流式诊断异常", e);
            try {
                emitter.send(event("error", e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    // ── Planner 调用 + JSON 解析 ──
    @SuppressWarnings("unchecked")
    private List<String> plan(String msg) {
        String raw = planner.plan(msg);
        log.info("Planner 原始输出: {}", raw);
        try {
            String json = raw.trim();
            // 剔除 LLM 可能在 JSON 外包裹的 markdown 标记
            if (json.startsWith("```")) {
                json = json.replaceAll("```json|```", "").trim();
            }
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Planner JSON 解析失败, 退回整段文本: {}", e.getMessage());
            return raw.isEmpty() ? List.of() : List.of(raw);
        }
    }

    // ── SSE 工具方法 ──
    private SseEmitter.SseEventBuilder event(String name, Object data) {
        return SseEmitter.event().name(name).data(data);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
