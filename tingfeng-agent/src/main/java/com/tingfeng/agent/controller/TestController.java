package com.tingfeng.agent.controller;

import com.tingfeng.agent.config.ToolRegistryManager;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestController {

    private final ChatModel model;
    private final ToolRegistryManager registryManager;

    public TestController(ChatModel model, ToolRegistryManager registryManager) {
        this.model = model;
        this.registryManager = registryManager;
    }

    @GetMapping("/pure-chat")
    public Map<String, Object> testPureChat(@RequestParam(defaultValue = "帮我检查一下MySQL的连接数") String msg) {
        String response = model.chat(msg);
        return Map.of("mode", "Pure LLM (无工具)", "question", msg, "response", response);
    }

    @GetMapping("/tool-chat")
    public Map<String, Object> testToolChat(@RequestParam(defaultValue = "帮我检查一下MySQL的连接数") String msg) {
        String response = registryManager.route("full").execute(msg);
        return Map.of("mode", "Tool-Use (有工具)", "question", msg, "response", response);
    }
}
