# ChiselCLI 学习笔记：阶段 6 —— 支线模块（微信 / LSP / 快照 / Runtime API）

> 目标：掌握四个独立模块的核心设计。这些是"加分项"——面试按兴趣选讲，
> 每个都能体现一个独立的工程点。不用全部深读，理解设计意图即可。

---

## 1. 微信 iLink 通道（wechat/）

### 定位
独立于 Skill / Runtime API 的第三条远程通道：用 iLink 协议（非官方微信 API）
让用户通过微信给 ChiselCLI 发消息、收回复。**默认关闭**，需显式 `/wechat` 或 `chisel wechat ...`。

### 核心设计：非交互式默认拒绝策略
远程聊天无法弹窗等人审 → 用 `WechatPolicyDecider` 替代 HITL：

```
只读工具 → 直接执行
execute_command → 必须精确命中命令白名单，否则拒绝
write_file → 允许但 PathGuard 限定 workspace
revert_turn / 浏览器会话切换 → 拒绝
mcp__* → 必须命中 MCP 白名单
```

**被拒结果回灌模型** + 写审计（approver=policy, outcome=deny）。

### 消息分发分层（防控制命令竞态）
```
getupdates long-poll
  → 鉴权（boundUserId 匹配？否则 drop + 审计 unbound_drop）
  → 分类：
      ├─ 控制命令（/stop /pause /resume /status）→ 立即执行，旁路队列
      └─ 普通消息 → 单并发 turn 队列
```

**为什么旁路**：`/stop` 如果排进队列，会排在它要取消的消息后面永远来不及执行。

### 绑定与鉴权
- setup 扫码 → iLink 返回 `ilink_user_id` → 作为 boundUserId
- 非绑定用户消息一律 drop + 写审计（不能静默）
- 账号文件权限 600

### 其他要点
- 回复用 `sendmessage` 分块发送，Markdown 子集归一化（标题转粗体、表格转键值）
- 取消复用 ReAct `/cancel` 的 cancellation token（不新造 interrupt 机制）
- 封号风险 / 第三方可见性在文档显著声明

### 面试讲法
> "微信通道是远程入口，无法弹窗，所以用非交互式默认拒绝策略替代 HITL——只读放行、执行命令必须命中白名单。消息分发分两层：控制命令（/stop 等）旁路队列立即执行，普通消息进单并发 turn 队列，避免取消命令排在它要取消的任务后面。"

## 2. LSP 诊断注入（lsp/）

### 定位
Agent 改完代码立刻注入编译诊断，而不是等用户手跑 `mvn compile`。

### 链路
```
edit_file / apply_patch / write_file 成功
  → LspHooks（挂在 ToolRegistry.executeTool 执行后路径）
  → LspManager.runPostEditLspHook(file)
      → 按语言惰性启动 LSP server（JDT LS for Java / rust-analyzer / pyright / gopls）
      → textDocument/didOpen → didChange → 收集 publishDiagnostics
  → 下一轮 LLM 请求前 flushPendingLspDiagnostics()
      → 把诊断作为合成 user message 注入 conversationHistory
```

### 关键设计
- **惰性启动**：按语言首次编辑才起 server，per-language transport pool 复用连接
- **优雅降级**：LSP server 启动失败/超时只打日志，不阻塞主流程；无对应 server 的语言跳过
- **限制注入**：单次最多 20 条诊断，按 severity 格式化

### 面试讲法
> "LSP 诊断通过 post-edit hook 注入：Agent 写完文件自动起 LSP server 收集编译错误，下一轮推理前作为合成消息塞进对话——模型立刻能看到自己引入的编译问题。启动失败只降级不阻塞。"

## 3. Side-Git 快照回滚（snapshot/）

### 定位
每个 turn 前后自动做工作区快照，可一键回滚，**不污染用户 `.git`**。

### 实现
```
SideGitManager：~/.chisel/snapshots/<project_hash>/<worktree_hash>/.git
  → 独立 side-git 仓库（JGit 纯 Java，与用户 .git 完全隔离）
  → preTurnSnapshot()：turn 开始前 add/commit，标记 "pre-turn <turn_id>"
  → postTurnSnapshot()：turn 结束后异步 commit，标记 "post-turn <turn_id>"
```

### 入口
- `/restore <N>`：从最近 N 个 pre-turn 快照恢复
- `revert_turn` 工具：LLM 可调用的回滚工具（Agent 自己判断改坏了要撤销）
- 快照策略：max_snapshots（默认 50）、snapshot_excludes（排除 .git/node_modules/target）

### 面试讲法
> "用 JGit 维护独立 side-git 仓库，每个 turn 前后自动 commit（pre/post-turn 标记），与用户 .git 完全隔离——Agent 改坏了可以一键回滚，但不污染用户的 git 历史。"

## 4. Runtime API + 后台任务（runtime/）

### 定位
无头场景：后台跑任务 + HTTP/SSE API 嵌入 CI/CD。

### 后台任务（DurableTaskManager）
- SQLite 持久化任务队列（`~/.chisel/tasks/tasks.db`）
- 生命周期：enqueued → running → completed / failed / canceled
- Worker Pool 默认 2（`CHISEL_TASK_WORKERS` 覆盖）
- 进程重启后未完成任务自动重入队
- CLI：`/task add` / `/task list` / `/task cancel` / `/task log`

### Runtime API（RuntimeApiServer）
- 基于 JDK `HttpServer`，仅监听 127.0.0.1
- 强制 `CHISEL_RUNTIME_API_KEY` 校验
- OpenAI Assistants 兼容端点：
  - `POST /v1/threads`：创建线程
  - `POST /v1/threads/{id}/turns`：发起一轮 Agent 交互
  - `GET /v1/threads/{id}/events`：SSE 流式事件
- RuntimeThreadStore：SQLite 保存 thread 与事件时间线

### 面试讲法
> "SQLite 持久化任务队列 + Worker Pool 跑后台 Agent；JDK HttpServer 暴露 OpenAI Assistants 兼容的 HTTP/SSE API，仅监听 localhost + API key 校验，可以嵌入 CI/CD。"

## 5. 各模块技术点速查

| 模块 | 核心技术点 | 一句话 |
|------|-----------|--------|
| 微信 | 非交互式策略 / 控制命令旁路 | 远程入口不能弹窗，默认拒绝 + 白名单 |
| LSP | post-edit hook / 合成消息注入 | 改完代码立刻报编译错误 |
| 快照 | JGit 隔离仓库 / turn 级自动 commit | 一键回滚不污染用户 git |
| Runtime | SQLite 任务队列 / JDK HttpServer / SSE | 无头场景后台跑 + API 嵌入 |

## 6. 面试选择建议

- **面 Agent 应用**：讲微信通道（策略设计）+ LSP（hook 模式）
- **面后端**：讲 Runtime API（HTTP/SSE + 持久化）+ 快照（JGit）
- **面全栈**：四个都一句带过，重点仍在 ReAct + MCP + RAG

## 7. 学习顺序（每模块 1-2 小时，按需）

1. 微信：读 `WechatPolicyDecider.java`（策略）+ `WechatMessageLoop.java`（旁路）
2. LSP：读 `LspManager.java`（server 管理）+ 找 post-edit hook 挂载点
3. 快照：读 `SideGitManager.java`（JGit 封装）
4. Runtime：读 `DurableTaskManager.java` + `RuntimeApiServer.java`
