package com.tingfeng.agent.controller;

import com.tingfeng.agent.service.AgentWorkflowService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/diagnose")
public class DiagnoseController {

    private final AgentWorkflowService workflowService;
    private final ExecutorService backgroundExecutor;

    public DiagnoseController(AgentWorkflowService workflowService,
                              @org.springframework.beans.factory.annotation.Qualifier("backgroundExecutor")
                              ExecutorService backgroundExecutor) {
        this.workflowService = workflowService;
        this.backgroundExecutor = backgroundExecutor;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String msg,
                              @RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        backgroundExecutor.execute(() ->
                workflowService.diagnoseStream(msg, emitter, sessionId));
        return emitter;
    }
}
