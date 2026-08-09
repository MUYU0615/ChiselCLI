# ChiselCLI 学习笔记：阶段 4 —— 记忆 + RAG + 上下文工程

> 目标：掌握三套系统——记忆（短期/长期/项目）、代码检索（RAG）、
> 上下文预算（按 window 自适应）。理解它们各自解决的痛点和选型差异。
> 对应面试必讲：双压缩机制、检索选型（记忆用关键词 vs 代码用向量）、窗口自适应。

---

## 1. 三套记忆系统（各自独立）

| 系统 | 存储 | 内容 | 生命周期 | 入口 |
|------|------|------|---------|------|
| 短期记忆 | 内存（ConversationMemory） | 本轮对话上下文 | 会话内 | 自动 |
| 长期记忆 | `~/.chisel/memory/long_term_memory.json` | 用户显式保存的事实 | 跨会话 | `/save` |
| 项目记忆 | `PAI.md` 文件 | 团队规则/项目事实 | 随仓库 | `/init` |

### 1.1 短期记忆（ConversationMemory）
- 每轮 `memoryManager.addUserMessage()` / `addAssistantMessage()` / `addToolResult()`
- 有 token 预算（window × 0.45），超限触发压缩（`ContextCompressor`）

### 1.2 长期记忆（LongTermMemory）
- **存储**：`ConcurrentHashMap<String, MemoryEntry>` 内存 + JSON 落盘
- **MemoryEntry 结构**：`id / content / type(FACT|CONVERSATION|SUMMARY|TOOL_RESULT) / timestamp / metadata(scope, project) / tokenCount`
- **写入**：`/save "项目用 Java 17"` → `store()` → 内容去重 → 内存 + 落盘
- **作用域**：`metadata.scope=global` 所有项目可见；`scope=project` 仅指定项目可见（`isVisibleInProject`）

### 1.3 项目记忆（PAI.md）
- 启动时注入 system prompt（`ProjectMemoryLoader`）
- 加载顺序：`~/.chisel/PAI.md` → 项目根 `PAI.md` → `.chisel/PAI.md` → 本地覆盖
- 有字符预算，避免 token 噪音

## 2. 长期记忆检索（关键词匹配，不用向量）

**为什么不用 RAG**（设计决策，代码注释明确）：
1. 规模小（几十条显式保存的事实），全量遍历 + contains 微秒级
2. 内容关键词明确（`/save` 时人已用准确词），不需要语义近似
3. 检索高频（每轮触发），向量化成本高

**实现**（MemoryRetriever）：
```java
private double computeRelevanceScore(MemoryEntry entry, String query) {
    if (contentLower.contains(queryLower)) return 1.0;          // 精确包含满分
    double keywordScore = matchedWords / queryWords.size();      // 部分命中按比例
    double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);     // 时间衰减
    return keywordScore * timeDecay;
}
// 长期记忆额外 ×1.2（显式保存的更可信）
// 排序后取 Top10 → 按 maxTokens 预算截断 → 注入 system prompt
```

**时间衰减**：24h 内从 1.0 线性衰减到 0.5 封底——记忆特有的"越新越相关"（代码检索没有）。

**注入**：`buildContextForQuery` → 组装 `"## 相关长期记忆"` Markdown → `Agent.updateSystemPromptWithMemory` 替换 `conversationHistory[0]`。

## 3. 双压缩机制（关键区分）

| 压缩 | 压什么 | 触发 | 谁调 |
|------|--------|------|------|
| `ContextCompressor` | **短期记忆**（ConversationMemory 条目） | 短期记忆超预算 | MemoryManager |
| `ConversationHistoryCompactor` | **发给 LLM 的消息列表**（conversationHistory） | 接近 window 上限 | Agent.maybeCompactHistory() |

**历史教训**（代码注释里写了）：旧版只压短期记忆，但 Agent 实际发的是 conversationHistory——两者错位导致压缩从未真正缩短即将发给 LLM 的 token。`ConversationHistoryCompactor` 是补丁。

**压缩算法**（ConversationHistoryCompactor）：
```
1. 估算 conversationHistory token，未达 trigger（window-20k-13k）直接返回
2. 找所有 user message 索引，保留最近 3 轮
3. system 之后、splitIdx 之前的全部消息喂 LLM 摘要
4. 重建：[system] + [user("已压缩摘要"+summary)] + [assistant("好的，请继续")] + [保留的尾部]
关键约束：分割点必须在 user message 边界（不切断 tool_call/tool_result 成对协议）
```

## 4. 上下文预算（ContextProfile：全按 window 派生）

**设计原则**（代码注释）：没有长/短/平衡分档，所有参数都是 maxContextWindow 的简单函数。

```java
public static ContextProfile from(LlmClient llmClient) {
    int window = Math.max(MIN_WINDOW, llmClient.maxContextWindow());
    return new ContextProfile(
            window,
            agentBudget(window),               // 80% × window
            compressionTriggerRatio(window),   // 压缩阈值 = window - 20k - 13k
            shortTermBudget(window),           // 短期记忆 = window × 0.45
            memoryContextTokens(window),       // 记忆注入 = window × 0.005（封顶 5000）
            window >= MCP_RESOURCE_INDEX_MIN_WINDOW,   // ≥32k 才注入 MCP resource 索引
            llmClient.supportsPromptCaching(),
            llmClient.promptCacheMode()
    );
}
```

**效果**：GLM 200k / DeepSeek 1M / Kimi 256k 走同一套逻辑，只是 window 不同导致触发时机不同——换模型不用改代码。

## 5. 代码检索（RAG）——与记忆检索对照

### 5.1 索引链路
```
源码文件
  → CodeChunker（JavaParser AST 分块：method/class/file 粒度，解析失败回退定长分段）
  → EmbeddingClient.embedBatch()（OpenAI 兼容批量：bge-m3 1024 维，2300 块 ~76s）
  → VectorStore（SQLite：code_chunks 表 + code_relations 表，批量事务插入）
```

### 5.2 检索链路（混合检索 CodeRetriever.hybridSearch）
```
查询 → Embedding（语义向量）
     + RagQueryTokenizer 分词（jieba）→ 关键词
     │
     ├─ 语义路径：余弦相似度（topK*2 候选）
     ├─ 关键词路径：SQL LIKE + 符号命中加权（name +1.0 / content +0.8 / 泛中文词 +0.4）
     └─ 语义失败 → 自动回退纯关键词（embedding 服务挂了不崩）
     │
     ▼
合并去重（同文件#同方法取 max + 双命中 +0.1）→ typeBoost（method +0.15 / class +0.10）
→ 排序 → limitPerFile(每文件最多 3 条)
```

**工具分工**（系统提示词明确）：
- `grep_code`：精确符号定位（知道名字）
- `glob_files`：文件名定位
- `search_code`：模糊语义兜底（不知道名字）

## 6. 检索选型对比（面试重点）

| 维度 | 记忆检索 | 代码检索（RAG） |
|------|---------|----------------|
| 算法 | jieba 分词 + contains + 时间衰减 | Embedding + 余弦 + 关键词加权 |
| 数据量 | 几十条 | 上千 chunk |
| 查询类型 | 关键词明确 | 语义模糊（模型猜不到名字） |
| 特有需求 | 时间衰减（越新越相关） | 符号精确命中加权 |
| 成本 | 零（纯内存） | embedding API/本地模型 |

**一句话**：记忆是小数据+关键词明确+有新旧权重 → 轻量方案；代码是大数据+语义模糊 → RAG。按数据性质选型。

## 7. 面试 Q&A

**Q1：为什么对话压缩的 trigger 是 window-20k-13k？**
A：20k 预留摘要输出空间，13k 安全缓冲（AUTOCOMPACT_BUFFER_TOKENS）——防止压缩动作本身把窗口撑爆。

**Q2：两道压缩有什么区别？**
A：ContextCompressor 压短期记忆条目；ConversationHistoryCompactor 压真正发给 LLM 的消息列表。前者管记忆预算，后者管窗口超限——两个度量体系，不能混。

**Q3：压缩时为什么分割点必须在 user message 边界？**
A：tool_call 和 tool_result 是成对的（靠 toolCallId 关联），切在中间会破坏协议，模型下一轮无法理解工具结果。

**Q4：记忆为什么不用向量？**
A：规模小 + 关键词明确 + 高频检索需低延迟；且记忆有时间衰减需求（向量没有时间概念）。代码检索才用向量，因为模型猜不到符号名、需要语义近似。

**Q5：RAG 为什么用混合检索不用纯向量？**
A：纯向量对精确符号（类名/方法名）匹配弱；关键词 LIKE + 符号加权兜底精确匹配。且 embedding 服务失败时回退关键词保证可用性。

## 8. 动手验证

```bash
# 1. 长期记忆
/save "我的项目用 Java 17 构建"
/memory list
/memory search "构建"
/memory delete <id>

# 2. RAG 索引 + 检索
/index
/search "限流是怎么实现的"
/graph CodeRetriever

# 3. 上下文状态
/context
```

## 9. 学习顺序

1. 第 1-2 节记忆系统 + 检索（20 分钟）
2. 第 3 节双压缩（15 分钟，重点区分）
3. 第 4 节上下文预算（15 分钟）
4. 第 5 节 RAG 索引 + 检索链路（30 分钟）
5. 第 6 节选型对比（10 分钟，面试重点）
6. 完成后可进阶段 5（TUI + 渲染）
