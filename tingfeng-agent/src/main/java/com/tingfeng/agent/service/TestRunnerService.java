package com.tingfeng.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.agent.agent.JudgeAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JudgeAgent judge;
    private final int port;

    public TestRunnerService(JudgeAgent judge,
                             @Value("${server.port:8081}") int port) {
        this.judge = judge;
        this.port = port;
    }

    // ── 测试用例定义 ──

    record TestCase(String caseId, String faultDesc,
                    List<String> expectedKeywords,
                    String expectedConclusion) {}

    private List<TestCase> buildTestCases() {
        return List.of(
                new TestCase("001",
                        "报警显示 Redis 内存使用率达到 95%，且业务端大量超时。",
                        List.of("碎片率", "evicted_keys", "内存饱和", "activedefrag",
                                "mem_fragmentation_ratio", "used_memory", "expired_keys"),
                        "必须覆盖排查内存碎片率(mem_fragmentation_ratio)的步骤，" +
                        "检查因内存满而驱逐的键数量，且不能建议直接重启服务器。"),
                new TestCase("002",
                        "/api/order/create 接口响应时间突然飙升到 2000ms，MySQL CPU 报警。",
                        List.of("慢查询", "slow_log", "索引", "查询阻塞",
                                "long_query_time", "EXPLAIN", "全表扫描"),
                        "应建议调用查询慢日志，锁定具体慢SQL，并用 EXPLAIN 分析执行计划，" +
                        "排查是否因缺失索引或锁等待导致。"),
                new TestCase("003",
                        "帮我推荐一款适合办公的笔记本电脑",
                        List.of("非运维", "拒绝", "仅能处理"),
                        "应明确拒绝该问题，告知仅处理运维诊断相关请求，不应给出电脑推荐。")
        );
    }

    // ── 测试入口 ──

    public Map<String, Object> runAllTests() {
        Instant started = Instant.now();
        List<TestCase> cases = buildTestCases();
        List<Map<String, Object>> results = new ArrayList<>();
        int pipelineTotal = 0, chatTotal = 0, count = 0;

        for (TestCase tc : cases) {
            log.info("========== 测试用例 [{}] 开始: {} ==========", tc.caseId, tc.faultDesc);
            try {
                Map<String, Object> result = runOneCase(tc);
                results.add(result);
                Map<String, Object> pipelineScore = parseScoreMap(result, "pipelineScore");
                Map<String, Object> chatScore = parseScoreMap(result, "chatScore");
                pipelineTotal += toInt(pipelineScore.get("total"));
                chatTotal += toInt(chatScore.get("total"));
                count++;
                log.info("用例[{}] Pipeline总分={}, Chat总分={}", tc.caseId,
                        pipelineScore.get("total"), chatScore.get("total"));
            } catch (Exception e) {
                log.error("用例[{}] 执行失败", tc.caseId, e);
                results.add(Map.of("caseId", tc.caseId, "error", e.getMessage()));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("testCases", cases.size());
        summary.put("completed", count);
        summary.put("pipelineAvgScore", count > 0 ? pipelineTotal / count : 0);
        summary.put("chatAvgScore", count > 0 ? chatTotal / count : 0);
        summary.put("durationSeconds", Duration.between(started, Instant.now()).toSeconds());
        summary.put("results", results);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScoreMap(Map<String, Object> result, String key) {
        Object val = result.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return Map.of("total", 0, "coverage", 0, "conclusion", 0, "professionalism", 0, "comment", "N/A");
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return 0;
    }

    // ── 单个用例执行 ──

    private Map<String, Object> runOneCase(TestCase tc) throws Exception {
        String sessionId = "test-" + tc.caseId + "-" + UUID.randomUUID().toString().substring(0, 6);

        // 1. 调用 Chat 接口
        log.info("[{}] 调用 Chat 接口...", tc.caseId);
        String chatResponse = callChat(tc.faultDesc, sessionId);

        // 2. 调用 Pipeline 流式接口
        log.info("[{}] 调用 Pipeline 流式接口...", tc.caseId);
        Map<String, String> pipelineResult = callPipeline(tc.faultDesc);
        String pipelinePlan = pipelineResult.getOrDefault("plan", "");
        String pipelineReport = pipelineResult.getOrDefault("report", "");

        // 3. Judge 评分
        log.info("[{}] Judge 评分中...", tc.caseId);
        String pipelineJudgeInput = buildJudgePrompt(tc, "Pipeline 诊断报告", pipelinePlan + "\n\n" + pipelineReport);
        String chatJudgeInput = buildJudgePrompt(tc, "Chat 诊断响应", chatResponse);

        String pipelineScoreRaw = judge.judge(pipelineJudgeInput);
        String chatScoreRaw = judge.judge(chatJudgeInput);

        Map<String, Object> pipelineScore = parseJsonScore(pipelineScoreRaw);
        Map<String, Object> chatScore = parseJsonScore(chatScoreRaw);

        log.info("[{}] Pipeline 评分: {} | Chat 评分: {}", tc.caseId,
                pipelineScore.get("total"), chatScore.get("total"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", tc.caseId);
        result.put("faultDesc", tc.faultDesc);
        result.put("expectedKeywords", tc.expectedKeywords);
        result.put("expectedConclusion", tc.expectedConclusion);
        result.put("chatResponse", truncate(chatResponse, 2000));
        result.put("pipelinePlan", truncate(pipelinePlan, 1000));
        result.put("pipelineReport", truncate(pipelineReport, 3000));
        result.put("pipelineScore", pipelineScore);
        result.put("chatScore", chatScore);
        return result;
    }

    // ── HTTP 调用 ──

    private String callChat(String msg, String sessionId) throws Exception {
        String encoded = URLEncoder.encode(msg, StandardCharsets.UTF_8);
        String url = "http://localhost:" + port + "/tingfeng/chat?msg=" + encoded
                + "&sessionId=" + sessionId;
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(120_000);
        conn.setRequestMethod("GET");

        int status = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(status >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            if (status >= 400) {
                log.warn("Chat 接口返回 HTTP {}: {}", status, sb);
                return "HTTP " + status + ": " + sb;
            }
            return sb.toString().trim();
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, String> callPipeline(String msg) throws Exception {
        String encoded = URLEncoder.encode(msg, StandardCharsets.UTF_8);
        String url = "http://localhost:" + port + "/diagnose/stream?msg=" + encoded;
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(300_000); // 5 min timeout
        conn.setRequestMethod("GET");

        Map<String, String> result = new LinkedHashMap<>();
        result.put("plan", "");
        result.put("report", "");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String eventName = "";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    if (!data.isEmpty()) {
                        storeEvent(eventName, data.toString(), result);
                        data.setLength(0);
                    }
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append("\n");
                    data.append(line.substring(5).trim());
                } else if (line.isBlank()) {
                    if (!data.isEmpty()) {
                        storeEvent(eventName, data.toString(), result);
                        data.setLength(0);
                        eventName = "";
                    }
                } else {
                    if (!data.isEmpty()) data.append("\n");
                    data.append(line);
                }
            }
            if (!data.isEmpty()) {
                storeEvent(eventName, data.toString(), result);
            }
        } finally {
            conn.disconnect();
        }
        return result;
    }

    private void storeEvent(String eventName, String data, Map<String, String> result) {
        if ("plan".equals(eventName)) {
            result.put("plan", data);
        } else if ("report".equals(eventName)) {
            String existing = result.get("report");
            result.put("report", (existing != null && !existing.isEmpty() ? existing + "\n" : "") + data);
        }
    }

    // ── Judge 辅助 ──

    private String buildJudgePrompt(TestCase tc, String label, String response) {
        return String.format("""
                ## 测试用例 [%s]
                故障描述：%s
                期望覆盖关键词：%s
                期望结论标准：%s

                ## %s
                %s

                请评分。""",
                tc.caseId, tc.faultDesc,
                String.join("、", tc.expectedKeywords),
                tc.expectedConclusion,
                label, response);
    }

    private Map<String, Object> parseJsonScore(String raw) {
        try {
            String json = raw.trim();
            json = json.replaceAll("```json|```", "").trim();
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Judge 评分 JSON 解析失败: {} raw={}", e.getMessage(), raw);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("total", 0);
            fallback.put("coverage", 0);
            fallback.put("conclusion", 0);
            fallback.put("professionalism", 0);
            fallback.put("comment", "解析失败: " + e.getMessage());
            return fallback;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "(空)";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n...(已截断)" : s;
    }
}
