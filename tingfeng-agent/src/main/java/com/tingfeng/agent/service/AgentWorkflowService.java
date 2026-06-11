package com.tingfeng.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import com.tingfeng.agent.agent.TodoItem;
import com.tingfeng.agent.config.TingFengProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlannerAgent planner;
    private final ExecutorAgent mysqlExecutor;
    private final ExecutorAgent redisExecutor;
    private final ExecutorAgent systemExecutor;
    private final ExecutorAgent fullExecutor;
    private final ReporterAgent reporter;
    private final RagService ragService;
    private final int taskTimeoutSeconds;

    public AgentWorkflowService(PlannerAgent planner,
                                 @Qualifier("mysqlExecutor") ExecutorAgent mysqlExecutor,
                                 @Qualifier("redisExecutor") ExecutorAgent redisExecutor,
                                 @Qualifier("systemExecutor") ExecutorAgent systemExecutor,
                                 @Qualifier("fullExecutor") ExecutorAgent fullExecutor,
                                 ReporterAgent reporter,
                                 RagService ragService,
                                 TingFengProperties props) {
        this.planner = planner;
        this.mysqlExecutor = mysqlExecutor;
        this.redisExecutor = redisExecutor;
        this.systemExecutor = systemExecutor;
        this.fullExecutor = fullExecutor;
        this.reporter = reporter;
        this.ragService = ragService;
        this.taskTimeoutSeconds = props.getExecutor().getTimeoutSeconds();
    }

    public String diagnose(String msg) {
        long start = System.currentTimeMillis();
        log.info("=== Pipeline 诊断开始 ===");

        List<TodoItem> todos = planWithRag(msg);
        if (todos.isEmpty()) {
            log.info("非运维问题，已拦截");
            return "我是运维诊断助手，仅能处理 Redis、MySQL、服务器诊断等相关问题。请提供运维相关的排查需求。";
        }

        List<CompletableFuture<TaskResult>> futures = new ArrayList<>();
        for (TodoItem item : todos) {
            futures.add(CompletableFuture.supplyAsync(() -> executeTask(item), executorPool));
        }

        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < futures.size(); i++) {
            try {
                TaskResult result = futures.get(i).get(taskTimeoutSeconds, TimeUnit.SECONDS);
                notes.append(result.note).append("\n\n");
                log.info("  [{}/{}] {} {}", i + 1, todos.size(),
                        result.ok ? "OK" : "FAILED", todos.get(i).task());
            } catch (Exception e) {
                log.warn("  [{}/{}] 超时或异常: {}", i + 1, todos.size(), e.getMessage());
                notes.append("### ").append(todos.get(i).task())
                     .append("\n- 执行超时或异常: ").append(e.getMessage()).append("\n\n");
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

            List<TodoItem> todos = planWithRag(msg);
            emitter.send(event("plan", todos.stream().map(TodoItem::task).toList()));

            if (todos.isEmpty()) {
                emitter.send(event("report", "我是运维诊断助手，仅能处理 Redis、MySQL、服务器诊断等相关问题。请提供运维相关的排查需求。"));
                emitter.complete();
                return;
            }

            // 批量发送所有 task-start
            for (int i = 0; i < todos.size(); i++) {
                emitter.send(event("task-start",
                        String.format("{\"index\":%d,\"total\":%d,\"title\":\"%s\"}",
                                i + 1, todos.size(), escapeJson(todos.get(i).task()))));
            }

            List<CompletableFuture<TaskResult>> futures = new ArrayList<>();
            for (int i = 0; i < todos.size(); i++) {
                final TodoItem item = todos.get(i);
                final int index = i;
                futures.add(CompletableFuture.supplyAsync(() -> executeTask(item), executorPool)
                        .orTimeout(taskTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> new TaskResult(
                                "### " + item.task() + "\n- 执行超时或异常: " + ex.getMessage(), false))
                        .orTimeout(taskTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> new TaskResult(
                                "### " + item.task() + "\n- 执行超时或异常: " + ex.getMessage(), false))
                        .thenApply(result -> {
                            synchronized (emitter) {
                                try {
                                    if (result.ok) {
                                        emitter.send(event("task-done",
                                                String.format("{\"index\":%d,\"note\":\"%s\"}",
                                                        index + 1, escapeJson(truncate(result.note, 300)))));
                                    } else {
                                        emitter.send(event("task-error",
                                                String.format("{\"index\":%d,\"error\":\"%s\"}",
                                                        index + 1, escapeJson(result.note))));
                                    }
                                } catch (IOException e) {
                                    log.warn("SSE 推送失败: {}", e.getMessage());
                                }
                            }
                            return result;
                        }));
            }

            // 等待全部完成（兜底超时 = 单任务超时 + 5s）
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(taskTimeoutSeconds + 5, TimeUnit.SECONDS);

            StringBuilder notes = new StringBuilder();
            for (CompletableFuture<TaskResult> f : futures) {
                notes.append(f.join().note).append("\n\n");
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

    // ── 并行任务执行 ──

    private record TaskResult(String note, boolean ok) {}

    private final java.util.concurrent.ExecutorService executorPool =
            Executors.newFixedThreadPool(4);

    private TaskResult executeTask(TodoItem item) {
        ExecutorAgent executor = routeByTags(item.tags());
        try {
            String note = executor.execute(item.task());
            return new TaskResult(note, true);
        } catch (Exception e) {
            log.warn("  执行失败: {}", e.getMessage());
            return new TaskResult("### " + item.task() + "\n- 执行失败: " + e.getMessage(), false);
        }
    }

    /** 按标签路由 Executor */
    private ExecutorAgent routeByTags(List<String> tags) {
        if (tags == null || tags.size() != 1) return fullExecutor;
        String tag = tags.get(0).toLowerCase();
        return switch (tag) {
            case "mysql" -> mysqlExecutor;
            case "redis" -> redisExecutor;
            case "system" -> systemExecutor;
            default -> fullExecutor;
        };
    }

    // ── Planner 调用 + JSON 解析 ──

    private List<TodoItem> planWithRag(String msg) {
        String ragContext = buildRagContext(msg);
        String enriched = ragContext.isEmpty() ? msg
                : ragContext + "\n\n根据以上参考知识，为以下问题制定排查计划：\n" + msg;
        return plan(enriched);
    }

    private String buildRagContext(String query) {
        List<String> results = ragService.search(query, 3);
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[参考知识]\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
        }
        return sb.toString();
    }

    // ── Planner 调用 + JSON 解析 ──

    private List<TodoItem> plan(String msg) {
        String raw = planner.plan(msg);
        log.info("Planner 原始输出: {}", raw);
        try {
            String json = raw.trim();
            if (json.startsWith("```")) json = json.replaceAll("```json|```", "").trim();
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, TodoItem.class));
        } catch (Exception e) {
            log.warn("结构化解析失败, 尝试旧格式兼容: {}", e.getMessage());
            return parseLegacy(raw);
        }
    }

    @SuppressWarnings("unchecked")
    private List<TodoItem> parseLegacy(String raw) {
        try {
            List<String> items = MAPPER.readValue(raw.trim(), List.class);
            List<TodoItem> result = new ArrayList<>();
            Pattern tagPat = Pattern.compile("\\[(MySQL|Redis|System)\\]\\s*");
            for (String item : items) {
                Matcher m = tagPat.matcher(item);
                List<String> tags = new ArrayList<>();
                while (m.find()) tags.add(m.group(1).toLowerCase());
                String task = tagPat.matcher(item).replaceAll("").trim();
                result.add(new TodoItem(task, tags));
            }
            return result;
        } catch (Exception ee) {
            log.warn("旧格式解析也失败: {}", ee.getMessage());
            return raw.isEmpty() ? List.of() : List.of(new TodoItem(raw, List.of()));
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
