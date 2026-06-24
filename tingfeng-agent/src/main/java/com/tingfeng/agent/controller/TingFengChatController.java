package com.tingfeng.agent.controller;

import com.tingfeng.agent.agent.TingFengOpsAgent;
import com.tingfeng.agent.http.TokenUsageTracker;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tingfeng")
public class TingFengChatController {

    private static final Logger log = LoggerFactory.getLogger(TingFengChatController.class);

    private final TingFengOpsAgent opsAgent;
    private final TokenUsageTracker tokenTracker;

    public TingFengChatController(TingFengOpsAgent opsAgent, TokenUsageTracker tokenTracker) {
        this.opsAgent = opsAgent;
        this.tokenTracker = tokenTracker;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String msg,
                       @RequestParam(required = false) String sessionId,
                       HttpServletResponse response) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        response.setHeader("X-Session-Id", sessionId);

        tokenTracker.reset();
        String answer = opsAgent.diagnose(sessionId, msg);
        var usage = tokenTracker.getUsage();

        response.setHeader("X-Token-Calls", String.valueOf(tokenTracker.getCallCount()));
        response.setHeader("X-Token-Input", String.valueOf(usage.inputTokenCount()));
        response.setHeader("X-Token-Output", String.valueOf(usage.outputTokenCount()));
        response.setHeader("X-Token-Total", String.valueOf(usage.totalTokenCount()));
        log.info("{}", tokenTracker.format());
        return answer;
    }
}
