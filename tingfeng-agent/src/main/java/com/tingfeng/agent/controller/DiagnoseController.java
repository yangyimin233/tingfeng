package com.tingfeng.agent.controller;

import com.tingfeng.agent.service.AgentWorkflowService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;

@RestController
@RequestMapping("/diagnose")
public class DiagnoseController {

    private final AgentWorkflowService workflowService;

    public DiagnoseController(AgentWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/chat")
    public String diagnose(@RequestParam String msg) {
        return workflowService.diagnose(msg);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String msg) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Executors.newSingleThreadExecutor().execute(() ->
                workflowService.diagnoseStream(msg, emitter));
        return emitter;
    }
}
