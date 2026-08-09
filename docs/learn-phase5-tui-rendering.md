# ChiselCLI 学习笔记：阶段 5 —— TUI + 渲染层

> 目标：掌握渲染架构——`Renderer` 接口抽象、inline 流式实现（默认）、
> 三种形态切换、JLine Status 底部 dock、输出收敛原则。
> 对应面试必讲：接口抽象多形态、输出统一走 Renderer、避免 stdout 争抢。

---

## 1. 渲染架构：一个接口，三种实现

```
Renderer（接口）
  ├─ InlineRenderer（默认）—— 流式 TUI（Claude Code 风格）
  ├─ LanternaRenderer    —— 全屏 TUI（CHISEL_RENDERER=lanterna）
  └─ PlainRenderer       —— 纯文本兜底（终端不支持 ANSI 时）
```

**选择逻辑**（RendererFactory）：
```
-Dchisel.renderer > CHISEL_RENDERER 环境变量 > 默认 inline
CHISEL_TUI=true → 兼容映射到 lanterna（打 deprecation 提示）
inline 初始化失败（终端不支持 ANSI）→ 自动 fallback 到 plain
```

## 2. Renderer 接口（对话期所有输出的收口）

```java
public interface Renderer extends AutoCloseable {
    void start();                       // 启动（设置滚动区域等）
    default void beginTurn() {}         // 开始一次用户任务输出
    default void beforeInput() {}       // 进入用户输入前
    default void afterInput() {}        // 用户输入结束后
    default boolean supportsThinkingPanel() { return false; }
    default boolean rendersReasoning() { return true; }
    PrintStream stream();               // 正文输出流（核心）
    // 还有：appendToolCalls（行内折叠工具块）、HITL 提示、状态栏更新、diff 等
}
```

**线程模型**（接口注释）：所有方法在调用方线程同步返回；涉及异步的实现自己负责线程封送。

## 3. 输出收敛原则（关键设计）

```
❌ 错误：Agent / Planner / Orchestrator 各自 System.out.println
        → 多个组件直接争抢终端光标，输出交错

✅ 正确：全部走 renderer.stream()
        → Main、PlanExecuteAgent、Planner、AgentOrchestrator 都把输出流
          接到 inline renderer，单点控制
```

**AGENTS.md 明确规则**：除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。

## 4. InlineRenderer 关键机制

### 4.1 绑定 LineReader（首屏不丢）

```java
// 首屏通过 installStartupScreen 挂到 LineReader.CALLBACK_INIT
// 首次进入 readLine 时用 printAbove 一次性打印完整 Banner + tips
// 避免：直接 stdout 打印的 logo 被 LineReader 首次重绘滚出可视区域
```

### 4.2 输出优先 printAbove

```java
// 当 LineReader.isReading() 为 true 时
// Renderer.stream() 的完整行输出优先通过 LineReader.printAbove 显示在输入行上方
// 未绑定 / 非读取态 / 测试路径 → 回退到原 PrintStream
```

### 4.3 live thinking 区

```java
// 固定高度 live 区动态显示 "Thinking..." 和灰色竖线 reasoning 预览
// 约束：只能清理自己刚打印的几行
// 不能用独立 JLine Display.update() / CLEAR_TO_EOS 向上覆盖 transcript
// content 或 tool call 开始前先清掉 live 区 → 完整 reasoning 引用块落到正文区
```

## 5. 底部 dock（BottomStatusBar）

**JLine Status 托管**（不是手写清屏）：

```java
// 由 JLine 维护滚动区域和状态行位置
// 不再手写 \n / moveUp / CLEAR_TO_EOS
status = Status.getStatus(terminal);
```

**两层信息**：
```
上层：模式（YOLO/HITL）+ MCP/Skill 摘要
下层：Auto Model / model / phase / ctx 百分比 + token / cost / elapsed / cwd
```

**关键字段语义（面试易问）**：
- `ctx`：**下一轮仍会带入请求的上下文估算**（不是累计用量）
- `in/out/cache`：最近任务的 LLM 调用统计
- **二者不要混用**——ctx 是"即将花的钱"，in/out 是"刚花过的钱"

**样式**：关键字段用克制的 JLine `AttributedString` 彩色高亮，但纯文本格式和宽度裁剪逻辑保持稳定。

## 6. 输入交互（JLine LineReader）

- **ChiselHighlighter**：输入实时高亮（slash 命令、@ 引用、@image:、敏感词、危险 shell 片段）——只做视觉提示，不混入提交文本
- **ChiselCompleter**：上下文补全（/model provider、/mcp 子命令、@image: 路径、@server:uri）——统一出口
- **ChiselHistory**：输入历史持久化到 `~/.chisel/history/input.history`，忽略空白/重复/密钥/base64 图片/超长输入

## 7. 面试 Q&A

**Q1：为什么输出统一走 Renderer.stream()？**
A：Agent/Plan/Team 多个组件同时往 stdout 写会争抢终端光标、输出交错。统一走 renderer 单点控制，inline 实现用 printAbove/Status 管理终端布局。

**Q2：首屏 logo 怎么避免被 LineReader 重绘冲掉？**
A：不裸写 stdout，通过 `installStartupScreen` 挂到 `LineReader.CALLBACK_INIT`，首次 readLine 时用 printAbove 一次性打印。

**Q3：ctx 和 in/out 有什么区别？**
A：ctx 是下一轮还会带入请求的上下文估算（即将花的钱）；in/out/cache 是最近任务 LLM 调用统计（刚花过的）。两者不要混用。

**Q4：live thinking 区为什么不能向上覆盖 transcript？**
A：它只能清理自己刚打印的几行；用 Display.update()/CLEAR_TO_EOS 会破坏 transcript 的历史内容。content 开始前先清 live 区，再把 reasoning 落到正文区。

## 8. 动手验证

```bash
# 1. 三种形态切换
CHISEL_RENDERER=plain java -jar target/chisel-1.0-SNAPSHOT.jar
CHISEL_RENDERER=lanterna java -jar target/chisel-1.0-SNAPSHOT.jar

# 2. 观察底部 dock
java -jar target/chisel-1.0-SNAPSHOT.jar
# 注意下层状态行：model / ctx 百分比 / token / cwd

# 3. 观察行内工具折叠
> 读取 src 目录和 pom.xml
# 工具执行时出现 "Read 2 files (ctrl+o to expand)"
```

## 9. 学习顺序

1. 第 2 节 Renderer 接口（10 分钟）
2. 第 3 节输出收敛原则（5 分钟，理解设计动机）
3. 第 4 节 InlineRenderer 机制（20 分钟）
4. 第 5 节底部 dock（15 分钟）
5. 第 7 节 Q&A + 第 8 节动手验证
6. 完成后可进阶段 6（支线：微信/LSP/快照/Runtime API）
