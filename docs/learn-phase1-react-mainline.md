# ChiselCLI 学习笔记：阶段 1 —— ReAct 主链路

> 目标：完全掌握「用户输入一条消息 → ChiselCLI 返回回答」这条主链路。
> 对应面试必讲内容：ReAct 循环、流式解析、工具调用回灌、协作式取消。

---

## 1. 整体时序：一条消息走完的完整链路

```
用户输入 "帮我看下这个项目结构"
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│ Main.java（CLI 入口）                                          │
│  ① 读输入行，交给 CliCommandParser 判断是普通任务还是 /命令      │
│  ② 普通任务 → 展开 @path / @image 引用                          │
│  ③ runTask = () -> reactAgent.run(taskInput)                  │
│  ④ runWithCancelSupport(...) 放入后台线程，前台监听 ESC         │
└──────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│ Agent.run(userInput)                                         │
│  ① 存短期记忆 + 检索长期记忆 → 替换 system prompt 第 0 条        │
│  ② 组装用户消息（前置 Skill body）→ 加入 conversationHistory    │
│  ③ while(true) ReAct 循环 ←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←  │
│     a. 压缩检查 / 预算检查 / LSP 诊断注入                        │
│     b. llmClient.chat(history, tools) 调模型（流式）            │
│     c. 模型返回 toolCalls？                                    │
│        ├─ 是 → 加 assistant 消息 → executeToolCalls 并行执行     │
│        │      → 结果 Message.tool(id, result) 回灌 → continue  │
│        └─ 否 → 返回最终回答                                     │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 关键类与方法对照表

### 2.1 入口层：`cli/Main.java`

| 方法/片段 | 行号 | 作用 |
|-----------|------|------|
| `new Agent(llmClient, hitlToolRegistry)` | ~319 | 创建 ReAct Agent（注入 LLM + 带 HITL 的 ToolRegistry） |
| `reactAgent.setExternalContextSupplier(...)` | ~320 | 注入 MCP resources 索引（长上下文模式） |
| `reactAgent.setSkillRegistry(...)` | ~321 | 注入 Skill 系统 |
| `runTask = () -> reactAgent.run(taskInput)` | ~831 | 普通任务 → ReAct 路径（对比 `/plan`→PlanExecuteAgent、`/team`→AgentOrchestrator） |
| `runWithCancelSupport(terminal, ui, ...)` | ~1109 | 后台线程跑任务 + raw mode 监听 ESC 取消 |

**要点**：三种执行模式（ReAct / Plan / Team）在 Main 层是**三选一**，但 Plan 的 task 和 Team 的 worker 内部都还是 ReAct 循环。

### 2.2 核心循环：`agent/Agent.java`

| 方法 | 行号 | 作用 |
|------|------|------|
| `run(String userInput)` | ~126 | 入口：记忆注入、组装消息、进入循环 |
| `while (true)` 主循环 | ~153 | ReAct 循环本体 |
| `maybeCompactHistory()` | ~300 | 对话接近 window 上限时压缩早期消息 |
| `injectPendingLspDiagnostics()` | ~355 | LSP 诊断作为合成消息注入 |
| `executeToolCalls(...)` | ~653 | 把 toolCalls 交给 ToolRegistry 并行执行 |
| `updateSystemPromptWithMemory(...)` | ~298 | 运行时替换 conversationHistory[0]（system prompt） |
| `pruneHistoricalImagePayloads()` | ~340 | 每轮前裁剪历史图片 base64，防 window 爆炸 |

**循环退出条件（三层守护）**：
1. 模型不再返回 toolCalls（正常结束）
2. `AgentBudget.check()`：token 超限 / 停滞检测（工具签名重复）/ 硬迭代上限（默认 50）
3. `CancellationContext.isCancelled()`：用户 ESC / `/cancel`

### 2.3 LLM 接口：`llm/LlmClient.java`

| 类型 | 说明 |
|------|------|
| `Message(role, content, reasoningContent, toolCalls, toolCallId)` | 对话消息，五段结构 |
| `Message.tool(toolCallId, content)` | 工具结果回灌（按 id 关联） |
| `ToolCall(id, function(name, arguments))` | 模型请求的工具调用 |
| `Tool(name, description, parameters)` | 发给模型的工具 schema |
| `ChatResponse(role, content, toolCalls, tokens)` | 模型回复（含 token 统计） |

### 2.4 流式实现：`llm/AbstractOpenAiCompatibleClient.java`

| 片段 | 作用 |
|------|------|
| `delta.path("content")` + `onContentDelta` | SSE 增量实时推给渲染器 |
| `mergeToolCallDeltas(accumulators, delta.tool_calls)` | **tool_call 分片按 index 合并**（关键） |
| `extractReasoningDelta(delta)` | 兼容 3 种 provider 的 reasoning 字段 |
| `parseCachedInputTokens(usage)` | 兼容 4 种缓存 token 字段 |

### 2.5 工具执行：`tool/ToolRegistry.java`

| 方法 | 作用 |
|------|------|
| `executeToolOutput(name, args)` | 单工具执行：MCP 检查 → 执行 → 审计 |
| `executeTools(invocations)` | 并行执行：`invokeAll(timeout)` + 顺序收集结果 |
| `PolicyException` catch | 策略拒绝转成字符串回灌模型（不中断） |

---

## 3. ReAct 循环伪代码（面试默写版）

```java
public String run(String userInput) {
    // 1. 记忆准备
    memoryManager.addUserMessage(userInput);
    String memoryContext = memoryManager.buildContextForQuery(userInput, ...);
    updateSystemPromptWithMemory(memoryContext);          // 替换 history[0]

    // 2. 用户消息入历史（含 Skill 前置）
    conversationHistory.add(Message.user(userInput));

    // 3. ReAct 循环
    while (true) {
        if (CancellationContext.isCancelled()) return "已取消";
        maybeCompactHistory();                             // 防 window 超限
        AgentBudget.ExitReason r = budget.check();         // 三层守护
        if (r != WITHIN_BUDGET) return "预算耗尽: " + r;

        ChatResponse response = llmClient.chat(history, tools);   // 流式调模型

        if (response.hasToolCalls()) {
            history.add(Message.assistant(reasoning, content, toolCalls));  // 助手说"我要调工具"
            List<ToolExecutionResult> results = executeToolCalls(toolCalls); // 并行执行
            for (ToolExecutionResult tr : results) {
                history.add(Message.tool(tr.id(), tr.result()));             // 结果回灌
            }
            continue;                                       // 再问一轮
        }
        history.add(Message.assistant(response.content()));
        return response.content();                          // 模型决定结束
    }
}
```

---

## 4. 三个关键机制详解

### 4.1 工具结果回灌（为什么 Message.tool 要带 toolCallId）

```
LLM: assistant(..., toolCalls=[{id: "call_1", name: "read_file", args: {...}}])
        ↓
执行: read_file 返回 "文件内容..."
        ↓
回灌: tool(role, toolCallId="call_1", content="文件内容...")
        ↓
下一轮 LLM 看到: assistant(要调 call_1) + tool(call_1 的结果)
```

**关键**：OpenAI 协议要求 tool 消息必须带对应的 tool_call_id，否则模型无法关联"这个结果是谁调出来的"。回灌顺序必须与 toolCalls 顺序一致（`executeTools` 保证了这一点）。

### 4.2 停滞检测（AgentBudget，防死循环）

```java
// AgentBudget.java
String signature = signatureOf(toolCalls);   // "工具名+参数"的签名
recentToolSignatures.addLast(signature);
if (最近 stagnationWindow 轮签名全部相同) {
    stagnant = true;   // 判定死循环
}
// check() 返回 STAGNATION_DETECTED → Agent 退出
```

**场景**：模型卡在"反复调用同一个工具+同一参数"（比如一直 read 同一个文件）。没有这个检测，会无限烧 token。

### 4.3 协作式取消（为什么不用 Thread.interrupt）

```java
// Main.runWithCancelSupport
token = CancellationContext.startRun();   // 全局取消 flag
future = executor.submit(task);           // 后台线程跑 Agent
// 前台 raw mode 监听 ESC
if (readEscCancel(terminal)) {
    token.cancel();      // 设置 flag
    future.cancel(true); // interrupt 后台线程
    return "已取消";
}
// Agent 循环每个边界检查：
if (CancellationContext.isCancelled()) return "⏹️ 已取消";
```

**为什么两套一起**：OkHttp 阻塞读 LLM 流时，`Thread.interrupt()` 不保证立即生效；所以用**协作式 flag**（Agent 在循环边界检查）+ **interrupt**（中断阻塞 I/O）双管齐下。

---

## 5. 面试 Q&A 准备

**Q1：ReAct 循环怎么防止无限循环烧钱？**
A：三层守护——① AgentBudget token 预算；② 停滞检测（最近 N 轮工具签名相同判死循环）；③ 硬迭代上限（默认 50 轮）。

**Q2：工具结果怎么回灌给模型？**
A：用 `Message.tool(toolCallId, content)`，通过 tool_call_id 与 assistant 的 toolCalls 关联，且必须保持原始顺序（ToolRegistry.executeTools 用 `invokeAll` + 按 index 收集保证）。

**Q3：为什么流式 tool_call 要按 index 合并？**
A：OpenAI 兼容 API 的 SSE 流式响应里，一个 tool_call 的参数可能被拆成多个 delta 分片（`{"index":0, "arguments":"{\"path\":"}` → `{"index":0, "arguments":"\"a.txt\"}"}`），必须按 index 攒起来再拼。

**Q4：用户按 ESC 取消是怎么生效的？**
A：协作式取消——Main 在 raw mode 监听 ESC，设置 `CancellationContext` flag 并 interrupt 后台线程；Agent 在循环每个边界（调 LLM 前/后、工具批次前）检查 flag。阻塞 I/O 用 interrupt 打断，循环逻辑用 flag 退出。

---

## 6. 动手验证（跟着做一遍）

```bash
cd ~/Documents/Project/chisel

# 1. 跑起来，发一条会触发工具的指令
java -jar target/chisel-1.0-SNAPSHOT.jar
> 列出当前目录的文件          # 会触发 list_dir

# 2. 打断观察取消
> 帮我把整个项目重构一下     # 让它跑久一点
# 按 ESC → 观察"已取消"

# 3. 看流式输出
> 写一个 hello world java 文件  # 观察 thinking → content → 工具调用
```

---

## 7. 学习顺序建议

1. **先看 2.2 的 `while(true)`**（5 分钟建立全局）
2. **再对照第 3 节伪代码**（10 分钟理解循环）
3. **然后逐个看 4.1/4.2/4.3 三个机制**（各 10 分钟）
4. **最后跑第 6 节动手验证**（15 分钟固化）
5. 完成后再进阶段 2（工具系统 + 安全层）
