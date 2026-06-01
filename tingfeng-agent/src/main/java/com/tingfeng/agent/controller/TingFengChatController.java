package com.tingfeng.agent.controller;

import com.tingfeng.agent.agent.TingFengOpsAgent;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tingfeng")
public class TingFengChatController {

    private final TingFengOpsAgent opsAgent;

    public TingFengChatController(TingFengOpsAgent opsAgent) {
        this.opsAgent = opsAgent;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String msg,
                       @RequestParam(required = false) String sessionId,
                       HttpServletResponse response) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        response.setHeader("X-Session-Id", sessionId);
        return opsAgent.diagnose(sessionId, msg);
    }
}
