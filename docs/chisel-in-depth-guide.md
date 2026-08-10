# ChiselCLI 全模块详解（从原理到代码）

> 这不是面试稿，是"把项目讲懂"的完整笔记。每个模块都讲：
> 解决什么问题 → 怎么设计 → 代码怎么实现 → 为什么这么做 → 细节坑。
> 建议按顺序读，每部分 20-30 分钟，配合 `src/main/java/com/chisel/` 下对应代码。

---

# 第一部分：ReAct 循环 —— 项目的心脏

## 1.1 它解决什么问题

你让 Agent"帮我看下项目结构"，Agent 不能一步到位——它需要：先看看有哪些文件 → 读几个关键文件 → 总结。**ReAct 就是让模型自己决定"下一步做什么"的循环**。

## 1.2 具体怎么工作的

想象一个循环：**问模型 → 模型说要做什么 → 做了 → 把结果告诉模型 → 再问 → ...直到模型说"我完成了"**。

代码里就是 `Agent.java` 的 `while(true)`：

```java
while (true) {
    // 1. 把"到目前为止的所有对话"发给模型
    LlmClient.ChatResponse response = llmClient.chat(conversationHistory, toolDefinitions, ...);
    
    // 2. 看模型怎么说：
    //    模型说"我要调用 list_dir 工具" → response.hasToolCalls() 为 true
    //    模型说"这是最终答案" → 没有 toolCalls，直接返回 content
    
    if (response.hasToolCalls()) {
        // 3. 执行模型要的工具
        List<ToolExecutionResult> results = executeToolCalls(response.toolCalls(), iteration);
        // 4. 把结果塞回对话历史
        for (ToolExecutionResult r : results) {
            conversationHistory.add(LlmClient.Message.tool(r.id(), r.result()));
        }
        // 5. 回到第 1 步，再问模型"现在看到结果了，下一步怎么办？"
        continue;
    }
    return response.content();  // 模型说完成了
}
```

**关键理解**：`conversationHistory` 是"唯一真相"。它记录着：

```
[system]    你是 ChiselCLI Agent...
[user]      帮我看下项目结构
[assistant] 我要用 list_dir 工具          ← 模型说要调工具
[tool]      当前目录包含 src、pom.xml...   ← 工具返回结果
[assistant] 我再用 read_file 读 pom.xml
[tool]      <pom.xml 内容>
[assistant] 项目结构如下：这是一个 Maven 项目...  ← 模型总结，结束
```

模型每次看到**完整的历史**，就知道自己已经做过什么、接下来该做什么。这就是 ReAct 的"思考-行动-观察"。

## 1.3 细节：为什么工具结果要按顺序回灌

模型说"调用 list_dir 和 read_file 两个工具"，会返回：

```json
{"tool_calls": [
  {"id": "call_1", "function": {"name": "list_dir", "arguments": "{}"}},
  {"id": "call_2", "function": {"name": "read_file", "arguments": "{\"path\":\"pom.xml\"}"}}
]}
```

执行后回灌：

```java
conversationHistory.add(Message.tool("call_1", "当前目录包含 src..."));
conversationHistory.add(Message.tool("call_2", "<pom.xml 内容>"));
```

**为什么必须带 `call_1` 这个 id？** 因为模型是靠 id 把"结果"和"我发出的请求"对应起来的。如果顺序乱了或 id 丢了，模型会困惑"这个结果到底是哪个工具返回的"。

**为什么并行执行后还要按原顺序收集？** 看 `ToolRegistry.executeTools`：

```java
List<Future<ToolExecutionResult>> futures = executor.invokeAll(tasks, timeout);
for (int i = 0; i < futures.size(); i++) {
    results.add(futures.get(i).get());  // 按 i 的顺序，不是完成的顺序
}
```

`invokeAll` 返回的 future 列表顺序和传入顺序一致，所以即使 `call_2` 先完成，结果也排在 `call_1` 后面——保证回灌顺序和模型请求顺序一致。

## 1.4 细节：流式输出是怎么"流"的

模型不是一次性吐出全部内容，而是像打字一样一段段来（SSE 流）。`AbstractOpenAiCompatibleClient` 处理：

```java
// 每次收到一个 chunk（一小段 JSON）
JsonNode delta = choice.path("delta");
String contentDelta = delta.path("content").asText("");
streamListener.onContentDelta(contentDelta);  // 立刻推给渲染器显示
```

**这就是为什么你看到"打字机效果"**：每收到一小段就显示。

**复杂的是 tool_calls 的流式合并**：模型说"我要调用 list_dir"这句话，在流里可能是这样拆开的：

```
chunk 1: {"delta": {"tool_calls": [{"index": 0, "function": {"name": "list"}}]}}
chunk 2: {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "_dir"}}]}}
chunk 3: {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"path\":"}}]}}
```

`mergeToolCallDeltas` 按 `index` 把碎片攒起来拼成完整参数。**如果不管 index 直接拼，会乱**。

另外还有 reasoning（思考过程）的兼容：不同 provider 字段名不同——
- DeepSeek：`reasoning_content`
- 部分模型：`reasoning`
- 有的：`reasoning_details`（数组）

`extractReasoningDelta` 把三种都兼容了，这就是"多模型适配"的细节。

## 1.5 细节：怎么防止模型死循环

模型可能犯傻：反复调用同一个工具、同一个参数，永远不结束。`AgentBudget` 做了三层防护：

```java
// 第一层：记录最近 N 轮的"工具名+参数"签名
String signature = signatureOf(toolCalls);  // 比如 "read_file:{\"path\":\"a.txt\"}"
recentToolSignatures.addLast(signature);
if (最近 3 轮签名全部相同) {
    stagnant = true;  // 判定死循环！
}
```

```java
// check() 三个退出条件
public ExitReason check() {
    if (stagnant) return STAGNATION_DETECTED;          // 死循环
    if (totalTokens >= tokenBudget) return TOKEN_BUDGET_EXCEEDED;  // 钱花完了
    if (iteration >= hardMaxIterations) return HARD_ITERATION_LIMIT;  // 轮数太多（默认50）
}
```

**这个"停滞检测"是很多 Agent 实现没考虑的坑**：模型卡在一个工具上反复重试，只有 token 预算兜底的话会浪费很多钱。签名去重能更早发现。

## 1.6 细节：用户按 ESC 取消是怎么生效的

```
Main 线程：              Agent 线程：
 进入 raw mode            while(true) {
 监听 ESC                  if (CancellationContext.isCancelled()) return "已取消";
 按下 ESC →                ...
   token.cancel() 设置flag  调 LLM（阻塞中...）
   future.cancel(true)     ...
```

**为什么"设置 flag"+"interrupt"双管齐下？** 因为：
- `Thread.interrupt()` 对**阻塞在 I/O 上**的线程有效（能打断 OkHttp 读流）
- 但 Agent 的**循环逻辑**（判断、拼字符串）不会被 interrupt 打断——所以要在每个循环边界检查 flag

这就是"协作式取消"：不是强杀线程，而是"请求取消 + 代码在安全点响应"。

## 1.7 细节：system prompt 是怎么动态变化的

Agent 构造时，`conversationHistory[0]` 是一条 system 消息。但每轮开始时，检索到的记忆要注入：

```java
// 每轮 run 开始
String memoryContext = memoryManager.buildContextForQuery(userInput, ...);
updateSystemPromptWithMemory(memoryContext);
// 实现：直接替换第 0 条
conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
```

**为什么要"替换第 0 条"而不是重新建列表？** 因为 system prompt 是"前缀"，LLM 的 prompt cache 是**前缀缓存**——前面部分不变才能命中缓存省钱。如果每次重建整段，缓存全失效。

---

# 第二部分：工具系统 + 安全层

## 2.1 工具的四要素

```java
tools.put("search_code", new Tool(
        "search_code",                                    // ① name：模型识别
        "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块...",  // ② description：模型选择依据
        createParameters(                                 // ③ parameters：JSON schema
                new Param("query", "string", "...", true),
                new Param("top_k", "integer", "...", false)
        ),
        args -> { ... }                                   // ④ executor：真正干活
));
```

**理解重点**：`description` 不是给人看的，是给模型看的——模型靠它决定"这个任务该用哪个工具"。所以 description 要写清楚"什么时候用、什么时候别用"。

每轮调 LLM 时，`toolRegistry.getToolDefinitions()` 把所有工具的 name/description/parameters 汇总成 schema 数组发给模型——模型看到 schema 就知道有哪些工具可用、各自干什么。

## 2.2 执行链四层拦截（项目的安全精髓）

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

## 2.3 HitlToolRegistry：透明拦截层

```java
public ToolOutput executeToolOutput(String name, String argumentsJson) {
    // HITL 未启用或该工具不需要审批 → 直接执行（零开销）
    if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
        return super.doExecuteTool(name, argumentsJson);
    }
    // 浏览器敏感页 → 单步审批
    // 已"全部放行"（按工具 or 按 MCP server）→ 直接执行
    if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
        return super.doExecuteTool(name, argumentsJson);
    }
    // 否则弹窗审批
    return executeAfterExplicitApproval(name, argumentsJson, null);
}
```

**为什么用继承而不是接口？** `HitlToolRegistry extends ToolRegistry`，只覆写 `executeToolOutput`——HITL 关闭时行为与父类完全相同，零额外开销。这是"透明拦截层"的设计：开不开启，代码路径一样，只是多一层判断。

**"全部放行"两个维度**：
- 按工具：`approvedAllByTool`——这次会话所有 read_file 都不问了
- 按 MCP server：`approvedAllByServer`——chrome-devtools 的所有工具都不问了（浏览器操作连续，一个个问太烦）

## 2.4 ApprovalPolicy：哪些工具要审批

```java
private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file", "execute_command", "create_project", "revert_turn");

public static boolean requiresApproval(String toolName) {
    return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);  // 所有 mcp__ 工具默认审批
}
```

**设计原则**：读操作（read_file/list_dir/grep_code）不需要确认（无副作用）；写/执行操作需要确认（有潜在破坏性）；外部 MCP 工具默认都要确认（不可信来源）。

## 2.5 PathGuard：路径围栏（防三类越界）

```java
public Path resolveSafe(String input) {
    Path resolved = raw.isAbsolute() ? raw.normalize() : rootPath.resolve(raw).normalize();
    Path realResolved = resolveRealPath(resolved);   // 关键：解析符号链接
    if (!realResolved.startsWith(rootPath)) {
        throw new PolicyException("路径越界: " + input + " 不在项目根之内");
    }
    return realResolved;
}
```

**三类越界**：
1. 绝对路径（`/etc/passwd`）→ `startsWith` 检查拦截
2. `..` 穿越（`../../etc/passwd`）→ `normalize()` 后检查
3. **符号链接逃逸**（项目内软链指向外部）→ `toRealPath()` 解析后检查

**两个细节坑**：
- **根路径自身也要 `toRealPath()`**：macOS 上 `/var` 实际是 `/private/var`，如果根是 `/var/xxx` 而解析后的目标是 `/private/var/xxx`，`startsWith` 永远 false（或者反过来永远 true）——必须先展开根。
- **不存在的路径也能校验**（write_file 创建新文件）：`resolveRealPath` 向上找最近的存在祖先解析，再把剩余段接回。

```java
private Path resolveRealPath(Path target) {
    Path existing = target;
    while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();   // 向上找存在的祖先
    }
    // 祖先 toRealPath + 剩余段接回
}
```

## 2.6 CommandGuard：命令快速拒绝

```java
// 黑名单：sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown
// rm 路径黑名单：匹配开头即可拦截
```

**代码注释里的坦诚**："黑名单是反模式（永远列不全），但能拦住 LLM 容易踩的明显破坏性命令"——**定位是辅助 HITL 而非主防线**。真正的防线是 HITL（人审）+ 路径围栏。

## 2.7 AuditLog：审计三态

```java
// 一行一条 JSON（JSONL），按天分文件 audit-YYYY-MM-DD.jsonl，默认 ~/.chisel/audit/
// 三种 outcome：
AuditEntry.allow(...)         // 成功
AuditEntry.denyByPolicy(...)  // 策略拒绝（approver=policy）
AuditEntry.error(...)         // 异常（approver=none）
// HITL 拒绝 → denyByHitl（approver=hitl）
```

**设计意图**（代码注释）：把 Agent 的"实际副作用"变成可回放的事实流——行为评估、差错复盘、监控告警的统一数据源。**写入失败只 stderr 提示不抛出**（审计故障不影响主流程）。

## 2.8 并行工具调度

```java
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    if (invocations.size() == 1) {  // 单工具直接执行，不建线程池
        ...;
    }
    int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // 上限 4
    ExecutorService executor = Executors.newFixedThreadPool(parallelism, daemonFactory);
    List<Future<ToolExecutionResult>> futures =
            executor.invokeAll(tasks, toolBatchTimeoutSeconds, SECONDS);  // 批次统一超时
    for (int i = 0; i < futures.size(); i++) {
        if (futures.get(i).isCancelled()) {
            results.add(ToolExecutionResult.timedOut(invocation, timeout));  // 超时结果回灌
        } else {
            results.add(futures.get(i).get());  // 按原顺序收集
        }
    }
}
```

**三个设计点**：
1. 单工具不走线程池（省开销）
2. `invokeAll(timeout)` 批次统一超时，超时任务被取消，**超时结果回灌给模型**（模型知道"这个工具超时了"）
3. 按 index 收集保证顺序（回灌历史不乱）

---

# 第三部分：MCP 协议（手写 JSON-RPC）

## 3.1 它解决什么问题

ChiselCLI 要调用外部 AI 工具（chrome-devtools 浏览器、filesystem 文件访问等）。这些工具通过 **MCP 协议**暴露——标准化的 JSON-RPC 2.0 通信。

**为什么手写不用 SDK**：官方 mcp-sdk 是 Java 的，但项目选择手写——为了理解协议本质、控制依赖、适配国内 provider 差异。

## 3.2 整体架构

```
McpServerManager（管理多个 server）
  ├─ McpClient（每 server 一个）
  │    └─ JsonRpcClient（协议核心：请求-响应配对）
  │         └─ McpTransport（传输层，二选一）
  │              ├─ StdioTransport（npx/uvx 子进程，stdin/stdout JSON 行）
  │              └─ StreamableHttpTransport（HTTP + SSE，支持 OAuth）
  ▼
外部 MCP server（chrome-devtools 等）
```

## 3.3 请求-响应配对（核心中的核心）

```java
ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending;

public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
    long id = ids.getAndIncrement();                     // 自增 id
    ObjectNode request = ...;                            // 构造 {jsonrpc:"2.0", id, method, params}
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);                             // 放入配对表

    scheduler.schedule(() -> {                           // 超时定时器
        if (pending.remove(id) != null) {
            future.completeExceptionally(new TimeoutException("JSON-RPC request timed out"));
        }
    }, timeoutSeconds, TimeUnit.SECONDS);

    transport.send(request);                             // 发出去
    return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);  // 等配对完成
}
```

**理解**：发请求时把 `id → Future` 放进 map；收响应时按 id 从 map 取出 Future 并 complete。`ConcurrentHashMap` 保证并发安全（多线程同时发请求）。

## 3.4 一个判断区分三种消息（协议灵魂）

```java
private void handleMessage(JsonNode message) {
    JsonNode idNode = message.get("id");
    // ① 无 id = 通知（notifications/tools/list_changed 等）→ 广播给监听者
    if (idNode == null || idNode.isNull()) {
        for (listener : notificationListeners) listener.accept(message);
        return;
    }
    long id = idNode.asLong();
    CompletableFuture<JsonNode> future = pending.remove(id);
    // ② 有 id 且在 pending = 我的请求的响应 → complete future
    if (future != null) { ...; return; }
    // ③ 有 id 但不在 pending = server 主动发来的请求（sampling/createMessage）
    //    必须异步派发，否则阻塞 reader 线程（自我死锁）
    requestDispatcher.submit(() -> dispatchServerRequest(id, method, params));
}
```

**这个"id 在不在 pending"的判断是整个协议的灵魂**——同一套消息流里区分了：
- 通知（无 id）
- 我的请求的响应（id 在 pending）
- server 发给我的请求（id 不在 pending）

## 3.5 双 transport

### StdioTransport（本地子进程）

```java
public StdioTransport(String command, List<String> args, Map<String,String> env, Path workingDir) {
    ProcessBuilder builder = new ProcessBuilder(commandLine);
    this.process = builder.start();   // 起 npx/uvx 子进程
    // 三个线程协作：
    //   send()        写 stdin（JSON 行 + \n + flush）
    //   stdout reader 读 stdout 解析 JSON 行 → 分发给监听者
    //   stderr reader 读 stderr 记环形日志（防管道堵死）
}
```

**为什么 stderr 要单独 drain？** 子进程往 stderr 写满了管道缓冲区会**阻塞**——如果没人读 stderr，子进程可能卡死。单独线程持续读并记录到环形缓冲（最多 200 行）就不会堵。

**close 的优雅关闭**：
```java
// 1. 关 stdin → 子进程读到 EOF（优雅退出窗口 1 秒）
// 2. 超时 → SIGTERM（destroy）
// 3. 再 2 秒 → destroyForcibly（SIGKILL）
```

### StreamableHttpTransport（远程 HTTP）

```java
public void send(JsonNode message) throws IOException {
    Request request = buildRequest(message, accessToken());   // 带 Bearer token（如有）
    Response response = client.newCall(request).execute();

    if (response.code() == 401 && tokenStore != null) {        // OAuth 挑战
        OAuthChallenge challenge = OAuthChallenge.parse(response.header("WWW-Authenticate"));
        String refreshed = tryRefresh(challenge);              // 秒级 refresh 重试
        if (refreshed != null) { ...重试... }
        else { throw new OAuthRequiredException(url, challenge); }  // 上抛做完整授权
    }
    // 解析响应：SSE（text/event-stream）或普通 JSON → 分发给监听者
    // 记录 Mcp-Session-Id 供后续请求携带
}
```

**session 管理**：HTTP transport 是"有状态"的——server 在响应头返回 `Mcp-Session-Id`，后续请求要带上（否则 server 不认）。DELETE 请求用于关闭 session。

## 3.6 OAuth 授权码 + PKCE（为什么分两层）

**触发**：server 返回 `401 + WWW-Authenticate: MCP-OAuth`（或 `Bearer resource_metadata=`）。

**两层处理（关键设计）**：
1. **运行中 401** → transport 层用 refresh token **秒级刷新后重试一次**（不阻塞）
2. **刷新失败 / 首次授权** → 抛 `OAuthRequiredException`，`McpServerManager` 捕获后走**完整浏览器授权**（长流程，不在请求超时内执行）

**完整授权流程**（McpOAuthClient）：
```
401 + WWW-Authenticate: MCP-OAuth
  → 发现授权服务器元数据（/.well-known/oauth-protected-resource → authorization_servers）
  → 读 RFC 8414 元数据（authorization_endpoint / token_endpoint）
  → 生成 PKCE code_verifier + S256 challenge + state
  → 本地开 loopback 随机端口，打开浏览器到授权页
  → 用户授权 → 浏览器回调 http://127.0.0.1:port/callback?code=xxx&state=yyy
  → 校验 state（防 CSRF）→ 用 code + code_verifier 换 token
  → 存 ~/.chisel/mcp/oauth-tokens.json（权限 600）
  → 重建 transport（带 token）重试 initialize
```

**为什么分两层？** 完整浏览器授权要几秒到几分钟（等用户操作），不能放进 JSON-RPC 请求的 60s 超时内。运行中 401 用 refresh token 秒级解决；只有 refresh 失败/首次才走长流程。

**PKCE 细节**：`code_verifier` 是随机字符串，`code_challenge = base64url(SHA256(code_verifier))`。换 token 时把 `code_verifier` 带上，server 验证它和之前发的 challenge 匹配——防止别人截获 code 后用。

**state 防 CSRF**：授权 URL 带随机 state，回调时校验 state 和发出的一致——防止攻击者伪造回调。

## 3.7 sampling 反向调用（server → client 请求）

**场景**：MCP server 自己没模型，请求 ChiselCLI 用 LLM 生成内容。

```
server → client: {"id": 42, "method": "sampling/createMessage", "params": {...}}
    │
    ▼  （JsonRpcClient 发现 id 42 不在 pending → dispatchServerRequest）
    ▼
McpServerManager.registerSamplingHandler
    │
    ▼
SamplingHandler.handle(params)
    ├─ 有 toolCall 字段 → 拒绝（不执行工具）
    ├─ 无 LLM → 拒绝
    └─ 有 LLM → 转换 messages → llmClient.chat() → 返回 {role, content, model}
    │
    ▼
JsonRpcClient.sendResponse(id=42, result)  ← 作为响应发回给 server
```

**安全**：终端默认允许但写审计；微信等非交互通道默认拒绝（server 请求带 toolCall 字段直接拒绝，不执行工具）。

## 3.8 server 自动重启（recovery）

```java
// ServerRecoveryManager
// 触发：工具调用抛 IOException（连接断 / 进程退出）
public void onToolFailure(String serverName, Throwable cause) {
    if (server.status() != READY) return;    // 非 READY 不重启
    if (!state.markScheduled()) return;      // 已在恢复中，幂等（防重复调度）
    executor.submit(() -> recover(serverName, state, cause));
}

// 退避：1s → 2s → 4s → 8s → 16s → 30s（封顶），默认最多 3 次
// 成功后重置计数；连续失败超限 → ERROR（等手动 /mcp restart）
// /mcp 状态行展示 "restarting (attempt n/3)"
```

**幂等设计**：连续 5 次失败只调度一次恢复循环（markScheduled 去重）——否则每个失败都起一个恢复线程会雪崩。

---

# 第四部分：记忆 + 上下文工程

## 4.1 三套记忆系统（各自独立）

| 系统 | 存储 | 内容 | 生命周期 | 入口 |
|------|------|------|---------|------|
| 短期记忆 | 内存（ConversationMemory） | 本轮对话上下文 | 会话内 | 自动 |
| 长期记忆 | `~/.chisel/memory/long_term_memory.json` | 用户显式保存的事实 | 跨会话 | `/save` |
| 项目记忆 | `PAI.md` 文件 | 团队规则/项目事实 | 随仓库 | `/init` |

## 4.2 长期记忆检索（关键词匹配，不用向量）

```java
// MemoryRetriever.computeRelevanceScore（打分）
private double computeRelevanceScore(MemoryEntry entry, String query) {
    // 1. 精确匹配 → 满分
    if (contentLower.contains(queryLower)) return 1.0;
    // 2. 关键词匹配：query 分词后命中比例
    double keywordScore = matchedWords / queryWords.size();
    // 3. 时间衰减：24 小时内 1.0 → 0.5，越新分越高
    double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);
    return keywordScore * timeDecay;
}
// 长期记忆额外 ×1.2（显式保存的精炼事实更可信）
// 排序后取 Top10 → 按 maxTokens 预算截断 → 注入 system prompt
```

**为什么不用向量（RAG）？** 这是面试必问的设计决策：
1. **规模小**：长期记忆就几十条（`/save` 显式存的稳定事实），全量遍历 + contains 微秒级。给几十条数据建 embedding 索引是杀鸡用牛刀
2. **内容特性**：记忆是用户显式写的**关键词明确**的事实（"项目用 Java 17"），`/save` 的时候人已经用了准确词，不需要语义模糊匹配
3. **成本**：Embedding 每次检索要调 API/本地模型，而记忆检索是**每轮对话都触发**的高频操作——用向量反而又慢又花钱
4. **时间衰减是记忆特有的**：越近的记忆越相关（向量没有时间概念）

**对比代码 RAG 用向量**：代码量大（上千 chunk）、模型猜不到符号名（"限流"→RateLimiter）、需要语义近似——所以代码用向量，记忆用关键词。**两个场景，两套算法，各取所需**。

## 4.3 双压缩机制（易混，必考）

| 压缩 | 压什么 | 触发 | 谁调 |
|------|--------|------|------|
| `ContextCompressor` | **短期记忆**（ConversationMemory 条目） | 短期记忆超预算 | MemoryManager |
| `ConversationHistoryCompactor` | **发给 LLM 的消息列表**（conversationHistory） | 接近 window 上限 | Agent.maybeCompactHistory() |

**历史教训**（代码注释明确写了）：旧版只压短期记忆，但 Agent 实际发的是 conversationHistory——两者错位导致压缩从未真正缩短即将发送的 token。`ConversationHistoryCompactor` 是补丁。

**压缩算法**：
```
1. 估算 conversationHistory token，未达 trigger（window-20k-13k）直接返回
2. 找所有 user message 索引，保留最近 3 轮
3. system 之后、splitIdx 之前的全部消息喂 LLM 摘要
4. 重建：[system] + [user("已压缩摘要"+summary)] + [assistant("好的，请继续")] + [保留的尾部]
关键约束：分割点必须在 user message 边界（不切断 tool_call/tool_result 成对协议）
```

**为什么分割点在 user 边界？** tool_call 和 tool_result 是成对的（靠 toolCallId 关联），如果切在中间，模型下一轮看到"半对"消息会困惑，无法理解工具结果。

**为什么 trigger 是 window-20k-13k？** 20k 预留摘要输出空间（压缩本身要生成摘要，要留 token），13k 是安全缓冲（AUTOCOMPACT_BUFFER_TOKENS）——防止压缩动作本身把窗口撑爆。

## 4.4 上下文全按 window 派生（自适应）

```java
// ContextProfile.from(llmClient)
public static ContextProfile from(LlmClient llmClient) {
    int window = Math.max(MIN_WINDOW, llmClient.maxContextWindow());
    return new ContextProfile(
            window,
            agentBudget(window),              // 80% × window
            compressionTriggerRatio(window),  // window - 20k - 13k
            shortTermBudget(window),          // window × 0.45
            memoryContextTokens(window),      // window × 0.005（封顶 5000）
            window >= MCP_RESOURCE_INDEX_MIN_WINDOW,   // ≥32k 才注入 MCP resource 索引
            llmClient.supportsPromptCaching(),
            llmClient.promptCacheMode()
    );
}
```

**设计原则**（代码注释）：**没有长/短/平衡分档，所有参数都是 maxContextWindow 的简单函数**。GLM 200k 和 DeepSeek 1M 走同一套逻辑，只是 window 不同导致触发时机不同——换模型不用改代码。

---

# 第五部分：RAG 代码语义检索

## 5.1 索引链路

```
源码文件
  → CodeChunker（JavaParser AST 分块）
  → EmbeddingClient.embedBatch()（OpenAI 兼容批量：bge-m3 1024 维）
  → VectorStore（SQLite：code_chunks 表 + code_relations 表，批量事务插入）
```

### 分块（为什么 AST 而不是固定长度）

```java
// CodeChunker：用 JavaParser 解析 AST，按"类/方法"粒度切
CompilationUnit cu = parser.parse(content);
cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
    chunks.add(CodeChunk.classChunk(filePath, className, classHeader, start, end));  // 类级
    clazz.getMethods().forEach(method -> {
        chunks.add(CodeChunk.methodChunk(                 // 方法级（最小检索单元）
            filePath, className + "." + methodSignature, methodContent, start, end));
    });
});
// 解析失败（非 Java / 特殊语法）→ 回退到按 2000 字符定长分段
```

**为什么 AST 分块而不是固定长度？** 固定长度会把一个方法切成两半，检索"这个方法怎么实现"时只能拿到半截。方法粒度最贴合"回答某个方法怎么实现"的场景。

**方法名带类名前缀**：`ClassName.methodSignature`——检索"ClassName.method"能精确命中。

### 批量 Embedding（性能优化）

```java
// EmbeddingClient.embedBatch：OpenAI 兼容 API 支持 input 数组
// 2300 块逐条调用 → 448 秒；批量 32 条/次 → 76 秒（6 倍提速）
// Ollama 本地路径退化为逐条（本地快，无需批量）
```

## 5.2 混合检索（不是纯向量）

```java
public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
    Map<String, SearchResult> merged = new LinkedHashMap<>();
    // 1. 语义检索：查询向量和所有 chunk 向量算余弦相似度，取 topK*2 候选
    for (SearchResult result : semanticSearch(query, topK * 2)) mergeResult(merged, result, ...);
    // 2. 关键词检索：jieba 分词 → SQL LIKE 匹配 + 符号加权
    for (String keyword : RagQueryTokenizer.tokenize(query)) {
        for (SearchResult result : keywordSearch(keyword)) {
            mergeResult(merged, boostKeywordMatch(result, keyword), ...);
        }
    }
    // 3. 代码类型加分：method +0.15 / class +0.10（方法比文件更能回答"怎么实现"）
    // 4. 排序 → limitPerFile(每文件最多 3 条，保证多样性)
}
```

**为什么混合检索？** 纯向量对精确符号（类名/方法名）匹配弱——embedding 是语义近似，"stagnant"和"AgentBudget.stagnant"的向量不一定近。关键词 LIKE + 符号加权兜底精确匹配。

### 符号命中加权（核心调优）

```java
private VectorStore.SearchResult boostKeywordMatch(result, keyword) {
    boolean pureHanKeyword = keyword 全是中文;
    double contentBoost = pureHanKeyword ? 0.4 : 0.8;  // 泛中文词减半
    double bonus = 0.0;
    if (nameLower.contains(keyword)) bonus += 1.0;       // 方法名/类名命中（最强）
    else if (contentLower.contains(keyword)) bonus += contentBoost;  // 方法体/注释命中
    if (fileLower.contains(keyword)) bonus += 0.2;
    ...
}
```

**为什么泛中文词减半？** "工具""调用""代码"这类词在无数代码块注释里出现，如果给足权重会大面积误命中（所有含"工具"的 chunk 都加分），把真正的符号命中挤到同分并列。这是实测踩过的坑（benchmark 从 50% 提分时发现）。

### 语义失败自动回退

```java
try {
    for (SearchResult result : semanticSearch(query, semanticLimit)) mergeResult(...);
} catch (Exception e) {
    // embedding 服务失败（限流/超时/未配置）→ 回退到纯关键词路径
    // 检索可用性优先于语义精度
    log.warn("semantic search failed, falling back to keyword path: {}", e.getMessage());
}
```

**为什么回退不报错？** embedding 服务是外部依赖（API/本地模型），会抖动。如果 embedding 挂了 search_code 直接失败，整个 Agent 的代码检索能力就没了——回退到关键词路径至少能用。

## 5.3 工具分工（系统提示词明确）

| 工具 | 输入 | 原理 | 适合 |
|------|------|------|------|
| `grep_code` | 精确字符串/正则 | 字符匹配（ripgrep 优先，Java 回退） | 知道确切符号 |
| `glob_files` | 文件名模式 | 文件名匹配 | 知道文件名 |
| `search_code` | 自然语言 | RAG 混合检索 | 只知道想要什么 |

**为什么模型需要三个工具？** 因为"找代码"有三种情况：
- 你知道符号（`MAX_RETRY`）→ grep 秒定位
- 你知道文件名（`Auth*`）→ glob
- 你只知道想要什么（"用户登录的实现"）→ search_code（因为模型不知道类名是 LoginController 还是 AuthService）

## 5.4 基准测试（评测驱动开发）

```
CodeSearchGoldenSetTest：10 例确定性链路（grep→read 命中率 100%，P50 8ms，P95 35ms）
RagBenchmarkTest：8 例模糊查询（search_code vs grep_code）

修复的 5 个真实缺陷（benchmark 50% → 100%）：
1. 索引包含 src/test（测试代码挤占生产代码排名）→ 排除 test 目录（chunk 3477→2303）
2. 关键词命中（content 内）权重太低被语义淹没 → name +1.0 / content +0.8
3. 泛中文词大面积误命中造成同分并列 → 扩充停用词 + 泛词减半
4. embedding 失败时 search_code 整体崩溃 → 语义失败回退关键词
5. embed 全失败时索引静默返回"成功 0 块" → 显式报错

数字：RAG 8/8 = 100% vs grep 3/8 = 37.5%（bge-m3）
```

---

# 第六部分：Prompt 分层架构

## 6.1 为什么分层

system prompt 不是一段写死的字符串，而是**分层组装**——因为不同内容稳定性不同、变化频率不同、KV 缓存需求不同。

```java
// PromptAssembler.assemble（固定顺序）
append(base.md)                               // ① 稳定：核心规则
append(personalities/calm.md)                 // ② 稳定：语调
append(modes/agent.md)                        // ③ 稳定：模式
append(approvals/suggest.md)                  // ④ 稳定：审批策略
append(runtimeContext())                      // ⑤ volatile：日期/时区
append(Project Context: 项目记忆+长期记忆+外部) // ⑥ volatile
append(Skills: skillIndex)                    // ⑦ volatile
append(context-management.md)                 // ⑧ 稳定：上下文管理
append(handoff.md)                            // ⑨ 稳定：交接
```

## 6.2 两个关键设计

**① 稳定内容在前、volatile 在后 → 最大化前缀缓存命中**

LLM 的 prompt cache 是**前缀缓存**（KV cache 按前缀复用）。前面 1-4 层是稳定不变的（base/语调/模式/审批），只有 5-7 层（日期/记忆/Skill 索引）每次变。这样每次请求时，前面大部分内容命中缓存——省钱、省延迟。

**② conversationHistory[0] 是占位符，运行时原地替换**

```java
// Agent 构造时
conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));

// 每轮 run 开始
String memoryContext = memoryManager.buildContextForQuery(userInput, ...);
conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
```

不重建整段历史，只替换第 0 条——**保持前缀稳定，缓存不失效**。

## 6.3 用户可覆盖

```
~/.chisel/prompts/base.md         → 替换内置 base.md
~/.chisel/prompts/modes/agent.md  → 覆盖特定模式
.chisel/prompts/...               → 项目级更高优先级
```

**价值**：调 prompt 从"改 Java 源码 + 重编译"变成"改 Markdown 文件"——prompt 调优可以脱离代码。

## 6.4 模式文件（PromptMode）

```
modes/agent.md           → ReAct
modes/plan.md            → Plan task executor
modes/planner.md         → Planner
modes/team-planner.md    → Multi-Agent 规划者
modes/team-worker.md     → Multi-Agent 执行者
modes/team-reviewer.md   → Multi-Agent 检查者
```

三个 SubAgent 角色（Planner/Worker/Reviewer）各自用不同的模式文件——角色提示词隔离。

---

# 第七部分：Skill 系统

## 7.1 为什么做（设计动机）

**痛点**：工具越来越多（11 内置 + 60+ MCP），决策指引（"什么时候用浏览器 MCP"）如果写死在 system prompt：
- prompt 爆炸（token 贵，稀释注意力）
- 改一次要重编译
- 无法按场景按需展开

**方案**：Skill 分两级——
- **索引段**（轻）：所有启用 skill 的 `name + description` 进 system prompt（预算内）
- **正文**（重）：完整 SKILL.md 只在模型调用 `load_skill(name)` 时注入下一轮 user message

**类比**：system prompt 里只有"目录"，模型看到目录觉得任务匹配 → 调 load_skill 翻到那一页 → 下一页 user message 里带着完整正文。

## 7.2 架构

```
三层目录扫描（后者整体覆盖前者同名 skill）
  ├─ builtin：jar 内置（SkillBuiltinExtractor 解压到 ~/.chisel/skills-cache/）
  ├─ user：   ~/.chisel/skills/<name>/SKILL.md
  └─ project：<projectDir>/.chisel/skills/<name>/SKILL.md

SKILL.md 结构：
  ---                    ← frontmatter（手写 YAML 子集解析，不引 SnakeYAML）
  name: web-access
  description: ...       ← 进索引段（≤500 codepoint）
  version: 1.0.0
  author: ...
  tags: [...]
  ---
  # 正文（markdown）    ← load_skill 时注入
```

## 7.3 SkillContextBuffer（注入缓冲区，核心）

```java
private static final int MAX_SKILLS = 3;   // 同一会话最多 3 个 skill body
private final Map<String, String> entries = new LinkedHashMap<>();  // 保序（LRU）

public synchronized void push(String skillName, String body) {
    entries.remove(skillName);   // 同一 skill 重复 push → 替换旧 body 并刷新到末尾
    entries.put(skillName, body);
    while (entries.size() > MAX_SKILLS) {
        String oldest = entries.keySet().iterator().next();  // LRU 淘汰最旧
        entries.remove(oldest);
    }
}

public synchronized String drain() {   // 一次性消费（防跨轮重复注入）
    // 组装成 "## 已加载 Skill：<name>\n<body>\n---\n"
    entries.clear();
    return sb.toString();
}
```

**关键约束**：
- `drain()` 是**一次性消费**——取走即清空，防止同一 skill 内容在多轮重复注入（如果不清空，每轮 user message 都会带着 skill body，浪费 token）
- **最多 3 个**，超出 LRU 淘汰最旧
- 同一 skill 重复 push → 替换 + 刷新到末尾（不重复）

## 7.4 预算控制（SkillIndexFormatter）

```java
public static final int MAX_DESCRIPTION_CODEPOINTS = 500;  // 单条描述 ≤500
public static final int MAX_ENABLED_SKILLS = 20;           // 启用上限 20
public static final int MAX_INDEX_BYTES = 4096;            // 索引段 ≤4KB
```

三层预算，超限硬截断 + stderr 警告——防止索引段把 system prompt 撑爆。

## 7.5 与工具/记忆的区别（理解核心）

| 维度 | Skill | 工具（Tool） | 记忆（Memory） |
|------|-------|-------------|---------------|
| 本质 | **怎么用能力的决策手册** | 能力（能做什么） | 事实（以前知道什么） |
| 触发 | 模型调 load_skill | 模型调工具 | 自动检索注入 |
| 注入点 | 下一轮 user message | 工具调用 | system prompt |
| 生命周期 | 会话内（3 个上限） | 常驻 | 跨会话 |
| 类比 | 专家手册 | 工具箱 | 笔记本 |

**例**：web-access Skill 不提供抓取能力（web_fetch 工具提供），它告诉模型"什么时候该用浏览器、什么时候该用 web_fetch、微信文章有哪些已知陷阱"。

## 7.6 安全边界

- Skill 内调用危险工具**仍走正常 HITL/策略层**（execute_command 工具维度全放行、浏览器 MCP 按 server 维度）——**不给 Skill 单独审批维度**
- 三个 SubAgent（Planner/Worker/Reviewer）**各持一个独立 SkillContextBuffer**——避免角色间提示词污染
- load_skill 正文注入时截断到 5KB（超出提示 `/skill show <name>` 看完整）

---

# 第八部分：多 Agent 编排

## 8.1 三角色

```
规划者（Planner）→ 拆解任务成步骤
执行者（Worker×2）→ 并行执行步骤（内部也是 ReAct）
检查者（Reviewer）→ 审查执行结果，未通过重试
```

## 8.2 审查循环（fail-safe 设计）

```java
// AgentOrchestrator
while (true) {
    List<ExecutionStep> executable = getExecutableSteps(steps);  // 找可执行步骤（依赖满足）
    if (executable.isEmpty()) break;
    // 单步 → 串行流式；多步 → 并行执行
    ...
    runStep(step, steps, retryCount, worker, reviewer, context, out);  // 执行+审查
}

// 审查结果解析（fail-safe：默认不通过）
boolean parseReviewApproval(String reviewContent) {
    // 1. 先试 JSON 解析（{approved: true/false}）
    // 2. 解析失败 → 找关键词：有否定词 → false；无肯定词 → false
    // 3. 都不满足 → 默认拒绝（避免放行有问题的结果）
}
```

**设计意图**（代码注释）：审查者的输出不可靠（LLM 输出），解析失败时**默认不通过**——宁可多跑一轮 worker 重做，也不放行有问题的结果。这是"质量门禁"的可靠性设计。

**重试**：retryCount 记录每步重试次数，超过上限保留当前结果并提示（不无限重试）。

---

# 第九部分：TUI + 渲染

## 9.1 渲染架构

```
Renderer（接口）
  ├─ InlineRenderer（默认）—— 流式 TUI（Claude Code 风格）
  ├─ LanternaRenderer    —— 全屏 TUI（CHISEL_RENDERER=lanterna）
  └─ PlainRenderer       —— 纯文本兜底（终端不支持 ANSI 时）
选择：-Dchisel.renderer > CHISEL_RENDERER > inline；CHISEL_TUI=true 兼容映射 lanterna
```

## 9.2 输出收敛原则（为什么统一走 Renderer）

```
❌ 错误：Agent / Planner / Orchestrator 各自 System.out.println
        → 多个组件直接争抢终端光标，输出交错
✅ 正确：全部走 renderer.stream()
        → 单点控制，inline 实现用 printAbove/Status 管理终端布局
```

## 9.3 InlineRenderer 关键机制

**① 首屏不丢**（installStartupScreen）：
```java
// 首屏通过 installStartupScreen 挂到 LineReader.CALLBACK_INIT
// 首次进入 readLine 时用 printAbove 一次性打印完整 Banner + tips
// 避免：直接 stdout 打印的 logo 被 LineReader 首次重绘滚出可视区域
```

**② 输出优先 printAbove**：
```java
// 当 LineReader.isReading() 为 true 时
// Renderer.stream() 的完整行输出优先通过 LineReader.printAbove 显示在输入行上方
// 未绑定 / 非读取态 / 测试路径 → 回退到原 PrintStream
```

**③ live thinking 区**：
```java
// 固定高度 live 区动态显示 "Thinking..." 和灰色竖线 reasoning 预览
// 约束：只能清理自己刚打印的几行
// 不能用独立 JLine Display.update() / CLEAR_TO_EOS 向上覆盖 transcript
// content 或 tool call 开始前先清掉 live 区 → 完整 reasoning 引用块落到正文区
```

## 9.4 底部 dock（BottomStatusBar）

**JLine Status 托管**（不是手写清屏）：
```java
status = Status.getStatus(terminal);
// 由 JLine 维护滚动区域和状态行位置
// 不再手写 \n / moveUp / CLEAR_TO_EOS
```

**两层信息**：
```
上层：模式（YOLO/HITL）+ MCP/Skill 摘要
下层：Auto Model / model / phase / ctx 百分比 + token / cost / elapsed / cwd
```

**ctx vs in/out（易混）**：
- `ctx` = **下一轮仍会带入请求的上下文估算**（即将花的钱）
- `in/out/cache` = 最近任务 LLM 调用统计（已花的钱）
- 二者不混用

## 9.5 输入交互（JLine LineReader）

- **ChiselHighlighter**：输入实时高亮（slash 命令、@ 引用、@image:、敏感词、危险 shell 片段）——只做视觉提示，不混入提交文本
- **ChiselCompleter**：上下文补全（/model provider、/mcp 子命令、@image: 路径、@server:uri）——统一出口
- **ChiselHistory**：输入历史持久化，忽略空白/重复/密钥/base64 图片/超长输入

---

# 第十部分：支线模块

## 10.1 浏览器（browser/ + chrome-devtools MCP）

- 默认 chrome-devtools MCP（28 工具：导航/输入/调试/网络/性能/模拟），SPA/防爬墙 fallback
- `/browser connect` 复用登录态 Chrome（CDP 远程调试端口），`/browser status/tabs/disconnect` 管理
- **安全**：SensitivePagePolicy 敏感页识别（settings/密码等）、改写型工具单步 HITL、shared 模式 close_page 硬保护（不能关非 ChiselCLI 创建的标签页）

## 10.2 微信 iLink 通道（wechat/）

**定位**：远程入口，用户通过微信给 ChiselCLI 发消息。**默认关闭**（显式 /wechat 才启动）。

**核心：非交互式默认拒绝策略**（远程聊天无法弹窗等人审）：
```
只读工具 → 直接执行
execute_command → 必须命中命令白名单，否则拒绝
write_file → 允许但 PathGuard 限定 workspace
revert_turn / 浏览器会话切换 → 拒绝
mcp__* → 必须命中 MCP 白名单
```

**消息分发分层**（防控制命令竞态）：
```
getupdates long-poll
  → 鉴权（boundUserId 匹配？否则 drop + 审计 unbound_drop）
  → 分类：
      ├─ 控制命令（/stop /pause /resume /status）→ 立即执行，旁路队列
      └─ 普通消息 → 单并发 turn 队列
```
**为什么旁路**：/stop 如果排进队列，会排在它要取消的消息后面永远来不及执行。

**取消复用 ReAct 的 cancellation token**（不新造 interrupt 机制）。

## 10.3 LSP 诊断注入（lsp/）

**链路**：
```
edit_file / apply_patch / write_file 成功
  → LspHooks（挂 ToolRegistry 执行后路径）
  → LspManager.runPostEditLspHook(file)
      → 按语言惰性启动 LSP server（JDT LS for Java / rust-analyzer / pyright / gopls）
      → textDocument/didOpen → didChange → 收集 publishDiagnostics
  → 下一轮 LLM 请求前 flushPendingLspDiagnostics()
      → 诊断作为合成 user message 注入 conversationHistory
```

**设计**：惰性启动（首次编辑才起 server）+ 优雅降级（失败只打日志不阻塞）+ 单次最多 20 条诊断。

## 10.4 Side-Git 快照（snapshot/）

**定位**：每个 turn 前后自动快照，可一键回滚，**不污染用户 .git**。

```java
// SideGitManager：~/.chisel/snapshots/<project_hash>/<worktree_hash>/.git
// 独立 side-git 仓库（JGit 纯 Java，与用户 .git 完全隔离）
// preTurnSnapshot：turn 前 commit，标记 "pre-turn <turn_id>"
// postTurnSnapshot：turn 后异步 commit，标记 "post-turn <turn_id>"
```

**入口**：`/restore <N>` 从最近 N 个 pre-turn 快照恢复；`revert_turn` 工具（Agent 自己判断改坏了要撤销）；策略 max_snapshots 50 / 排除 .git/node_modules/target。

## 10.5 Runtime API + 后台任务（runtime/）

**后台任务**（DurableTaskManager）：
- SQLite 持久化任务队列（~/.chisel/tasks/tasks.db）
- 生命周期：enqueued → running → completed / failed / canceled
- Worker Pool 默认 2（CHISEL_TASK_WORKERS 覆盖）
- 进程重启后未完成任务自动重入队

**Runtime API**（RuntimeApiServer）：
- 基于 JDK HttpServer（无 Spring Boot，轻量），仅监听 127.0.0.1
- 强制 CHISEL_RUNTIME_API_KEY 校验
- OpenAI Assistants 兼容端点：POST /v1/threads、/turns、GET /events（SSE）

---

# 附录 A：全模块速查表

| 模块 | 一句话 | 核心技术点 |
|------|--------|-----------|
| Agent | ReAct 循环 | 停滞检测/协作取消/预算三层守护 |
| 工具 | 四层拦截 | HITL/PathGuard/CommandGuard/Audit 三态 |
| MCP | 手写 JSON-RPC | 配对/双 transport/OAuth 分层/sampling/重启 |
| 记忆 | 三套 + 双压缩 | 关键词+时间衰减/窗口自适应 |
| RAG | AST 分块+混合检索 | bge-m3 批量/符号加权/失败回退 |
| Prompt | 分层组装 | 稳定在前/volatile 在后/前缀缓存 |
| Skill | 专家手册 | 两级加载/ContextBuffer/预算三层 |
| 多Agent | worker+reviewer | 审查 fail-safe/重试 |
| TUI | Renderer 三实现 | 输出收敛/Status dock/printAbove |
| 安全 | 四层 + 策略 | 审计/非交互式拒绝/符号链接防逃逸 |

# 附录 B：项目运行前提

```bash
export JAVA_HOME=~/tools/jdk-17.0.20+8/Contents/Home
export PATH=$JAVA_HOME/bin:~/tools/apache-maven-3.9.16/bin:$PATH

mvn clean package
java -jar target/chisel-1.0-SNAPSHOT.jar

# 测试（embedding 服务在线 RAG 测试才完整）
python3 /tmp/fake_ollama.py &        # 本地弱向量（免费，默认）
# 或真实模型（SiliconFlow bge-m3）：
# export EMBEDDING_PROVIDER=openai
# export EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
# export EMBEDDING_MODEL=BAAI/bge-m3
# export EMBEDDING_API_KEY=sk-xxx
NO_COLOR=1 mvn test -Pquick          # 常规回归
```
