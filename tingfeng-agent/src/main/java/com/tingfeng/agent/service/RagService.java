package com.tingfeng.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STORE_PATH = Path.of("data", "rag_store.json");
    private static final Path KW_PATH = Path.of("data", "rag_keyword.json");
    private static final String SEED_FILE = "rag/seed_knowledge.json";

    private final EmbeddingModel embeddingModel;
    private InMemoryEmbeddingStore<TextSegment> store;

    // ── 关键词索引 ──
    private final List<String> docTexts = new CopyOnWriteArrayList<>();
    private final Map<String, Map<Integer, Double>> invertedIndex = new ConcurrentHashMap<>();
    private final Map<String, Double> idfScores = new ConcurrentHashMap<>();

    public RagService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // ── 生命周期 ──

    @PostConstruct
    public void init() {
        try {
            if (Files.exists(STORE_PATH)) {
                log.info("从 {} 恢复向量知识库", STORE_PATH);
                store = InMemoryEmbeddingStore.fromFile(STORE_PATH.toAbsolutePath().toString());
                restoreKeywordIndex();
                log.info("已恢复向量知识库 ({} 条) + 关键词索引", entryCount());
                return;
            }
        } catch (Exception e) {
            log.warn("知识库恢复失败, 将重新构建: {}", e.getMessage());
            try { Files.deleteIfExists(STORE_PATH); } catch (IOException ignored) {}
        }
        store = new InMemoryEmbeddingStore<>();
        loadSeed();
        buildKeywordIndex();
    }

    @PreDestroy
    public void save() {
        if (store == null) return;
        try {
            Files.createDirectories(STORE_PATH.getParent());
            store.serializeToFile(STORE_PATH.toAbsolutePath().toString());
            saveKeywordIndex();
            log.info("知识库已保存 (向量+关键词)");
        } catch (Exception e) {
            log.warn("知识库持久化失败: {}", e.getMessage());
        }
    }

    // ── 混合检索 ──

    public List<String> search(String query, int maxResults) {
        return search(query, maxResults, 0.0);
    }

    public List<String> search(String query, int maxResults, double minScore) {
        if (store == null) return Collections.emptyList();
        try {
            // 1. 向量检索 (语义维度)
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchResult<TextSegment> vectorResult = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(10)
                            .minScore(minScore)
                            .build());

            List<EmbeddingMatch<TextSegment>> vMatches = vectorResult.matches();

            // 2. 关键词检索 (字面维度)
            List<ScoredDoc> kwMatches = keywordSearch(query, 10);

            // 3. 日志输出: 两路召回详情
            log.info("[RAG] 查询: {}", query);
            log.info("[RAG-向量] 召回 {} 条:", vMatches.size());
            for (int i = 0; i < vMatches.size(); i++) {
                var m = vMatches.get(i);
                String title = m.embedded().text().replace('\n', ' ');
                if (title.length() > 60) title = title.substring(0, 60) + "...";
                log.info("  #{} score={:.4f} title={}", i + 1, m.score(), title);
            }
            log.info("[RAG-关键词] 召回 {} 条:", kwMatches.size());
            for (int i = 0; i < kwMatches.size(); i++) {
                ScoredDoc d = kwMatches.get(i);
                String title = d.text.replace('\n', ' ');
                if (title.length() > 60) title = title.substring(0, 60) + "...";
                log.info("  #{} score={:.4f} title={}", i + 1, d.score, title);
            }

            // 4. RRF 融合去重
            List<String> fused = rrfFuse(vMatches, kwMatches, 60, maxResults);
            log.info("[RAG-融合] 最终返回 {} 条", fused.size());
            for (int i = 0; i < fused.size(); i++) {
                String title = fused.get(i).replace('\n', ' ');
                if (title.length() > 60) title = title.substring(0, 60) + "...";
                log.info("  #{} {}", i + 1, title);
            }

            return fused;
        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 关键词检索 ──

    private record ScoredDoc(String text, double score) {}

    private static final double KW_MIN_SCORE = 0.5;

    private List<ScoredDoc> keywordSearch(String query, int topK) {
        if (docTexts.isEmpty()) return Collections.emptyList();
        List<String> qtokens = tokenize(query);
        if (qtokens.isEmpty()) return Collections.emptyList();

        // TF-IDF 打分: 对每个文档累加所有查询词的 TF-IDF 权重
        double[] scores = new double[docTexts.size()];
        for (String token : qtokens) {
            Map<Integer, Double> postings = invertedIndex.get(token);
            if (postings == null) continue;
            double idf = idfScores.getOrDefault(token, 3.0);
            for (var entry : postings.entrySet()) {
                int docId = entry.getKey();
                double tf = entry.getValue();
                scores[docId] += tf * idf;
            }
        }

        List<ScoredDoc> results = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > KW_MIN_SCORE) {
                results.add(new ScoredDoc(docTexts.get(i), scores[i]));
            }
        }
        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    // ── RRF 融合 ──

    private List<String> rrfFuse(List<EmbeddingMatch<TextSegment>> vector,
                                  List<ScoredDoc> keyword, int k, int topN) {
        Map<String, Double> scores = new LinkedHashMap<>();

        for (int i = 0; i < vector.size(); i++) {
            String text = vector.get(i).embedded().text();
            scores.merge(text, 1.0 / (i + k), Double::sum);
        }
        for (int i = 0; i < keyword.size(); i++) {
            scores.merge(keyword.get(i).text, 1.0 / (i + k), Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ── 倒排索引构建 ──

    private void buildKeywordIndex() {
        docTexts.clear();
        invertedIndex.clear();
        idfScores.clear();
        if (store == null) return;

        EmbeddingSearchResult<TextSegment> all = store.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModel.embed("init").content())
                        .maxResults(10000).build());

        int docId = 0;
        for (var match : all.matches()) {
            String text = match.embedded().text();
            docTexts.add(text);
            List<String> tokens = tokenize(text);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            for (var entry : tf.entrySet()) {
                invertedIndex.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                        .put(docId, (double) entry.getValue());
            }
            docId++;
        }
        // 计算 IDF
        int N = docTexts.size();
        for (var entry : invertedIndex.entrySet()) {
            int df = entry.getValue().size();
            double idf = Math.log((N + 1.0) / (df + 0.5));
            idfScores.put(entry.getKey(), idf);
        }
        log.info("关键词索引构建完成: {} 文档, {} 个词项", N, invertedIndex.size());
    }

    // ── 分词 (中文 bigram + 空白分隔) ──

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        // CJK bigram
        for (int i = 0; i < text.length() - 1; i++) {
            char c1 = text.charAt(i);
            char c2 = text.charAt(i + 1);
            if (Character.isIdeographic(c1) && Character.isIdeographic(c2)) {
                tokens.add(String.valueOf(c1) + c2);
            }
        }
        // Whitespace tokens for English / mixed content
        String[] parts = text.split("[\\s,，。.!！?？:：;；、\\[\\]【】()（）]+");
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p.toLowerCase());
        }
        return tokens;
    }

    // ── 关键词索引持久化 ──

    @SuppressWarnings("unchecked")
    private void restoreKeywordIndex() {
        try {
            if (!Files.exists(KW_PATH)) { buildKeywordIndex(); return; }
            Map<String, Object> data = MAPPER.readValue(KW_PATH.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            List<String> texts = (List<String>) data.get("docs");
            docTexts.addAll(texts);
            Map<String, Map<String, Number>> rawIdx = (Map<String, Map<String, Number>>) data.get("index");
            if (rawIdx != null) {
                for (var e : rawIdx.entrySet()) {
                    Map<Integer, Double> postings = new ConcurrentHashMap<>();
                    for (var p : e.getValue().entrySet()) {
                        postings.put(Integer.parseInt(p.getKey()), p.getValue().doubleValue());
                    }
                    invertedIndex.put(e.getKey(), postings);
                }
            }
            Map<String, Number> rawIdf = (Map<String, Number>) data.get("idf");
            if (rawIdf != null) {
                for (var e : rawIdf.entrySet()) idfScores.put(e.getKey(), e.getValue().doubleValue());
            }
            log.info("关键词索引已恢复: {} 文档", docTexts.size());
        } catch (Exception e) {
            log.warn("关键词索引恢复失败, 将重建: {}", e.getMessage());
            buildKeywordIndex();
        }
    }

    private void saveKeywordIndex() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("docs", new ArrayList<>(docTexts));
            Map<String, Map<Integer, Double>> idxCopy = new LinkedHashMap<>();
            for (var e : invertedIndex.entrySet()) {
                idxCopy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            data.put("index", idxCopy);
            data.put("idf", new LinkedHashMap<>(idfScores));
            MAPPER.writeValue(KW_PATH.toFile(), data);
        } catch (Exception e) {
            log.warn("关键词索引保存失败: {}", e.getMessage());
        }
    }

    // ── 种子知识加载 ──

    public void addEntry(String title, String content) {
        if (store == null) store = new InMemoryEmbeddingStore<>();
        String text = title + "\n" + content;
        Embedding embedding = embeddingModel.embed(text).content();
        store.add(embedding, TextSegment.from(text));
        buildKeywordIndex();
        save();
        log.info("新增知识条目: {}", title);
    }

    public void resetToSeed() {
        store = new InMemoryEmbeddingStore<>();
        loadSeed();
        buildKeywordIndex();
        save();
        log.info("知识库已重置为种子数据");
    }

    public int importDocument(String markdown) {
        if (store == null) store = new InMemoryEmbeddingStore<>();
        String[] sections = markdown.split("\n(?=## )");
        int count = 0;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            String title = trimmed;
            String content = trimmed;
            int newlineIdx = trimmed.indexOf('\n');
            if (newlineIdx > 0 && trimmed.startsWith("## ")) {
                title = trimmed.substring(3, newlineIdx).trim();
                content = trimmed.substring(newlineIdx + 1).trim();
            } else if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                continue;
            }
            if (title.length() < 10 || content.length() < 20) continue;

            String text = title + "\n" + content;
            Embedding embedding = embeddingModel.embed(text).content();
            store.add(embedding, TextSegment.from(text));
            count++;
        }
        if (count > 0) {
            buildKeywordIndex();
            save();
        }
        log.info("文档导入完成: {} 个切片", count);
        return count;
    }

    public int entryCount() {
        if (store == null) return 0;
        try {
            Embedding dummy = embeddingModel.embed("count").content();
            return store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(dummy).maxResults(10000).build()).matches().size();
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
