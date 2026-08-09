# 第 24 期开发任务：MCP OAuth + sampling + server 自动重启

> AGENTS.md 已声明 "下一步：OAuth / sampling / recovery 作为后续 MCP 增强"。
> 本期补齐第 11 期明确推迟的三项 MCP 增强，全部属于"远程 server 可用性 / 双向能力"范畴。

## 1. 目标与产出物

在 MCP 协议核心（第 10 期）+ 高级能力（第 11 期）的基础上，补齐三项被推迟的能力：

1. **OAuth 2.0 Authorization Code + PKCE**：Streamable HTTP server 返回 401 + `WWW-Authenticate: MCP-OAuth` 挑战时，自动走授权码 + PKCE 换取 token，持久化并自动刷新，后续请求携带 `Authorization: Bearer`。
2. **`sampling/createMessage` 反向 LLM 调用**：server 通过 JSON-RPC request（非通知）向 client 发起 `sampling/createMessage`，ChiselCLI 用当前配置的 LLM client 生成回复并返回，受 HITL 策略层与审计管理。
3. **MCP server 自动重启（recovery）**：stdio 子进程退出 / HTTP 连接失败导致工具调用失败时，自动重启 server 并重注册工具，带退避重试，不阻塞主流程。

**验证标准**：新增单元测试覆盖三条路径；`mvn test -Pquick` 全绿；`mvn clean package` 通过。

## 2. 明确不做

- OAuth Client Credentials / Device Flow（MCP 规范里 Authorization Code + PKCE 是标准路径）
- 多账号 / 多 server 并发 OAuth 浏览器流程（一次只处理一个授权）
- sampling 的 tool-use 执行（server 请求里带 toolCall 时直接拒绝，不执行）
- `roots/list` 支持（本期不做反向 roots 能力）
- server health ping / heartbeat 主动探活（沿用第 11 期决策）
- OAuth token 的 TUI 可视化管理（仅 `/mcp` 状态行展示是否已授权）

## 3. 模块拆分

```text
src/main/java/com/chisel/mcp/
├── oauth/
│   ├── McpOAuthClient.java        # 授权码 + PKCE 流程：challenge 解析、授权 URL、code 换 token、刷新
│   ├── OAuthTokenStore.java       # token 持久化（~/.chisel/mcp/oauth-tokens.json），含过期与刷新
│   └── OAuthChallenge.java        # WWW-Authenticate 挑战头解析
├── sampling/
│   └── SamplingHandler.java       # 处理 server → client 的 sampling/createMessage 请求
├── recovery/
│   └── ServerRecoveryManager.java # 崩溃检测 + 退避自动重启
├── jsonrpc/
│   └── JsonRpcClient.java         # 新增：处理 server → client 的 request（带 id 但不是响应）
└── transport/
    └── StreamableHttpTransport.java  # 401 + MCP-OAuth 挑战 → 触发 OAuth 流程后重试
```

集成点：

- `McpClient`：暴露 `onServerRequest(Consumer)`，把带 id 的非响应消息路由给 sampling handler
- `McpServerManager`：持有 `OAuthTokenStore` / `SamplingHandler` / `ServerRecoveryManager`；`start()` 启动时给 transport 注入 OAuth 能力；注册 sampling handler；恢复管理器监控工具调用失败
- `ToolRegistry`：工具调用失败回调（供 recovery 检测）
- `Main`：`/mcp` 状态行展示 OAuth 状态
- `AuditLog`：OAuth 授权 / sampling 调用 / 自动重启各记一条审计
- `McpInitializeRequest`：声明 `sampling` capability（如果启用）

## 4. 用户行为

### 4.1 OAuth

```text
$ cat ~/.chisel/mcp.json
{
  "mcpServers": {
    "secure-demo": {
      "url": "https://mcp.example.com/mcp",
      "oauth": true
    }
  }
}
```

启动时 `initialize` 或任何请求返回 `401` + `WWW-Authenticate: MCP-OAuth` 挑战 → ChiselCLI 打开浏览器到授权页 → 用户授权 → 本地回调端口收 code → 换 token 存 `~/.chisel/mcp/oauth-tokens.json` → 重试原请求。

- token 过期（401 + Bearer 挑战）→ 自动用 refresh_token 刷新后重试
- 无浏览器环境（`java.awt.Desktop` 不可用 / headless）→ 打印授权 URL，提示用户手动打开，仍走本地回调等 code
- token 文件权限 `600`，字段脱敏后入审计

### 4.2 sampling

server 声明 `sampling` capability 后，可向 ChiselCLI 发起 `sampling/createMessage`。ChiselCLI 行为：

- 用当前 `ChiselConfig` 默认 provider 的 LLM client 生成回复（复用现有 `LlmClientFactory`）
- `maxTokens` 默认 1024，受 `CHISEL_MCP_SAMPLING_MAX_TOKENS` 覆盖
- HITL：sampling 是"外部 server 花 ChiselCLI 的 API 额度"，默认**允许但审计**；`/mcp` 状态行不展示（低频事件）
- 微信通道：非交互式策略下 sampling **默认拒绝**（与 execute_command 同级）
- server 请求带 `toolCall` 字段 → 拒绝（返回错误，不执行工具）
- 审计：`approver=policy / outcome=allow|deny`，`tool=sampling/createMessage`

### 4.3 自动重启

- 工具调用抛 IOException / server 进程退出 → `ServerRecoveryManager` 标记 `RESTARTING`，指数退避（1s → 2s → 4s → 8s，上限 30s，默认最多 3 次）后自动重启
- 重启成功后重新走 `initialize → tools/list → 注册`，工具自动恢复
- 连续失败超过上限 → 标记 `ERROR`，不再自动重试（等 `/mcp restart <name>` 手动）
- `/mcp` 状态行展示 `restarting (attempt 2/3)` 与最近一次错误

## 5. 不做清单对照

| 能力 | 第 11 期状态 | 本期状态 |
|------|-------------|---------|
| OAuth Authorization Code + PKCE | 延后 | ✅ 交付 |
| `sampling/createMessage` | 延后 | ✅ 交付 |
| MCP server 自动重启 | 延后 | ✅ 交付 |
| health ping / heartbeat | 不做 | 不做 |
| roots/list | 未提 | 不做 |

## 6. 测试计划

- `OAuthChallengeTest`：`MCP-OAuth` / `Bearer resource_metadata=` / 无挑战头解析
- `McpOAuthClientTest`：MockWebServer 模拟授权端点，验证 PKCE code_challenge、token 请求参数、refresh 流程
- `OAuthTokenStoreTest`：写入 / 读取 / 过期 / 文件权限 / 损坏容错
- `SamplingHandlerTest`：mock LLM client，验证 messages 转换、maxTokens、toolCall 拒绝、异常回滚
- `ServerRecoveryManagerTest`：退避序列、最大次数、成功后重置
- `McpServerManagerTest` 扩展：工具调用失败触发重启、重启后工具重注册
- `JsonRpcClientTest` 扩展：server → client request 路由（带 id 非响应消息）
- 回归：`mvn test -Pquick`

## 7. 文档联动

- `README.md`：MCP 章节新增 OAuth / sampling / recovery 小节；删除 "OAuth 和 sampling 当前未实现" 行
- `AGENTS.md`：同步更新已交付能力与"下一步"
- `ROADMAP.md`：MCP 高级能力章节补充三项已完成状态
