package com.tingfeng.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORE_PATH = Path.of("data", "rag_store.json");
    private static final String SEED_FILE = "rag/seed_knowledge.json";

    private final EmbeddingModel embeddingModel;
    private InMemoryEmbeddingStore<TextSegment> store;

    public RagService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void init() {
        try {
            if (Files.exists(STORE_PATH)) {
                log.info("从 {} 恢复向量知识库 (免重新向量化)", STORE_PATH);
                store = InMemoryEmbeddingStore.fromFile(STORE_PATH.toAbsolutePath().toString());
                log.info("已恢复向量知识库");
                return;
            }
        } catch (Exception e) {
            log.warn("向量知识库恢复失败, 将重新构建: {}", e.getMessage());
            try { Files.deleteIfExists(STORE_PATH); } catch (IOException ignored) {}
        }

        // 全新构建
        store = new InMemoryEmbeddingStore<>();
        loadSeed();
    }

    @PreDestroy
    public void save() {
        if (store == null) return;
        try {
            Files.createDirectories(STORE_PATH.getParent());
            store.serializeToFile(STORE_PATH.toAbsolutePath().toString());
            log.info("向量知识库已保存到 {}", STORE_PATH);
        } catch (Exception e) {
            log.warn("向量知识库持久化失败: {}", e.getMessage());
        }
    }

    public List<String> search(String query, int maxResults) {
        if (store == null) return Collections.emptyList();
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchResult<TextSegment> result = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(maxResults)
                            .build());
            return result.matches().stream()
                    .map(m -> m.embedded().text())
                    .toList();
        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 种子知识加载 ──

    /** 运行时新增一条知识 — 向量化后写入 store + 触发即时落盘 */
    public void addEntry(String title, String content) {
        if (store == null) store = new InMemoryEmbeddingStore<>();
        String text = title + "\n" + content;
        Embedding embedding = embeddingModel.embed(text).content();
        store.add(embedding, TextSegment.from(text));
        save();
        log.info("新增知识条目: {}", title);
    }

    /** 清空知识库并重新加载种子知识 */
    public void resetToSeed() {
        store = new InMemoryEmbeddingStore<>();
        loadSeed();
        save();
        log.info("知识库已重置为种子数据");
    }

    /**
     * 导入 Markdown 文档 — 按 ## 标题切片, 每段即一条知识。
     * 忽略过短的片段(标题名或正文 < 20 字符), 忽略前导 # 号之前的导言。
     * 返回导入的条目数。
     */
    public int importDocument(String markdown) {
        if (store == null) store = new InMemoryEmbeddingStore<>();
        String[] sections = markdown.split("\n(?=## )");
        int count = 0;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            // 提取 ## 标题作为 title
            String title = trimmed;
            String content = trimmed;
            int newlineIdx = trimmed.indexOf('\n');
            if (newlineIdx > 0 && trimmed.startsWith("## ")) {
                title = trimmed.substring(3, newlineIdx).trim();
                content = trimmed.substring(newlineIdx + 1).trim();
            } else if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                continue; // 跳过文档总标题 (# xxx)
            }
            if (title.length() < 10 || content.length() < 20) continue;

            String text = title + "\n" + content;
            Embedding embedding = embeddingModel.embed(text).content();
            store.add(embedding, TextSegment.from(text));
            count++;
        }
        if (count > 0) save();
        log.info("文档导入完成: {} 个切片", count);
        return count;
    }

    /** 当前知识条目数 */
    public int entryCount() {
        if (store == null) return 0;
        try {
            Embedding dummy = embeddingModel.embed("count").content();
            return store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(dummy).maxResults(1000).build()).matches().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void loadSeed() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_FILE)) {
            if (is == null) {
                log.info("未找到种子知识文件, 跳过预加载");
                return;
            }
            List<Map<String, String>> items = MAPPER.readValue(is,
                    new TypeReference<List<Map<String, String>>>() {});
            log.info("首次启动, 正在向量化 {} 条种子知识...", items.size());

            for (Map<String, String> item : items) {
                String text = item.get("title") + "\n" + item.get("content");
                Embedding embedding = embeddingModel.embed(text).content();
                store.add(embedding, TextSegment.from(text));
            }
            log.info("种子知识向量化完成");
        } catch (IOException e) {
            log.warn("加载种子知识失败: {}", e.getMessage());
        }
    }
}
