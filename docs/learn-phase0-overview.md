# ChiselCLI 学习笔记：阶段 0 —— 全局概览与学习路径

> 这份是所有阶段文档的入口。先建立全局认知，再按阶段深入。
> 配套文档：learn-phase1 到 learn-phase6（各阶段详解）。

---

## 1. 项目一句话

ChiselCLI 是**从零手写的 Java Agent CLI**（对标 Claude Code）：
34k 行 / 214 个主类 / 770+ 单测 / Java 17 / Maven。

## 2. 三条执行路径

| 路径 | 入口 | 触发 | 内部 |
|------|------|------|------|
| ReAct | `Agent.java` | 默认 | while(true) 循环调 LLM |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` | 先规划 DAG，task 按依赖批次执行 |
| Multi-Agent | `AgentOrchestrator.java` | `/team` | 规划者+Worker+检查者，审查循环 |

**共享**：三条路径复用 `ToolRegistry` / `MemoryManager` / `SnapshotService`——不各自造轮子。

**关键**：Plan 的 task 和 Team 的 worker 内部都还是 ReAct 循环（SubAgent / task executor）。

## 3. 核心能力地图（24 期演进）

```
基础 ReAct → 规划执行 → 记忆上下文 → RAG检索 → 多Agent → HITL → 并行 → 多模型
联网 → MCP核心 → MCP高级 → 长上下文 → ChromeDevTools → CDP复用 → Skill → TUI
LSP诊断 → Git快照 → Prompt分层 → 后台任务+API → 图片输入 → 微信通道 → MCP OAuth/sampling/重启
```

## 4. 模块清单（src/main/java/com/chisel/）

| 目录 | 职责 | 学习阶段 |
|------|------|---------|
| `agent/` | Agent / Plan / Multi-Agent / SubAgent | 阶段 1 |
| `cli/` | Main 入口 / 命令解析 / 计划审阅输入 | 阶段 1 |
| `llm/` | LlmClient 接口 + 7 个 provider 客户端 | 阶段 1 |
| `tool/` | ToolRegistry（工具注册 + 执行 + 审计） | 阶段 2 |
| `hitl/` | HITL 审批流 | 阶段 2 |
| `policy/` | PathGuard / CommandGuard / AuditLog | 阶段 2 |
| `mcp/` | MCP 客户端 / transport / OAuth / sampling / recovery | 阶段 3 |
| `memory/` | 短期/长期记忆、压缩、检索 | 阶段 4 |
| `context/` | 上下文模式与 Token 展示 | 阶段 4 |
| `rag/` | 分块 / Embedding / 向量存储 / 混合检索 | 阶段 4 |
| `render/` | Renderer 接口 + inline/plain 实现 | 阶段 5 |
| `wechat/` | iLink 微信通道 | 阶段 6 |
| `lsp/` | LSP 诊断注入 | 阶段 6 |
| `snapshot/` | Side-Git 快照回滚 | 阶段 6 |
| `runtime/` | 后台任务 + Runtime API | 阶段 6 |
| `browser/` | 浏览器会话与敏感页策略 | 阶段 6（略） |
| `skill/` | Skill 系统 | 阶段 4（略） |
| `prompt/` | Prompt 分层组装 | 阶段 4（略） |

## 5. 学习路径总览（8-12 天）

| 阶段 | 文档 | 时间 | 掌握后能讲 |
|------|------|------|-----------|
| 0 全局 | 本文 | 半天 | 项目全貌、模块地图 |
| 1 ReAct 主线 | learn-phase1-react-mainline.md | 1-2 天 | 核心循环 + 流式 + 取消 |
| 2 工具+安全 | learn-phase2-tools-security.md | 1-2 天 | 四层拦截 + 并行调度 |
| 3 MCP 协议 | learn-phase3-mcp-protocol.md | 2-3 天 | 协议实现（最强） |
| 4 记忆+RAG | learn-phase4-memory-rag-context.md | 2 天 | 检索 + 压缩 + 选型 |
| 5 TUI | learn-phase5-tui-rendering.md | 1-2 天 | 渲染架构 + 输出收敛 |
| 6 支线 | learn-phase6-side-modules.md | 按需 | 微信/LSP/快照/API |

## 6. 三条学习路线（按目标）

- **面试 Agent 应用**：阶段 1 → 3 → 4（ReAct + MCP + RAG）
- **面试后端**：阶段 1 → 2 → 6 的 Runtime/快照
- **面试全栈/通用**：全阶段，重点 1 + 3

## 7. 学习技巧（针对本项目）

1. **用项目测自己**：跑 `/search "限流怎么实现"` 看 RAG、`/index` 看批量 embedding——学的同时验证功能
2. **测试即文档**：`src/test/java/com/chisel/` 下的测试演示系统行为（RagBenchmarkTest、McpServerManagerTest 等）
3. **benchmark 报告可读**：`target/benchmark/rag-benchmark.md` 展示 8 个查询的检索结果，看结果理解算法
4. **别读 34k 行**：主线（Agent/LLM/Tool/MCP/RAG）约 60%，支线看接口和注释即可
5. **动手验证**：每份阶段文档第 7-8 节都有"动手验证"，跟着做一遍比读十遍强

## 8. 项目运行前提速查

```bash
# 环境
export JAVA_HOME=~/tools/jdk-17.0.20+8/Contents/Home
export PATH=$JAVA_HOME/bin:~/tools/apache-maven-3.9.16/bin:$PATH

# 构建 + 运行
mvn clean package
java -jar target/chisel-1.0-SNAPSHOT.jar

# 测试（embedding 服务在线时 RAG 测试才完整）
python3 /tmp/fake_ollama.py &        # 本地弱向量（免费，默认）
NO_COLOR=1 mvn test -Pquick          # 常规回归
NO_COLOR=1 mvn test -DskipTests=false # 全量（慢）
```
