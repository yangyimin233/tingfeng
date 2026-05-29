package com.tingfeng.agent.controller;

import com.tingfeng.agent.agent.TingFengOpsAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tingfeng")
public class TingFengChatController {

    private final TingFengOpsAgent opsAgent;

    public TingFengChatController(TingFengOpsAgent opsAgent) {
        this.opsAgent = opsAgent;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String msg) {
        return opsAgent.diagnose(msg);
    }
}
