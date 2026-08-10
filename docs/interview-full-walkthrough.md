# ChiselCLI 面试全讲解（完整版）

> 从项目定位到每个模块的实现细节，一条主线串到底，覆盖面试官可能拷打的所有点。
> 建议顺序：先读一遍建立全局 → 逐个模块对照代码 → 背 Q&A。

---

## 第一部分：项目定位（30 秒开场）

**一句话**：ChiselCLI 是一个**从零手写的 Java Agent CLI**（对标 Claude Code），把用户的自然语言任务通过"循环调用 LLM + 执行工具 + 记忆/上下文管理"变成可验证的产出。

**规模**：Java 17 / Maven / 34k 行 / 214 个主类 / 770+ 单测全绿。

**核心能力地图**（24 期演进）：
```
基础 ReAct → 规划执行 → 记忆上下文 → RAG检索 → 多Agent → HITL → 并行 → 多模型
联网 → MCP核心 → MCP高级 → 长上下文 → ChromeDevTools → CDP复用 → Skill → TUI
LSP诊断 → Git快照 → Prompt分层 → 后台任务+API → 图片输入 → 微信通道 → MCP OAuth/sampling/重启
```

**三条执行路径**（都共享 ToolRegistry / MemoryManager / SnapshotService）：
| 路径 | 入口 | 触发 | 内部 |
|------|------|------|------|
| ReAct | `Agent.java` | 默认 | while(true) 循环调 LLM |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` | 先规划 DAG，task 按依赖批次执行 |
| Multi-Agent | `AgentOrchestrator.java` | `/team` | 规划者+Worker+检查者，审查循环 |

**关键认知**：Plan 的 task 和 Team 的 worker 内部都还是 ReAct 循环（SubAgent）——"一种循环，三种编排"。

---

## 第二部分：主链路（面试第一问必讲）

### 一条消息怎么走完

```
用户输入 "帮我看下项目结构"
    │
    ▼
① Main.java（CLI 入口）
    │  CliCommandParser 判断：普通任务 or /命令
    │  普通任务 → 展开 @path / @image 引用
    │  后台线程执行（runWithCancelSupport），前台 raw mode 监听 ESC
    ▼
② Agent.run(userInput)
    │  存短期记忆 + 检索长期记忆 → 替换 system prompt 第 0 条
    │  用户消息（前置 Skill body）进 conversationHistory
    ▼
③ while(true) ReAct 循环        ← 灵魂
    │  压缩检查 / 预算检查 / LSP 诊断注入
    │  llmClient.chat(history, tools) 流式调模型
    │  模型返回：
    │    ├─ toolCalls → 并行执行 → Message.tool 回灌 → continue
    │    └─ 无 → 返回最终回答
    ▼
④ Renderer.stream() → TUI 展示
```

### ReAct 循环伪代码（面试默写版）

```java
public String run(String userInput) {
    memoryManager.addUserMessage(userInput);
    updateSystemPromptWithMemory(retrieveLongTerm(userInput));  // 记忆注入 history[0]
    conversationHistory.add(Message.user(userInput));

    while (true) {
        if (CancellationContext.isCancelled()) return "已取消";
        maybeCompactHistory();                        // 防 window 超限
        AgentBudget.ExitReason r = budget.check();    // 三层守护
        if (r != WITHIN_BUDGET) return "预算耗尽";

        ChatResponse response = llmClient.chat(history, tools);  // 流式

        if (response.hasToolCalls()) {
            history.add(Message.assistant(reasoning, content, toolCalls));
            List<ToolExecutionResult> results = executeToolCalls(toolCalls);  // 并行
            for (ToolExecutionResult tr : results) {
                history.add(Message.tool(tr.id(), tr.result()));   // 结果回灌
            }
            continue;
        }
        history.add(Message.assistant(response.content()));
        return response.content();
    }
}
```

### 三个核心机制

**① 防死循环（AgentBudget 三层守护）**
```java
// token 预算（默认 Integer.MAX_VALUE，可用 -Dchisel.react.token.budget 覆盖）
// 停滞检测：最近 N 轮"工具名+参数"签名全部相同 → 判死循环
String signature = signatureOf(toolCalls);
recentToolSignatures.addLast(signature);
stagnant = recentToolSignatures.stream().allMatch(sig -> sig.equals(first));
// 硬迭代上限（默认 50）
```

**② 工具结果回灌（为什么 Message.tool 带 toolCallId）**
```
LLM: assistant(toolCalls=[{id: call_1, name: read_file}])
  → 执行 read_file → 返回内容
  → 回灌: tool(role, toolCallId="call_1", content="...")
  → 下一轮 LLM 才能关联"这个结果是谁调出来的"
关键：顺序必须与 toolCalls 一致（executeTools 按 index 收集保证）
```

**③ 协作式取消（为什么不用纯 Thread.interrupt）**
```
Main: token = CancellationContext.startRun()
      future = executor.submit(task)          // 后台线程
      // raw mode 监听 ESC
      if (readEscCancel(terminal)) { token.cancel(); future.cancel(true); }
Agent: 每个循环边界检查 CancellationContext.isCancelled()
原因：OkHttp 阻塞读 LLM 流时 interrupt 不保证立即生效
       → 用 flag（循环逻辑退出）+ interrupt（打断阻塞 I/O）双管齐下
```

### 流式解析（AbstractOpenAiCompatibleClient）

```java
// SSE 每个 data 是一小片 delta
JsonNode delta = choice.path("delta");
String contentDelta = delta.path("content").asText("");
streamListener.onContentDelta(contentDelta);            // 实时推给渲染器
mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));  // 按 index 合并分片

// 兼容多 provider：
// reasoning 字段：reasoning_content / reasoning / reasoning_details
// 缓存 token：cached_tokens / prompt_cache_hit_tokens / input_cache_hit_tokens / prompt_tokens_details.cached
```

**Q：为什么 tool_call 要按 index 合并？**
A：OpenAI 兼容 API 流式响应里，一个 tool_call 的参数被拆成多个 delta 分片（`{"index":0,"arguments":"{\"path\":"}` → `{"index":0,"arguments":"\"a.txt\"}"}`），必须按 index 攒起来再拼。

---

## 第三部分：工具系统 + 安全（后端岗必讲）

### 工具的四要素
```
Tool(name, description, parameters(schema), executor)
- name：模型识别
- description：模型选择工具的依据（决定模型用不用）
- parameters：JSON schema，模型按它生成参数
- executor：真正干活
```
每轮把全部工具的 schema 汇总发给模型（getToolDefinitions），模型根据 description 决定用哪个。

### 执行链四层拦截（核心）

```
模型请求 execute_command("rm -rf /")
  │
  ▼
① HitlToolRegistry.executeToolOutput()   ← HITL 层（人审）
    ├─ HITL 未启用 / 工具不需审批 → 直接放行（零开销）
    ├─ 已"全部放行"（按工具 or 按 MCP server）→ 直接执行
    └─ 否则弹窗 [y/n/a/s/m]
  │
  ▼
② ToolRegistry.doExecuteTool()           ← 执行层
    ├─ MCP 工具先过浏览器安全检查（checkBrowserTool）
    ├─ PathGuard.resolveSafe(path)       ← ③ 路径围栏
    └─ CommandGuard.check(command)       ← ④ 命令黑名单
  │
  ▼
成功 → 审计 allow + 返回结果
PolicyException → 审计 denyByPolicy + 返回 "🛡️ 策略拒绝: ..."
其他异常 → 审计 error + 返回 "工具执行失败: ..."
```

### 关键类

**HitlToolRegistry**：继承 ToolRegistry，只覆写 executeToolOutput——HITL 关闭时行为与父类完全相同。拒绝返回 `"[HITL] 操作已被拒绝：..."` 给模型。

**ApprovalPolicy**：`requiresApproval = DANGEROUS_TOOLS(write_file/execute_command/create_project/revert_turn) || isMcpTool(name)`——所有 mcp__ 工具默认审批。

**PathGuard（路径围栏）**：
```java
public Path resolveSafe(String input) {
    Path resolved = raw.isAbsolute() ? raw.normalize() : rootPath.resolve(raw).normalize();
    Path realResolved = resolveRealPath(resolved);   // 关键：解析符号链接
    if (!realResolved.startsWith(rootPath)) {
        throw new PolicyException("路径越界");
    }
    return realResolved;
}
// 三类越界：绝对路径 / ..穿越 / 符号链接逃逸
// 细节1：根路径自身也 toRealPath()（macOS /var → /private/var，不展开 startsWith 永远 false）
// 细节2：不存在的路径也能校验（write_file 创建新文件）：向上找最近存在祖先解析，剩余段接回
```

**CommandGuard**：黑名单 fast-fail（sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown）。注释明确：黑名单是反模式（永远列不全），但能拦住 LLM 容易踩的明显破坏性命令——辅助 HITL 而非主防线。

**AuditLog**：JSONL 按天分文件（audit-YYYY-MM-DD.jsonl），默认 ~/.chisel/audit/。三种 outcome：allow / denyByPolicy（approver=policy）/ error。HITL 拒绝 → denyByHitl。写入失败只 stderr 提示不抛出。

### 并行工具调度

```java
int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // 上限 4
List<Future<ToolExecutionResult>> futures =
        executor.invokeAll(tasks, toolBatchTimeoutSeconds, SECONDS);  // 批次统一超时
for (int i = 0; i < futures.size(); i++) {
    if (futures.get(i).isCancelled()) {
        results.add(ToolExecutionResult.timedOut(invocation, timeout));  // 超时回灌
    } else {
        results.add(futures.get(i).get());  // 按原顺序收集
    }
}
```
三点：invokeAll 批次超时 / 按 index 保序（回灌不乱）/ daemon 线程。

### 微信通道策略（非交互式默认拒绝）

| 工具 | 微信通道默认 | 放开方式 |
|------|------------|---------|
| execute_command | 拒绝 | 命令白名单精确命中 |
| write_file/create_project | 允许但 PathGuard 限定 workspace | 越界即拒 |
| revert_turn/浏览器切换 | 拒绝 | v1 不放开 |
| mcp__* | 拒绝 | server/tool 白名单 |
| 只读工具 | 直接执行 | — |

**Q&A 拷打**：
- "HITL 和 PathGuard 谁先？" → HITL 先；但用户无法批准策略拒绝的请求（PathGuard 抛异常时 HITL 点了允许也过不去）
- "拒绝为什么不抛异常？" → 工具的执行对象是 LLM，返回字符串让它换路；抛异常整个 Agent 崩
- "符号链接逃逸怎么防？" → toRealPath 解析后 startsWith 检查；根路径自身也要 toRealPath
- "审计三种 outcome？" → allow / denyByPolicy / error

---

## 第四部分：MCP 协议（技术岗必讲，含金量最高）

### 亮明身份
**没有用官方 mcp-sdk**（pom.xml 无 io.modelcontextprotocol），JSON-RPC 2.0 从零手写。

### 架构
```
McpServerManager（管理多个 server）
  ├─ McpClient（每 server 一个）
  │    └─ JsonRpcClient（协议核心）
  │         └─ McpTransport（传输层）
  │              ├─ StdioTransport（npx/uvx 子进程，stdin/stdout JSON 行）
  │              └─ StreamableHttpTransport（HTTP + SSE + OAuth）
  ▼
外部 MCP server（chrome-devtools 等）
```

### 请求-响应配对（核心）
```java
ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending;

public JsonNode request(String method, JsonNode params, long timeoutSeconds) {
    long id = ids.getAndIncrement();
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    scheduler.schedule(() -> {                 // 超时定时器
        if (pending.remove(id) != null) future.completeExceptionally(new TimeoutException());
    }, timeoutSeconds, SECONDS);
    transport.send(request);
    return future.get(timeoutSeconds + 1, SECONDS);
}
```

### 一个判断区分三种消息（协议灵魂）
```java
private void handleMessage(JsonNode message) {
    JsonNode idNode = message.get("id");
    if (idNode == null || idNode.isNull()) {
        // ① 无 id = 通知（notifications/tools/list_changed）→ 广播
        for (listener : notificationListeners) listener.accept(message);
        return;
    }
    long id = idNode.asLong();
    CompletableFuture<JsonNode> future = pending.remove(id);
    if (future != null) {
        // ② 有 id 且在 pending = 我的请求的响应 → complete
        ...;
        return;
    }
    // ③ 有 id 但不在 pending = server 主动发来的请求（sampling）
    //    必须异步派发，否则阻塞 reader 线程（自我死锁）
    requestDispatcher.submit(() -> dispatchServerRequest(id, method, params));
}
```

### 双 transport

**StdioTransport**：
- ProcessBuilder 起子进程，三个线程：send 写 stdin / stdout reader 解析 / stderr reader 记环形日志（防堵死）
- close：关 stdin（EOF 优雅 1s）→ SIGTERM → 2s 后 destroyForcibly

**StreamableHttpTransport**：
- buildRequest 带 Bearer token（如有）+ MCP-Protocol-Version + Mcp-Session-Id
- 401 + WWW-Authenticate 挑战 → tryRefresh（秒级）重试；失败抛 OAuthRequiredException
- SSE 解析：text/event-stream 按 data: 行切，空响应 swallow

### OAuth 授权码 + PKCE（为什么分两层）
```
运行中 401 → transport 层 refresh token 秒级刷新重试（不阻塞）
首次授权/刷新失败 → 抛 OAuthRequiredException → Manager 完整浏览器授权
完整流程：
  401 + WWW-Authenticate: MCP-OAuth
  → 发现授权服务器元数据（/.well-known/oauth-protected-resource → authorization_servers）
  → 读 RFC 8414 元数据（authorization_endpoint / token_endpoint）
  → PKCE：code_verifier + S256 challenge + state
  → 本地 loopback 随机端口 + 打开浏览器
  → 用户授权 → 回调 ?code=&state= → 校验 state（防 CSRF）
  → code + verifier 换 token → 存 ~/.chisel/mcp/oauth-tokens.json（600）
  → 重建 transport 重试 initialize
无浏览器：打印授权 URL 等回调
```

**Q：为什么分两层？** A：完整浏览器授权要几秒到几分钟，不能放 JSON-RPC 60s 超时内。运行中 401 用 refresh 秒级解决；只有 refresh 失败/首次才走长流程。

### sampling 反向调用
```
server → client: {id:42, method:"sampling/createMessage", params}
  → JsonRpcClient 发现 id 42 不在 pending → dispatchServerRequest
  → McpServerManager.registerSamplingHandler → SamplingHandler.handle
  → 有 toolCall 字段 → 拒绝（不执行工具）
  → 无 LLM → 拒绝
  → 转换 messages → llmClient.chat() → 返回 {role, content, model}
  → sendResponse(id=42, result)
```

### server 自动重启
```
onToolFailure（工具调用 IOException）→ 非 READY 不重启 / 已在恢复中幂等
→ 指数退避 1s→30s，最多 3 次 → 重启 + 重注册工具
→ 成功重置计数；超限标 ERROR（等手动 /mcp restart）
```

### 生命周期
```
startAll → 每 server 并行 start
  → createAndInitializeClient（transport + initialize，401 走 OAuth）
  → registerNotificationHandlers / registerSamplingHandler
  → listTools → 注册 mcp__{server}__{tool}
  → READY；启动超时 8s → 先进 CLI 后台继续
```

**Q&A 拷打**：
- "JSON-RPC 怎么配对？" → ConcurrentHashMap<id, Future> + ScheduledExecutor 超时
- "怎么区分响应和 server 请求？" → id 在不在 pending
- "为什么 sampling handler 必须异步？" → reader 线程同步跑 handler 会自我死锁（handler 内部调 LLM/发请求时阻塞 reader）
- "OAuth 为什么两层？" → 长流程不能放请求超时内
- "stdio 防 stderr 堵死？" → 单独线程 drain 环形缓冲

---

## 第五部分：记忆 + 上下文（Agent 岗必讲）

### 三套记忆
| 记忆 | 存储 | 内容 | 生命周期 | 入口 |
|------|------|------|---------|------|
| 短期 | 内存 ConversationMemory | 本轮对话 | 会话内 | 自动 |
| 长期 | JSON 文件 | `/save` 显式事实 | 跨会话 | /save |
| 项目 | PAI.md | 团队规则 | 随仓库 | /init |

### 长期记忆检索（关键词匹配，不用向量）
```java
// MemoryRetriever.computeRelevanceScore
if (contentLower.contains(queryLower)) return 1.0;        // 精确包含满分
double keywordScore = matchedWords / queryWords.size();    // 部分命中比例
double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);   // 时间衰减
return keywordScore * timeDecay;
// 长期记忆 ×1.2（显式保存更可信）
// Top10 → maxTokens 截断 → "## 相关长期记忆" Markdown → 替换 history[0]
```

**为什么不用向量**（面试必问）：
1. 规模小（几十条显式事实），全量遍历 + contains 微秒级
2. 内容关键词明确（/save 时人已用准确词），不需要语义近似
3. 检索高频（每轮触发），向量化成本高
**对比代码 RAG 用向量**：代码量大、模型猜不到符号名、需要语义近似。

### 双压缩（易混，必考）
| 压缩 | 压什么 | 触发 | 谁调 |
|------|--------|------|------|
| ContextCompressor | 短期记忆条目 | 短期记忆超预算 | MemoryManager |
| ConversationHistoryCompactor | 发给 LLM 的消息列表 | 接近 window 上限 | Agent.maybeCompactHistory |

**历史教训**（代码注释）：旧版只压短期记忆，但 Agent 实际发 conversationHistory，两者错位导致压缩从未真正缩短即将发送的 token——ConversationHistoryCompactor 是补丁。

**压缩算法**：
```
1. 估算 token，未达 trigger（window-20k-13k）返回
2. 找 user message 索引，保留最近 3 轮
3. system 之后、splitIdx 之前喂 LLM 摘要
4. 重建：[system]+[user("已压缩摘要"+summary)]+[assistant("好的请继续")]+[尾部]
关键：分割点在 user message 边界（不切断 tool_call/tool_result 成对协议）
```

### 上下文全按 window 派生
```java
// ContextProfile.from(llmClient)
window = max(MIN_WINDOW, llmClient.maxContextWindow());
agentBudget(window)          // 80% × window
compressionTriggerRatio(window)  // window - 20k - 13k
shortTermBudget(window)      // window × 0.45
memoryContextTokens(window)  // window × 0.005（封顶 5000）
window >= 32k               // 才注入 MCP resource 索引
```
**效果**：GLM 200k / DeepSeek 1M / Kimi 256k 走同一套逻辑，换模型不用改代码。

### Prompt 分层组装（易忽略但会问）
```java
append(base.md)                              // 稳定：核心规则
append(personalities/calm.md)                // 稳定：语调
append(modes/agent.md)                       // 稳定：模式
append(approvals/suggest.md)                 // 稳定：审批策略
append(runtimeContext())                     // volatile：日期/时区
append(Project Context: 项目记忆+长期记忆+外部) // volatile
append(Skills: skillIndex)                   // volatile
append(context-management.md)                // 稳定
append(handoff.md)                           // 稳定
```
**关键设计**：
- 稳定内容在前、volatile 在后 → 最大化 LLM 前缀缓存命中（KV prefix cache）
- conversationHistory[0] 是占位符，记忆检索结果运行时原地替换（不重建整段）
- 用户级覆盖：~/.chisel/prompts/base.md 替换内置文件，调 prompt 不用重编译

**Q&A 拷打**：
- "压缩触发为什么是 window-20k-13k？" → 20k 预留摘要输出，13k 安全缓冲（AUTOCOMPACT_BUFFER_TOKENS）
- "两道压缩区别？" → 一个压记忆条目，一个压真正发的消息列表，度量不同
- "为什么分割点在 user 边界？" → 不破坏 tool_call/tool_result 成对协议
- "记忆为什么不用向量？" → 规模小+关键词明确+高频低延迟+时间衰减需求
- "Prompt 为什么稳定在前 volatile 在后？" → 前缀缓存命中

---

## 第六部分：RAG 代码检索

### 索引链路
```
源码 → CodeChunker（JavaParser AST 分块：method/class/file，解析失败回退定长 2000）
     → EmbeddingClient.embedBatch（OpenAI 兼容批量：bge-m3 1024 维，2300 块 76s）
     → VectorStore（SQLite code_chunks + code_relations，批量事务插入）
索引排除 src/test（测试代码污染结果，chunk 3477→2303）
```

### 混合检索（CodeRetriever.hybridSearch）
```
查询 → Embedding 语义向量（余弦，topK*2 候选）
     + jieba 分词 → 关键词（SQL LIKE + 符号加权）
  → mergeResult 合并去重（同 file#name 取 max + 双命中 +0.1）
  → typeBoost（method +0.15 / class +0.10）
  → 排序 → limitPerFile(每文件 3)
语义失败 → 自动回退纯关键词（embedding 挂了不崩）
```

### 加权设计
```java
// boostKeywordMatch
if (nameLower.contains(keyword)) bonus += 1.0;       // 方法名/类名命中（最强）
else if (contentLower.contains(keyword)) bonus += 0.8; // 方法体/注释命中（英文）
// 泛中文词（工具/调用等）content boost 减半 +0.4（防大面积误命中）
```

### 工具分工
| 工具 | 输入 | 原理 | 适合 |
|------|------|------|------|
| grep_code | 精确字符串/正则 | 字符匹配（ripgrep 优先，Java 回退） | 知道确切符号 |
| glob_files | 文件名模式 | 文件名匹配 | 知道文件名 |
| search_code | 自然语言 | RAG 混合检索 | 只知道想要什么 |

### 基准测试（亮点：评测驱动开发）
```
CodeSearchGoldenSetTest：10 例确定性链路（grep→read 命中率 100%，P95 35ms）
RagBenchmarkTest：8 例模糊查询（search_code vs grep_code）
修复 5 个真实缺陷：测试代码污染索引 / 关键词加权不足 / 泛词误命中 / embedding 失败无回退 / 全失败静默成功
数字：RAG 8/8 = 100% vs grep 3/8 = 37.5%（bge-m3）；批量 embedding 448s→76s
```

---

## 第七部分：Skill 系统

### 定位
**Skill = 专家手册**：把"零散工具 + 决策指引"打包成可复用单元。两级加载：
- 索引段（轻）：启用 skill 的 name+description 进 system prompt（预算内）
- 正文（重）：SKILL.md 只在模型调 load_skill(name) 时注入下一轮 user message

### 架构
```
三层目录扫描（后者覆盖前者）：builtin（jar 解压）→ user（~/.chisel/skills）→ project（.chisel/skills）
SKILL.md：frontmatter（手写 YAML 子集，不引 SnakeYAML）+ 正文
运行链：Registry.reload → 索引段（IndexFormatter）→ 模型 load_skill → ContextBuffer.push
      → 下一轮 user message drain → "## 已加载 Skill：<name>" 前置
```

### 关键机制
```
SkillContextBuffer：
  drain 一次性消费（防跨轮重复注入）
  上限 3 个，超出 LRU 淘汰
  同 skill 重复 push → 替换 + 刷新到末尾
  /clear 复位
预算三层：
  单条 desc ≤500 codepoint
  启用 ≤20 个
  索引段 ≤4096 字符（超限硬截断 + stderr 警告）
安全：skill 内危险工具仍走 HITL（不提升权限）
角色隔离：三个 SubAgent 各自独立 buffer（防污染）
```

### 和工具/记忆的区别（必问）
| 维度 | Skill | 工具 | 记忆 |
|------|-------|------|------|
| 本质 | 怎么用能力的决策手册 | 能力（能做什么） | 事实（以前知道什么） |
| 触发 | 模型调 load_skill | 模型调工具 | 自动检索注入 |
| 注入点 | 下一轮 user message | 工具调用 | system prompt |
| 类比 | 专家手册 | 工具箱 | 笔记本 |

---

## 第八部分：浏览器 + 微信 + LSP + 快照 + Runtime（加分项）

### 浏览器（browser/ + chrome-devtools MCP）
- 默认 chrome-devtools MCP（28 工具），SPA/防爬墙 fallback
- `/browser connect` 复用登录态 Chrome（CDP），`/browser status/tabs/disconnect`
- 安全：SensitivePagePolicy 敏感页识别、改写型工具单步 HITL、shared 模式 close_page 硬保护（不能关非 ChiselCLI 创建的标签页）

### 微信 iLink 通道
- 远程入口不能弹窗 → 非交互式默认拒绝策略（WechatPolicyDecider）
- 消息分发分层：控制命令（/stop /pause /resume）旁路队列立即执行，普通消息单并发队列（防 /stop 排在要取消的消息后面）
- 绑定：扫码返回 ilink_user_id 作为 boundUserId；非绑定用户 drop + 审计
- 回复：sendmessage 分块，Markdown 子集归一化（标题转粗体、表格转键值）

### LSP 诊断注入
- post-edit hook：edit_file/apply_patch/write_file 成功后收集 publishDiagnostics
- 下一轮 LLM 请求前 flushPendingLspDiagnostics → 合成 user message 注入（最多 20 条）
- 惰性启动 LSP server（JDT LS/rust-analyzer/pyright/gopls），失败只降级不阻塞

### Side-Git 快照
- JGit 独立 side-git 仓库（~/.chisel/snapshots/），与用户 .git 隔离
- preTurn/postTurn 自动 commit，/restore <N> 回滚，revert_turn 工具
- max_snapshots 50，排除 .git/node_modules/target

### Runtime API + 后台任务
- DurableTaskManager：SQLite 任务队列（enqueued→running→completed/failed/canceled），Worker Pool 默认 2，重启自动重入队
- RuntimeApiServer：JDK HttpServer，仅 127.0.0.1，强制 API key
- OpenAI Assistants 兼容：POST /v1/threads、/turns、GET /events（SSE）

---

## 第九部分：TUI + 渲染

### 架构
```
Renderer（接口）
  ├─ InlineRenderer（默认）流式 TUI
  ├─ LanternaRenderer 全屏
  └─ PlainRenderer 兜底（终端不支持 ANSI）
选择：-Dchisel.renderer > CHISEL_RENDERER > inline；CHISEL_TUI=true 兼容映射 lanterna
```

### 关键机制
- **输出收敛**：全部走 renderer.stream()（Main/Plan/Orchestrator 都把输出流接进来），避免多组件抢 stdout
- **首屏**：installStartupScreen 挂 LineReader.CALLBACK_INIT，首次 readLine 用 printAbove 打印（防被重绘冲掉）
- **live thinking 区**：固定高度，只能清理自己刚打印的几行，不能 Display.update() 向上覆盖 transcript
- **底部 dock**：JLine Status 托管（不手写清屏），两层信息：上层模式+MCP/Skill，下层 model/ctx/token/cost/cwd
- **ctx vs in/out**：ctx = 下一轮将带入的上下文估算（即将花的钱）；in/out/cache = 最近任务 LLM 调用统计（已花的钱）——不混用

---

## 第十部分：面试讲述策略

### 开场（30 秒）
一句话定位 + 主线（ReAct 循环）+ 规模（34k 行/770 测试）。

### 主线（2-3 分钟）
一条消息走完的链路，重点 ReAct 循环 + 流式 + 取消 + 防死循环。

### 亮点选讲（按面试官）
- 技术岗 → MCP 手写协议（配对 + 双向区分 + OAuth 分层 + sampling + 重启）
- 后端岗 → 工具安全层（四层拦截 + 并行调度）+ Runtime API + 快照
- Agent 岗 → 记忆双压缩 + Skill + RAG 选型 + Prompt 分层

### 收尾（1 分钟）
- 承认边界：测试集小（8-10 例）、embedding 走 API（SiliconFlow bge-m3）、无沙箱（设计决策）
- 强调"评测驱动开发"：50%→100% 的 5 个真实缺陷修复过程
- 强调选型判断：记忆用关键词 vs 代码用向量（按数据性质）、黑名单是辅助 vs HITL 主防线、不引 SnakeYAML/mcp-sdk

---

## 附录：全局速查表

| 模块 | 一句话 | 技术点 |
|------|--------|--------|
| Agent | ReAct 循环 + 三层守护 | 停滞检测/协作取消/预算 |
| 工具 | 四层拦截 | HITL/PathGuard/CommandGuard/Audit |
| MCP | 手写 JSON-RPC | 配对/双transport/OAuth/sampling/重启 |
| 记忆 | 三套 + 双压缩 | 关键词+时间衰减/窗口自适应 |
| RAG | AST分块+混合检索 | bge-m3/符号加权/回退 |
| Prompt | 分层组装 | 稳定在前/volatile在后/前缀缓存 |
| Skill | 专家手册 | 两级加载/ContextBuffer/预算 |
| TUI | Renderer 三实现 | 输出收敛/Status dock |
| 安全 | 四层 + 策略 | 审计三态/非交互式拒绝 |
