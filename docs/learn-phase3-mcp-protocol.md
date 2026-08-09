# ChiselCLI 学习笔记：阶段 3 —— MCP 协议（技术含量最高）

> 目标：完全掌握「ChiselCLI 如何与外部 MCP server 通信」——无官方 SDK，
> 从零手写 JSON-RPC 2.0 客户端。这是项目最硬的技能点，面试必讲。
>
> 覆盖：请求-响应配对、双 transport（stdio + HTTP/SSE）、OAuth PKCE、
> sampling 反向调用、server 崩溃自动重启。

---

## 1. MCP 是什么 & 整体架构

**MCP（Model Context Protocol）**：让 ChiselCLI（client）调用外部 AI 工具（server）的标准协议。
如 chrome-devtools MCP 提供浏览器操控、filesystem 提供文件访问。

```
ChiselCLI (client)
    │  McpServerManager（管理多个 server）
    │
    ├─ McpClient（每个 server 一个）
    │    └─ JsonRpcClient（协议核心：请求-响应配对）
    │         └─ McpTransport（传输层，二选一）
    │              ├─ StdioTransport（起 npx/uvx 子进程，stdin/stdout 传 JSON 行）
    │              └─ StreamableHttpTransport（HTTP + SSE，支持 OAuth）
    │
    ▼
外部 MCP server（npx chrome-devtools-mcp 等）
```

**关键点**：没有用 `mcp-sdk` 依赖，JSON-RPC 全部手写（`pom.xml` 里无 io.modelcontextprotocol）。

## 2. 协议核心：JsonRpcClient（请求-响应配对）

### 2.1 发请求（带 id，等响应）

```java
public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
    long id = ids.getAndIncrement();                     // 自增 id
    ObjectNode request = MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", method);
    request.set("params", params);

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

**数据结构**：`ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending` —— **id → Future 的配对表**。

### 2.2 收消息（核心：区分三种消息）

```java
private void handleMessage(JsonNode message) {
    JsonNode idNode = message.get("id");

    // ① 没有 id = 通知（notifications/tools/list_changed 等）→ 广播给监听者
    if (idNode == null || idNode.isNull()) {
        for (Consumer<JsonNode> listener : notificationListeners) listener.accept(message);
        return;
    }

    long id = idNode.asLong();
    CompletableFuture<JsonNode> future = pending.remove(id);   // ② 有 id 且匹配 pending = 我的请求的响应

    if (future != null) {
        // 有 error → 抛 JsonRpcException；否则 complete(result)
        ...
        return;
    }

    // ③ 有 id 但不在 pending = server 主动发来的请求（sampling/createMessage）
    //    必须异步派发，否则阻塞 transport reader 线程（自我死锁）
    requestDispatcher.submit(() -> dispatchServerRequest(id, method, params));
}
```

**这个"id 在不在 pending"的判断是整个协议的灵魂**——同一套消息流里区分了：
- 通知（无 id）
- 我的请求的响应（id 在 pending）
- server 发给我的请求（id 不在 pending）

## 3. 双 transport

### 3.1 StdioTransport（本地子进程）

```java
public StdioTransport(String command, List<String> args, Map<String,String> env, Path workingDir) {
    ProcessBuilder builder = new ProcessBuilder(commandLine);
    this.process = builder.start();                    // 起 npx/uvx 子进程
    // 三个线程：
    //   send()        → 写 stdin（JSON 行 + \n + flush）
    //   stdout reader → 读 stdout 解析 JSON 行 → 分发给监听者
    //   stderr reader → 读 stderr 记环形日志（防管道堵死）
}
```

**要点**：
- stderr 单独 drain（否则子进程 stderr 写满会阻塞）
- `close()`：先关 stdin（EOF 优雅退出窗口 1s）→ SIGTERM → 2s 后 destroyForcibly

### 3.2 StreamableHttpTransport（远程 HTTP）

```java
public void send(JsonNode message) throws IOException {
    Request request = buildRequest(message, accessToken());   // 带 Bearer token（如有）
    Response response = client.newCall(request).execute();

    if (response.code() == 401 && tokenStore != null) {        // OAuth 挑战
        OAuthChallenge challenge = OAuthChallenge.parse(response.header("WWW-Authenticate"));
        String refreshed = tryRefresh(challenge);               // 秒级 refresh 重试
        if (refreshed != null) { ...重试... }
        else { throw new OAuthRequiredException(url, challenge); }  // 上抛给 Manager 做完整授权
    }
    // 解析响应：SSE（text/event-stream）或普通 JSON → 分发给监听者
    // 记录 Mcp-Session-Id 供后续请求携带
}
```

## 4. OAuth 授权码 + PKCE（第 24 期交付）

**触发**：server 返回 `401 + WWW-Authenticate: MCP-OAuth`（或 `Bearer resource_metadata=`）。

**两层处理**（关键设计）：
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

**无浏览器环境**：打印授权 URL，提示用户手动打开，仍等本地回调。

## 5. sampling 反向调用（server → client 请求）

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

**安全**：终端默认允许但写审计；微信等非交互通道默认拒绝。

## 6. server 自动重启（recovery）

```java
// ServerRecoveryManager
// 触发：工具调用抛 IOException（连接断 / 进程退出）
public void onToolFailure(String serverName, Throwable cause) {
    if (server.status() != READY) return;    // 非 READY 不重启
    if (!state.markScheduled()) return;      // 已在恢复中，幂等
    executor.submit(() -> recover(serverName, state, cause));
}

// 退避：1s → 2s → 4s → 8s → 16s → 30s（封顶），默认最多 3 次
// 成功后重置计数；连续失败超限 → ERROR（等手动 /mcp restart）
// /mcp 状态行展示 "restarting (attempt n/3)"
```

## 7. McpServerManager 生命周期

```
startAll()
  → 每个 server 并行 start()
      → createAndInitializeClient()
          → 创建 transport → new McpClient → initialize()
          → 401 挑战 → 完整 OAuth 授权 → 重建 transport 重试
      → registerNotificationHandlers()（tools/list_changed 等）
      → registerSamplingHandler()
      → listTools() → 注册 mcp__{server}__{tool} 到 ToolRegistry
      → 状态 READY
  → 启动超时（默认 8s）→ 先进 CLI，未完成 server 后台继续（STARTING）
```

## 8. 面试 Q&A

**Q1：JSON-RPC 请求-响应怎么配对的？**
A：`ConcurrentHashMap<Long, CompletableFuture<JsonNode>>`——发请求时 id → Future 入表，收响应按 id 移除并 complete。超时用 ScheduledExecutor 定时移除。

**Q2：怎么区分"我的请求的响应"和"server 发来的请求"？**
A：看 id 在不在 pending 表里。在 → 是响应（complete future）；不在 → 是 server 主动发来的请求（sampling 等），路由给 handler。

**Q3：为什么 sampling handler 必须异步派发？**
A：如果直接在 transport 的 reader 线程同步跑 handler，handler 内部调 LLM 或发新请求时会阻塞 reader，导致同 server 其他 pending 响应的读取被卡死（自我死锁）。

**Q4：OAuth 为什么分两层处理？**
A：完整浏览器授权要几秒到几分钟，不能放进 JSON-RPC 请求的 60s 超时内。所以运行中 401 用 refresh token 秒级刷新重试；首次授权/刷新失败才抛 OAuthRequiredException 由 Manager 做完整流程。

**Q5：stdio 子进程怎么防止 stderr 堵死？**
A：stderr 单独起线程 drain 到环形缓冲（最多 200 行），不读就会写满阻塞子进程。

**Q6：server 崩了怎么恢复？**
A：工具调用抛 IOException → ServerRecoveryManager 指数退避重启（1s→30s，最多 3 次）→ 重新 initialize + 注册工具；连续失败标 ERROR 等手动重启。

## 9. 动手验证

```bash
# 1. 看 MCP 状态
java -jar target/chisel-1.0-SNAPSHOT.jar
/mcp        # 看 server 状态、工具数

# 2. 用 MCP 工具（chrome-devtools）
> 帮我打开 https://example.com 看看内容
# 观察 mcp__chrome-devtools__* 工具被调用

# 3. 看 server 日志
/mcp logs chrome-devtools
```

## 10. 学习顺序

1. 第 2 节 JsonRpcClient 配对（20 分钟，最重要）
2. 第 3 节双 transport（各 15 分钟）
3. 第 4 节 OAuth（20 分钟）
4. 第 5-6 节 sampling / recovery（各 10 分钟）
5. 第 7 节生命周期（10 分钟）
6. 完成后可进阶段 4（记忆 + RAG + 上下文）
