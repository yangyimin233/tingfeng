package com.tingfeng.agent.service;

import com.tingfeng.agent.config.TingFengProperties;
import com.tingfeng.agent.dto.AlertmanagerPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);
    private static final int MAX_REPORT_LEN = 4000;

    private final AgentWorkflowService workflowService;
    private final TingFengProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    /** 诊断 + 飞书推送的业务线程池 */
    private final ExecutorService workPool = Executors.newFixedThreadPool(2);
    /** 窗口定时器专用，只做 drain，不做耗时操作 */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private final List<AlertmanagerPayload> buffer = new ArrayList<>();
    private final Object bufferLock = new Object();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final int batchWindowSeconds;
    private final int maxBatchSize;

    public AlertDispatchService(AgentWorkflowService workflowService,
                                TingFengProperties properties) {
        this.workflowService = workflowService;
        this.properties = properties;
        this.batchWindowSeconds = properties.getAlert().getBatchWindowSeconds();
        this.maxBatchSize = properties.getAlert().getMaxBatchSize();
    }

    public void dispatch(AlertmanagerPayload payload) {
        boolean shouldForce;
        synchronized (bufferLock) {
            buffer.add(payload);
            shouldForce = buffer.size() >= maxBatchSize;
            log.info("告警入缓冲: groupKey={}, bufferSize={}/{}, window={}s",
                    payload.getGroupKey(), buffer.size(), maxBatchSize, batchWindowSeconds);
        }

        if (batchWindowSeconds <= 0 || shouldForce) {
            if (shouldForce) log.warn("缓冲池满, 强制立即消费");
            drainAndProcess();
            return;
        }

        workPool.execute(() -> {
            if (flushScheduled.compareAndSet(false, true)) {
                timer.schedule(this::drainAndProcess, batchWindowSeconds, TimeUnit.SECONDS);
                log.info("窗口计时器启动, {}s 后批量诊断", batchWindowSeconds);
            }
        });
    }

    // ── 核心：drain 与 process 分离 ──

    /**
     * 从 buffer 中取出待处理告警并清空。
     * 此方法由定时器线程或 force-flush 路径调用，仅做 O(1) 的 drain 操作，
     * 耗时的大模型调用一律丢给 workPool 异步执行，绝不阻塞定时器线程。
     */
    private void drainAndProcess() {
        flushScheduled.set(false);
        List<AlertmanagerPayload> batch;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        log.info("drain {} 条告警, 提交处理", batch.size());
        workPool.execute(() -> doProcess(batch));
    }

    /** 在 workPool 中执行，耗时操作随便跑 */
    private void doProcess(List<AlertmanagerPayload> batch) {
        try {
            String prompt = buildBatchPrompt(batch);
            log.info("开始批量诊断 {} 条告警", batch.size());
            String report = workflowService.diagnose(prompt);
            log.info("批量诊断完成, 报告长度={}", report != null ? report.length() : 0);

            String title = buildBatchCardTitle(batch);
            String severity = extractBatchSeverity(batch);
            pushToFeishu(report, title, severity);
        } catch (Exception e) {
            log.error("批量诊断异常", e);
        }
    }

    // ── 批量 Prompt 构建 ──

    private String buildBatchPrompt(List<AlertmanagerPayload> batch) {
        StringBuilder sb = new StringBuilder("收到以下 ").append(batch.size())
                .append(" 条告警，请分析是否存在共同根因，统一排查：\n\n");
        int idx = 1;
        for (AlertmanagerPayload payload : batch) {
            if (payload.getAlerts() != null) {
                for (AlertmanagerPayload.Alert alert : payload.getAlerts()) {
                    Map<String, String> labels = alert.getLabels();
                    Map<String, String> annotations = alert.getAnnotations();
                    String service = labels != null ? labels.getOrDefault("service",
                            labels.getOrDefault("alertname", "unknown")) : "unknown";
                    String severity = labels != null ? labels.getOrDefault("severity", "unknown") : "unknown";
                    String summary = annotations != null ? annotations.getOrDefault("summary", "") : "";
                    String desc = annotations != null ? annotations.getOrDefault("description", "") : "";

                    sb.append(idx).append(". [").append(severity).append("] ").append(service);
                    if (!summary.isEmpty()) sb.append(": ").append(summary);
                    if (!desc.isEmpty()) sb.append("\n   详情: ").append(desc);
                    sb.append("\n");
                    idx++;
                }
            }
        }

        List<String> allLabels = batch.stream()
                .map(AlertmanagerPayload::getCommonLabels)
                .filter(m -> m != null && !m.isEmpty())
                .flatMap(m -> m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()))
                .distinct()
                .collect(Collectors.toList());
        if (!allLabels.isEmpty()) {
            sb.append("\n公共标签: ").append(String.join(", ", allLabels)).append("\n");
        }

        return sb.toString();
    }

    // ── 飞书卡片 ──

    private static final DateTimeFormatter CARD_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private void pushToFeishu(String report, String title, String severity) {
        String url = properties.getAlert().getFeishu().getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.info("飞书 Webhook URL 未配置，跳过推送");
            return;
        }

        Map<String, Object> body = buildCard(title, severity, report);
        try {
            restTemplate.postForEntity(url, body, String.class);
            log.info("飞书推送成功");
        } catch (Exception e) {
            log.error("飞书推送失败: {}", e.getMessage());
        }
    }

    private String buildBatchCardTitle(List<AlertmanagerPayload> batch) {
        if (batch.size() == 1 && batch.get(0).getAlerts() != null && batch.get(0).getAlerts().size() == 1) {
            AlertmanagerPayload.Alert a = batch.get(0).getAlerts().get(0);
            Map<String, String> ann = a.getAnnotations();
            if (ann != null && ann.containsKey("summary")) return ann.get("summary");
        }
        long totalAlerts = batch.stream()
                .mapToLong(p -> p.getAlerts() != null ? p.getAlerts().size() : 0).sum();
        AlertmanagerPayload first = batch.get(0);
        if (first.getAlerts() != null && !first.getAlerts().isEmpty()) {
            Map<String, String> ann = first.getAlerts().get(0).getAnnotations();
            String summary = ann != null ? ann.getOrDefault("summary", "") : "";
            if (!summary.isEmpty()) {
                return summary + " (共 " + totalAlerts + " 条告警)";
            }
        }
        return totalAlerts + " 条告警";
    }

    private String extractBatchSeverity(List<AlertmanagerPayload> batch) {
        for (AlertmanagerPayload p : batch) {
            if (p.getAlerts() != null) {
                for (AlertmanagerPayload.Alert a : p.getAlerts()) {
                    Map<String, String> labels = a.getLabels();
                    if (labels != null && "critical".equalsIgnoreCase(labels.get("severity")))
                        return "critical";
                }
            }
        }
        return "warning";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCard(String title, String severity, String report) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", title));
        header.put("template", severityColor(severity));

        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", truncateReport(report)));
        elements.add(Map.of("tag", "hr"));
        elements.add(Map.of("tag", "note", "elements", List.of(
                Map.of("tag", "plain_text", "content",
                        "TingFeng Agent · " + CARD_TIME.format(Instant.now()))
        )));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", header);
        card.put("elements", elements);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "interactive");
        body.put("card", card);
        return body;
    }

    private String severityColor(String severity) {
        return switch (severity.toLowerCase()) {
            case "critical" -> "red";
            case "warning", "warn" -> "yellow";
            case "info" -> "blue";
            default -> "blue";
        };
    }

    private String truncateReport(String report) {
        if (report == null) return "";
        if (report.length() <= MAX_REPORT_LEN) return report;
        return report.substring(0, MAX_REPORT_LEN) + "\n\n> (已截断，完整报告请查看 TingFeng 控制台)";
    }
}
