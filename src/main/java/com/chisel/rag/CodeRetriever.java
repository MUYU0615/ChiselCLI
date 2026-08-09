package com.chisel.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索器：语义检索 + 图谱检索的统一入口
 */
public class CodeRetriever implements AutoCloseable {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索：用自然语言查询最相关的代码块
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索：同时进行语义检索和关键词检索，合并去重。
     *
     * 排序原则：**符号精确命中（关键词路径）优先于语义近似**——
     * 模型若能在查询里带上猜到的符号名（如 stagnant / refresh），
     * 该符号所在代码块是比语义相似更强的信号，不能被语义高分结果淹没。
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();
        Set<String> dualMatchBonused = new HashSet<>();

        // 1. 语义检索：取 topK*2 候选（弱向量下语义区分度有限，少取避免无关结果挤占）
        int semanticLimit = Math.max(topK * 2, 10);
        for (VectorStore.SearchResult result : semanticSearch(query, semanticLimit)) {
            mergeResult(merged, result, dualMatchBonused);
        }

        // 2. 关键词检索：符号精确命中直接给高权重，进 merged 后排序靠前
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                mergeResult(merged, boostKeywordMatch(result, keyword), dualMatchBonused);
            }
        }

        // 3. 代码类型加分：method/class 比 file 更直接回答"怎么实现"
        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double typeBoost = switch (r.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };
            ranked.add(typeBoost == 0.0 ? r : new VectorStore.SearchResult(
                    r.filePath(), r.chunkType(), r.name(), r.content(), r.similarity() + typeBoost));
        }

        ranked.sort(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed());
        return limitPerFile(ranked, topK, 3);
    }

    private void mergeResult(Map<String, VectorStore.SearchResult> merged, VectorStore.SearchResult candidate,
                             Set<String> dualMatchBonused) {
        String key = candidate.filePath() + "#" + candidate.name();
        VectorStore.SearchResult existing = merged.get(key);
        if (existing == null) {
            merged.put(key, candidate);
        } else {
            double best = Math.max(existing.similarity(), candidate.similarity());
            // 双重命中奖励只给一次，不重复叠加
            if (!dualMatchBonused.contains(key)) {
                best += 0.1;
                dualMatchBonused.add(key);
            }
            merged.put(key, new VectorStore.SearchResult(
                    candidate.filePath(), candidate.chunkType(), candidate.name(),
                    candidate.content(), best));
        }
    }

    private VectorStore.SearchResult boostKeywordMatch(VectorStore.SearchResult result, String keyword) {
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().toLowerCase();
        String contentLower = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        // 符号精确命中是最强信号：
        // - name 命中（类名/方法名含关键词）：权重最高，几乎必然进 TopK
        // - content 命中（代码体/注释含关键词，如 stagnant 字段、pending 字段）：也应显著高于纯语义
        // 中文关键词更泛（"工具""调用"在大量代码注释里出现），content boost 只给英文符号一半权重，
        // 避免泛中文词大面积误命中把真正的符号命中挤到同分并列。
        boolean pureHanKeyword = keywordLower.codePoints()
                .allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        double contentBoost = pureHanKeyword ? 0.4 : 0.8;

        double bonus = 0.0;
        if (nameLower.contains(keywordLower)) {
            bonus += 1.0;  // 方法名/类名精确命中
        } else if (contentLower.contains(keywordLower)) {
            bonus += contentBoost;  // 方法体/注释含关键词：比纯语义强，但泛中文词减半
        }
        if (fileLower.contains(keywordLower)) {
            bonus += 0.2;
        }

        return new VectorStore.SearchResult(
                result.filePath(),
                result.chunkType(),
                result.name(),
                result.content(),
                result.similarity() + bonus
        );
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK
     */
    private List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索：查询指定类/方法的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
