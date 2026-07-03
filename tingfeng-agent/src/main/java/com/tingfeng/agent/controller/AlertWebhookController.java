package com.tingfeng.agent.controller;

import com.tingfeng.agent.dto.AlertmanagerPayload;
import com.tingfeng.agent.service.AlertDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
@ConditionalOnProperty(prefix = "tingfeng.alert", name = "enabled", havingValue = "true")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final AlertDispatchService dispatchService;

    public AlertWebhookController(AlertDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody AlertmanagerPayload payload) {
        log.info("收到告警 webhook: groupKey={}, status={}, alerts={}",
                payload.getGroupKey(), payload.getStatus(),
                payload.getAlerts() != null ? payload.getAlerts().size() : 0);
        dispatchService.dispatch(payload);
        return ResponseEntity.ok("ok");
    }
}
