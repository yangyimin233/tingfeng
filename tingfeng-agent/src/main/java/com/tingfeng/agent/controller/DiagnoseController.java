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


    // 目前已换用下面的流式输出接口了，其service执行等效普通的 diagnose + 一个emitter 能异步输出一些signal 供前端 绘制 状态信息
//    @GetMapping("/chat")
//    public String diagnose(@RequestParam String msg) {
//        return workflowService.diagnose(msg);
//    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String msg,
                              @RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Executors.newSingleThreadExecutor().execute(() ->
                workflowService.diagnoseStream(msg, emitter, sessionId));
        return emitter;
    }
}
