package com.tingfeng.agent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline 会话历史管理器。
 * 多轮诊断历史自动 LLM 摘要压缩，避免暴力丢弃。
 */
@Component
public class SessionHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryManager.class);
    private static final int MAX_HISTORY = 8;
    private static final int COMPRESS_THRESHOLD = 5;   // 攒够 5 条原始条目才触发同步压缩

    private final Cache<String, List<String>> cache = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    private final ChatModel chatModel;

    public SessionHistoryManager(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 记录一轮诊断的摘要，并视情况触发同步压缩 */
    public void record(String sessionId, String question, String report) {
        if (sessionId == null || sessionId.isBlank()) return;
        String summary = extractSummary(report);
        List<String> history = cache.get(sessionId, k -> new ArrayList<>());

        // ── 同步压缩：阈值为 5，单线程串行无并发风险 ──
        synchronized (history) {
            history.add("Q: " + truncate(question, 100) + "\n  " + summary);
            while (history.size() > MAX_HISTORY) history.remove(0);

            long rawCount = history.stream().filter(e -> !e.startsWith("[历史压缩]")).count();
            if (rawCount >= COMPRESS_THRESHOLD) {
                compressSync(history, sessionId);
            }
        }
        log.debug("[SessionHistory] {} 记录, 当前 {} 条", sessionId, history.size());
    }

    // ── 同步压缩：在 record() 的 synchronized 块内直接调用 ──

    private void compressSync(List<String> history, String sessionId) {
        int lastIdx = history.size() - 1;
        String latest = history.remove(lastIdx);
        String text = String.join("\n", history);
        history.clear();

        String prompt = """
                将以下多轮运维诊断历史压缩为一段 200 字内的摘要。
                只保留：每次诊断的核心问题、关键发现、最终根因。丢弃过程细节。

                诊断历史：
                %s

                摘要：""".formatted(text);

        try {
            String compressed = chatModel.chat(prompt);
            String trimmed = truncate(compressed, 300);
            history.add("[历史压缩] " + trimmed);
            history.add(latest);
            log.info("[SessionHistory] {} 同步压缩完成: → 2 条 ({} 字)", sessionId, trimmed.length());
        } catch (Exception e) {
            // 降级：压缩失败保留原文，不清空
            log.warn("[SessionHistory] 压缩失败, 保留原文: {}", e.getMessage());
            history.addAll(List.of(text.split("\n")));
            history.add(latest);
        }
    }

    // ────────────────────────────────────────────────
    // 以下为异步压缩方案 (暂存，暂不启用)
    //
    // 异步方案需要处理以下并发问题：
    //   1. 压缩期间新条目插入 → 需 snapshot + tail 合并
    //   2. 多次压缩任务并发 → 需 version guard 防旧结果覆盖
    //   3. 网络波动导致 LLM 调用超时 → 需超时降级
    // 当前采用同步方案规避以上复杂度。
    // ────────────────────────────────────────────────

    /*
    private final ExecutorService compressExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-compress");
        t.setDaemon(true);
        return t;
    });

    private void recordAsync(String sessionId, String question, String report) { ... }

    private void compressAsync(String sessionId, List<String> snapshot) { ... }
    */

    // ── 上下文读取 ──

    /** 获取历史上下文（注入到 Planner 输入中） */
    public String getContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "";
        List<String> history = cache.getIfPresent(sessionId);
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (history.get(0).startsWith("[历史压缩]")) {
            sb.append("[前序多轮诊断历史]\n");
            sb.append(history.get(0).substring(6).trim()).append("\n");
            if (history.size() > 1) {
                sb.append("\n[最近一轮]\n").append(history.get(1)).append("\n");
            }
        } else {
            sb.append("[上一轮诊断]\n");
            for (String h : history) {
                sb.append(h).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── 摘要提取 ──

    private String extractSummary(String report) {
        if (report == null || report.isBlank()) return "(无内容)";

        StringBuilder sb = new StringBuilder();
        String diagnosis = extractSection(report, "二、排查过程");
        if (!diagnosis.isBlank()) {
            sb.append("发现: ").append(truncate(diagnosis, 200));
        }
        String root = extractSection(report, "三、根因分析");
        if (!root.isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("根因: ").append(truncate(root, 200));
        }
        String conclusion = extractSection(report, "五、总结");
        if (!conclusion.isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("结论: ").append(truncate(conclusion, 150));
        }
        return sb.isEmpty() ? truncate(report, 200) : sb.toString();
    }

    private String extractSection(String report, String keyword) {
        String[] sections = report.split("\n(?=## )");
        for (String sec : sections) {
            if (sec.contains(keyword) || sec.contains(keyword.replace(' ', '、'))) {
                int nl = sec.indexOf('\n');
                return nl > 0 ? sec.substring(nl + 1).trim() : sec.trim();
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
