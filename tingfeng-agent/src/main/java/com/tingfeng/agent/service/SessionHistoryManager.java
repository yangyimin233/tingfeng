package com.tingfeng.agent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline 会话历史管理器。
 * 从 Reporter 输出的 Markdown 中提取摘要，注入下一轮 Planner 的上下文。
 */
@Component
public class SessionHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryManager.class);
    private static final int MAX_HISTORY = 5;

    private final Cache<String, List<String>> cache = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    /** 记录一轮诊断的摘要 */
    public void record(String sessionId, String question, String report) {
        if (sessionId == null || sessionId.isBlank()) return;
        String summary = extractSummary(report);
        List<String> history = cache.get(sessionId, k -> new ArrayList<>());
        synchronized (history) {
            history.add("Q: " + truncate(question, 100) + "\n  " + summary);
            while (history.size() > MAX_HISTORY) history.remove(0);
        }
        log.debug("[SessionHistory] {} 记录, 当前 {} 条", sessionId, history.size());
    }

    /** 获取历史上下文（注入到 Planner 输入中） */
    public String getContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "";
        List<String> history = cache.getIfPresent(sessionId);
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[上一轮诊断]\n");
        for (String h : history) {
            sb.append(h).append("\n\n");
        }
        log.debug("[SessionHistory] 注入 {} 条历史", history.size());
        return sb.toString();
    }

    /** 从 Reporter 输出的 Markdown 提取摘要 */
    private String extractSummary(String report) {
        if (report == null || report.isBlank()) return "(无内容)";

        StringBuilder sb = new StringBuilder();
        // 提取 "二、排查过程与发现"
        String diagnosis = extractSection(report, "二、排查过程");
        if (!diagnosis.isBlank()) {
            sb.append("发现: ").append(truncate(diagnosis, 200));
        }
        // 提取 "三、根因分析"
        String root = extractSection(report, "三、根因分析");
        if (!root.isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("根因: ").append(truncate(root, 200));
        }
        // 提取 "五、总结"
        String conclusion = extractSection(report, "五、总结");
        if (!conclusion.isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("结论: ").append(truncate(conclusion, 150));
        }
        return sb.isEmpty() ? truncate(report, 200) : sb.toString();
    }

    /** 从 Markdown 报告中按 ## 标题找对应 section */
    private String extractSection(String report, String keyword) {
        String[] sections = report.split("\n(?=## )");
        for (String sec : sections) {
            if (sec.contains(keyword) || sec.contains(keyword.replace(' ', '、'))) {
                // 去掉标题行
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
