# ChiselCLI 学习笔记：阶段 2 —— 工具系统 + 安全层

> 目标：掌握「模型请求一个工具调用 → 工具执行 → 结果回灌」的完整链路，
> 以及 HITL / 路径围栏 / 命令黑名单 / 审计四层安全是怎么叠加的。
> 对应面试必讲：策略拒绝回灌模型（不中断）、并行工具调度、审计三态。

---

## 1. 工具注册：一个工具是什么

```java
// ToolRegistry.registerRagTools() 里注册 search_code 的完整形态
tools.put("search_code", new Tool(
        "search_code",                                    // 工具名
        "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块...",  // 描述（模型选择工具的依据）
        createParameters(                                 // JSON schema（模型按它生成参数）
                new Param("query", "string", "自然语言查询描述", true),
                new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
        ),
        args -> { ... }                                   // 执行函数（args 是参数 Map）
));
```

**四个要素**：`name`（模型识别）+ `description`（模型选择）+ `parameters`（模型生成参数）+ `executor`（真正干活）。

**发给模型**：每轮调 LLM 时，`toolRegistry.getToolDefinitions()` 把所有工具的 name/description/parameters 汇总成 schema 列表发给模型——模型根据描述决定"该用哪个工具"。

## 2. 工具执行链：四层拦截（核心）

```
模型请求 execute_command("rm -rf /")
    │
    ▼
① HitlToolRegistry.executeToolOutput()   ← HITL 层（人审）
    ├─ HITL 未启用 → 直接放行到下一层
    └─ 需要审批 → 弹窗 [y/n/a/s/m] → 通过才放行
    │
    ▼
② ToolRegistry.doExecuteTool()           ← 执行层
    ├─ MCP 工具先过浏览器安全检查（checkBrowserTool）
    ├─ PathGuard.resolveSafe(path)       ← ③ 路径围栏（抛 PolicyException）
    └─ CommandGuard.check(command)       ← ④ 命令黑名单（抛 PolicyException）
    │
    ▼
成功 → 审计 allow + 返回结果
PolicyException → 审计 denyByPolicy + 返回 "🛡️ 策略拒绝: ..."
其他异常 → 审计 error + 返回 "工具执行失败: ..."
```

## 3. 关键类

### 3.1 `hitl/HitlToolRegistry.java`（HITL 层）

```java
public ToolOutput executeToolOutput(String name, String argumentsJson) {
    // HITL 未启用或该工具不需要审批 → 直接执行（零开销）
    if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
        return super.doExecuteTool(name, argumentsJson);
    }
    // 浏览器工具敏感页 → 单步审批
    // 已"全部放行"（按工具或按 MCP server）→ 直接执行
    if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
        return super.doExecuteTool(name, argumentsJson);
    }
    // 否则弹窗审批
    return executeAfterExplicitApproval(name, argumentsJson, null);
}
```

**要点**：
- 继承 `ToolRegistry`，只覆写 `executeToolOutput`——HITL 关闭时行为与父类完全相同
- "全部放行"分两个维度：按工具（`approvedAllByTool`）/ 按 MCP server（`approvedAllByServer`）
- 拒绝结果返回 `"[HITL] 操作已被拒绝：..."` 给模型（不是抛异常）

### 3.2 `hitl/ApprovalPolicy.java`（哪些工具要审批）

```java
private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file", "execute_command", "create_project", "revert_turn");

public static boolean requiresApproval(String toolName) {
    return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);  // 所有 mcp__ 工具默认审批
}
```

### 3.3 `policy/PathGuard.java`（路径围栏）

```java
public Path resolveSafe(String input) {
    Path raw = Paths.get(input);
    Path resolved = raw.isAbsolute() ? raw.normalize() : rootPath.resolve(raw).normalize();
    Path realResolved = resolveRealPath(resolved);   // 关键：解析符号链接
    if (!realResolved.startsWith(rootPath)) {
        throw new PolicyException("路径越界: " + input + " 不在项目根之内");
    }
    return realResolved;
}
```

**三类越界**：
1. 绝对路径（`/etc/passwd`）→ startsWith 检查拦截
2. `..` 穿越（`../../etc/passwd`）→ normalize 后检查
3. **符号链接逃逸**（项目内软链指向外部）→ `toRealPath()` 解析后检查

**关键细节**：
- 根路径自身也 `toRealPath()`（macOS 上 `/var` → `/private/var`，不展开会导致 startsWith 永远 false）
- **不存在的路径也能校验**（write_file 创建新文件）：向上找最近的存在祖先解析，再把剩余段接回（`resolveRealPath`）

### 3.4 `policy/CommandGuard.java`（命令黑名单）

```java
// 快速拒绝：sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / chmod 777 / shutdown
// 注释明确写：黑名单是反模式（永远列不全），但能拦住 LLM 容易踩的明显破坏性命令
// 定位：辅助 HITL 而非主防线
```

### 3.5 `policy/AuditLog.java`（审计）

```java
// 一行一条 JSON（JSONL），按天分文件 audit-YYYY-MM-DD.jsonl，默认 ~/.chisel/audit/
// 三种 outcome：
AuditEntry.allow(...)        // 成功
AuditEntry.denyByPolicy(...) // 策略拒绝（approver=policy）
AuditEntry.error(...)        // 异常（approver=none）
// HITL 拒绝 → denyByHitl（approver=hitl）
// 写入失败只 stderr 提示，不抛异常（审计故障不影响主流程）
```

## 4. 并行工具调度

```java
// ToolRegistry.executeTools()
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
```

**三个设计点**：
1. **`invokeAll(timeout)`**：整个批次统一超时，超时任务被取消
2. **按 index 收集**：结果顺序与输入顺序一致（保证回灌历史不乱）
3. **daemon 线程**：不阻塞 JVM 退出

## 5. 微信通道的特殊策略（非交互式默认拒绝）

远程聊天无法弹窗等人 → `WechatPolicyDecider`：

| 工具类别 | 微信通道默认 | 放开方式 |
|---------|------------|---------|
| `execute_command` | **拒绝** | setup 配置命令白名单，精确命中才放行 |
| `write_file` / `create_project` | 允许但 PathGuard 限定 workspace | 越界即拒 |
| `revert_turn` / 浏览器会话切换 | **拒绝** | v1 不放开 |
| `mcp__*` | **拒绝** | setup 配置 server/tool 白名单 |
| 只读工具 | 直接执行 | — |

**被拒结果回灌模型**：`"🛡️ 策略拒绝: ..."` 让模型换路，同时写审计（approver=policy）。

## 6. 面试 Q&A

**Q1：HITL 和 PathGuard 谁先执行？**
A：HITL 先（`HitlToolRegistry.executeToolOutput`），PathGuard/CommandGuard 在 `ToolRegistry.doExecuteTool` 内部。但**用户无法批准策略拒绝的请求**——PathGuard 抛 `PolicyException` 时即使 HITL 点了允许，也会在 doExecuteTool 里被拦。

**Q2：策略拒绝为什么不抛异常给上层，而是返回字符串？**
A：工具执行的"用户"是 LLM。返回字符串（"策略拒绝: ..."）让模型看到原因并换一条路继续（比如改用 read_file 而不是读越界路径）；抛异常会让整个 Agent 崩溃。这是 Agent 工具的黄金法则。

**Q3：符号链接逃逸怎么防的？**
A：`toRealPath()` 解析符号链接后做 `startsWith(rootPath)` 检查。根路径自身也要 `toRealPath()`（macOS `/var`→`/private/var`）。

**Q4：审计的三种 outcome 是什么？**
A：allow（成功）/ denyByPolicy（策略拒绝，approver=policy）/ error（异常），HITL 拒绝是 denyByHitl（approver=hitl）。

## 7. 动手验证

```bash
# 1. 触发越界路径
java -jar target/chisel-1.0-SNAPSHOT.jar
> 读取 /etc/passwd
# 观察 "🛡️ 策略拒绝: 路径越界..."

# 2. 触发 HITL
/hitl on
> 创建文件 test.txt
# 观察弹窗 [y/n/a/s/m]

# 3. 看审计
/audit 5
# 观察 JSONL 记录
```

## 8. 学习顺序

1. 看第 2 节执行链图（5 分钟）
2. 逐个读 3.1-3.5 五个类（每个 10 分钟）
3. 看第 4 节并行调度（10 分钟）
4. 跑第 7 节动手验证（15 分钟）
5. 完成后再进阶段 3（MCP 协议）
