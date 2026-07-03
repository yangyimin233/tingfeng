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
import java.util.stream.Collectors;

@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);
    private static final int MAX_REPORT_LEN = 4000;

    private final AgentWorkflowService workflowService;
    private final TingFengProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public AlertDispatchService(AgentWorkflowService workflowService,
                                TingFengProperties properties) {
        this.workflowService = workflowService;
        this.properties = properties;
    }

    public void dispatch(AlertmanagerPayload payload) {
        executor.execute(() -> {
            try {
                doDispatch(payload);
            } catch (Exception e) {
                log.error("告警处理异常", e);
            }
        });
    }

    private void doDispatch(AlertmanagerPayload payload) {
        String prompt = buildPrompt(payload);
        log.info("开始诊断告警: groupKey={}, alerts={}", payload.getGroupKey(),
                payload.getAlerts() != null ? payload.getAlerts().size() : 0);

        String report = workflowService.diagnose(prompt);
        log.info("诊断完成, 报告长度={}", report != null ? report.length() : 0);

        pushToFeishu(report, payload);
    }

    private String buildPrompt(AlertmanagerPayload payload) {
        StringBuilder sb = new StringBuilder("收到以下告警，请排查根因并给出处理建议：\n\n");
        if (payload.getAlerts() != null) {
            for (int i = 0; i < payload.getAlerts().size(); i++) {
                AlertmanagerPayload.Alert alert = payload.getAlerts().get(i);
                Map<String, String> labels = alert.getLabels();
                Map<String, String> annotations = alert.getAnnotations();

                String service = labels != null ? labels.getOrDefault("service",
                        labels.getOrDefault("alertname", "unknown")) : "unknown";
                String severity = labels != null ? labels.getOrDefault("severity", "unknown") : "unknown";
                String summary = annotations != null ? annotations.getOrDefault("summary", "") : "";
                String desc = annotations != null ? annotations.getOrDefault("description", "") : "";

                sb.append(i + 1).append(". [").append(severity).append("] ").append(service);
                if (!summary.isEmpty()) sb.append(": ").append(summary);
                if (!desc.isEmpty()) sb.append("\n   详情: ").append(desc);
                sb.append("\n");
            }
        }

        if (payload.getCommonLabels() != null && !payload.getCommonLabels().isEmpty()) {
            String ctx = payload.getCommonLabels().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            sb.append("\n公共标签: ").append(ctx).append("\n");
        }

        return sb.toString();
    }

    // ── 飞书卡片 ──

    private static final DateTimeFormatter CARD_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private void pushToFeishu(String report, AlertmanagerPayload payload) {
        String url = properties.getAlert().getFeishu().getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.info("飞书 Webhook URL 未配置，跳过推送");
            return;
        }

        String title = buildCardTitle(payload);
        String severity = extractSeverity(payload);

        Map<String, Object> body = buildCard(title, severity, report);
        try {
            restTemplate.postForEntity(url, body, String.class);
            log.info("飞书推送成功");
        } catch (Exception e) {
            log.error("飞书推送失败: {}", e.getMessage());
        }
    }

    private String buildCardTitle(AlertmanagerPayload payload) {
        if (payload.getAlerts() != null && !payload.getAlerts().isEmpty()) {
            AlertmanagerPayload.Alert first = payload.getAlerts().get(0);
            Map<String, String> labels = first.getLabels();
            Map<String, String> annotations = first.getAnnotations();
            String service = labels != null ? labels.getOrDefault("service",
                    labels.getOrDefault("alertname", "")) : "";
            String summary = annotations != null ? annotations.getOrDefault("summary", "") : "";
            if (!summary.isEmpty()) return summary;
            if (!service.isEmpty()) return service + " 告警";
        }
        return "TingFeng 告警诊断";
    }

    private String extractSeverity(AlertmanagerPayload payload) {
        if (payload.getAlerts() != null && !payload.getAlerts().isEmpty()) {
            Map<String, String> labels = payload.getAlerts().get(0).getLabels();
            if (labels != null) return labels.getOrDefault("severity", "info");
        }
        if (payload.getCommonLabels() != null) {
            return payload.getCommonLabels().getOrDefault("severity", "info");
        }
        return "info";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCard(String title, String severity, String report) {
        // header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", title));
        header.put("template", severityColor(severity));

        // body elements
        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content",
                truncateReport(report)));
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
