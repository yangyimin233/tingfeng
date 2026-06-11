package com.tingfeng.agent.controller;

import com.tingfeng.agent.service.RagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagAdminController {

    private final RagService ragService;

    public RagAdminController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 新增知识条目: POST /rag/add?title=xxx&content=xxx */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestParam String title, @RequestParam String content) {
        ragService.addEntry(title, content);
        return Map.of("ok", true, "title", title);
    }

    /** 重置为种子数据: POST /rag/reset */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        ragService.resetToSeed();
        return Map.of("ok", true, "count", ragService.entryCount());
    }

    /** 检索知识: GET /rag/search?q=xxx */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query) {
        return Map.of("query", query, "results", ragService.search(query, 5));
    }

    /** 导入 Markdown 文档: POST /rag/import-md, body 为原始 markdown */
    @PostMapping("/import-md")
    public Map<String, Object> importMarkdown(@RequestBody String markdown) {
        int count = ragService.importDocument(markdown);
        return Map.of("ok", true, "imported", count, "total", ragService.entryCount());
    }

    /** 知识条目数: GET /rag/count */
    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("count", ragService.entryCount());
    }
}
