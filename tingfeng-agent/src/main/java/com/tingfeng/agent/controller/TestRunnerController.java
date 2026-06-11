package com.tingfeng.agent.controller;

import com.tingfeng.agent.service.TestRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/test")
public class TestRunnerController {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerController.class);

    private final TestRunnerService runner;

    public TestRunnerController(TestRunnerService runner) {
        this.runner = runner;
    }

    /**
     * 一键运行诊断对比测试: 同时调用 /tingfeng/chat 和 /diagnose/stream,
     * 由 JudgeAgent 分别评分后返回结果。
     */
    @GetMapping("/compare")
    public Map<String, Object> compare() {
        log.info("========== 诊断对比测试 启动 ==========");
        return runner.runAllTests();
    }

    /**
     * SSE 流式版本 — 前端可实时看到进度
     */
    @GetMapping(value = "/compare-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter compareStream() {
        SseEmitter emitter = new SseEmitter(600_000L);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("start").data("测试开始"));
                Map<String, Object> result = runner.runAllTests();
                emitter.send(SseEmitter.event().name("result").data(result));
                emitter.complete();
            } catch (Exception e) {
                log.error("测试异常", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
