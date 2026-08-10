# ChiselCLI

一个面向商业场景的 Java Agent CLI 产品，对标 Claude Code。

**核心能力**：ReAct / Plan-and-Execute / Multi-Agent 三种执行模式、统一工具调用与并行调度、手写 MCP 客户端（resources / mentions / 动态工具 / OAuth / sampling / 自动重启）、记忆与上下文工程（长短记忆 / 自动压缩 / 项目记忆）、RAG 代码语义检索（分词 + Embedding + SQLite 向量存储 + AST 分块）、HITL 审批流、安全策略与审计日志、JLine 交互 TUI、CDP 浏览器会话复用、LSP 诊断注入、Side-Git 快照回滚、异步后台任务 + Runtime API、图片输入、微信 iLink 通道、下一步建议（Claude Code 式 Tab 补全）。

**已交付**：inline 流式 TUI、LSP 诊断注入、Git Side-History 快照与回滚、Prompt 分层架构、异步后台任务 + Runtime API、图片复制粘贴输入、微信 iLink 通道文本 MVP、MCP OAuth / sampling / server 自动重启、下一步建议（任务完成后异步生成候选指令，空输入按 Tab 一键补全）。

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 或 Agnes API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
# 或
export STEP_API_KEY=your_step_api_key_here
export STEP_MODEL=step-3.5-flash
# 或
export DEEPSEEK_API_KEY=your_deepseek_api_key_here
export DEEPSEEK_MODEL=deepseek-v4-flash
# 或
export KIMI_API_KEY=your_kimi_api_key_here
export KIMI_MODEL=kimi-k2.6
# 或
export FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1
export FREELLMAPI_MODEL=auto
# 或
export AGNES_API_KEY=your_agnes_api_key_here
export AGNES_MODEL=agnes-2.0-flash
export AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
```

也可以在 ChiselCLI 内用命令写入 `~/.chisel/config.json`：

```text
/config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
/model freellmapi
/config provider agnes --api-key <key> --model agnes-2.0-flash --default
/model agnes
```

### 2. 编译运行

```bash
# 编译（默认跳过测试）
mvn clean package

# 运行
java -jar target/chisel-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.chisel.cli.Main"
```

### 3. 测试

```bash
# 终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 代码搜索 deterministic golden set（10 例，产出命中率/耗时基准报告到 target/benchmark/）
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# RAG 语义检索基准（模糊查询对比 search_code vs grep_code，需本地 embedding 服务在线）
mvn test -Dtest=RagBenchmarkTest -DskipTests=false

# 发版或大范围重构前再跑全量
mvn test -DskipTests=false
```

## 三种执行模式

### ReAct（默认）

单轮对话驱动的 `ReAct` 循环：思考 → 行动 → 观察。支持工具调用：读文件、写文件、列目录、文件 glob、代码 grep、执行命令、创建项目、RAG 语义辅助检索、联网搜索、MCP 动态工具。更适合简单任务或单步操作。

### Plan-and-Execute

输入 `/plan` 后，下一条任务使用计划模式：任务分解 → DAG 依赖编排 → 按批次并行执行。执行前会停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划
- 方向键不会触发取消；只有单独按下 `ESC` 才会取消待执行 plan

一条命令切模式并执行：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

执行完成后自动回到默认 `ReAct` 模式。简单任务会自动生成最小计划，不再为了凑步数扩展无关步骤。

### Multi-Agent

输入 `/team` 进入多 Agent 协作：规划者 + 执行者 + 检查者三角色，主从架构编排器自动分配任务，检查者审查质量、未通过自动重试，执行者共享工具集。

## 记忆与上下文

- **短期记忆**：对话历史管理与自动压缩
- **长期记忆**：`/save <事实>` 显式保存跨会话稳定事实；`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear` 可审计可删除
- **项目记忆**：`PAI.md` 分层注入（`~/.chisel/PAI.md` → 项目根 `PAI.md` → `.chisel/PAI.md` → 本地覆盖），`/init` 生成精简版，`/export` 导出完整 system prompt
- **上下文压缩**：大窗口模型按 `window - 20k - 13k` 预留摘要输出与安全缓冲，自动触发
- **长上下文工程**：`/context` 查看当前 window、动态预算、上下文模式、prompt cache、RAG topK；`search_code` 默认 topK 按模式自适应（5 / 10 / 20）

## RAG 代码语义检索

- 代码向量化（本地 Ollama 或远程 API Embedding）
- SQLite 向量存储 + 内存余弦检索
- 文件 / 类 / 方法粒度分块索引（JavaParser AST 分析）
- 代码关系图谱（类继承、接口实现、方法调用）
- 定位策略：精确代码定位优先 `glob_files` / `grep_code` / `read_file`；`search_code` 只做模糊语义或常规搜索无果时的辅助

## MCP 集成

MCP 子系统默认开启。`~/.chisel/mcp.json` 不存在时，ChiselCLI 会自动创建默认 chrome-devtools 配置：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

需要继续接入其他 server 时，可编辑 `~/.chisel/mcp.json` 或项目内 `.chisel/mcp.json`：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "${PROJECT_DIR}"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    },
    "step_search": {
      "url": "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
      "headers": {"Authorization": "Bearer ${STEP_API_KEY}"}
    }
  }
}
```

- `command` 表示 stdio server，`url` 表示 Streamable HTTP server；`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量 / 项目 `.env` / 用户 `~/.env` 读取
- `step_search` 是约定名称：存在 `STEP_API_KEY` 时自动内置该远程 MCP；当前模型为 `step-3.7-flash*` 时，内置 `web_search` / `web_fetch` 会优先代理到该 MCP server
- **OAuth**：远程 server 返回 `401 + WWW-Authenticate` 挑战（`MCP-OAuth` 或 `Bearer resource_metadata=`）时自动走授权码 + PKCE，token 持久化到 `~/.chisel/mcp/oauth-tokens.json`（权限 600），过期自动 refresh
- **sampling**：server 可反向请求 ChiselCLI 用当前模型生成回复，默认允许但审计，`toolCall` 请求拒绝
- **自动重启**：工具调用因网络/进程失败时指数退避自动恢复（默认最多 3 次），成功后重新注册工具
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 清洗 `$ref` / `anyOf` / 超长 description
- 支持 resources：server 声明 `resources` capability 后自动注册 `mcp__{server}__list_resources` / `read_resource` 虚拟工具，用户可 `@server:protocol://path` 显式引用
- 长上下文模式自动把 resources 的 URI / 描述索引注入 system prompt

### Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`（导航 / 输入 / 调试 / 网络 / 性能 / 模拟等）
- 用于 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号、知乎、推特等 `web_fetch` 失败站点自动引导走浏览器
- `/browser connect` 复用已允许远程调试的登录态 Chrome（`chrome://inspect/#remote-debugging` 勾选 Allow remote debugging）；`/browser status` / `/browser tabs` / `/browser disconnect` 管理会话
- 登录态安全约束：敏感页面识别、改写型工具单步 HITL、shared 模式 `close_page` 硬保护
- HITL「全部放行」支持 server 维度

### 使用浏览器读取页面

```text
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

期望路径是 `web_fetch` 尝试失败后，fallback 到 `mcp__chrome-devtools__navigate_page` 与 `take_snapshot`。

## 联网能力

- `web_search`：搜索互联网获取实时信息；支持 Step 3.7 Flash + StepSearch MCP 优先、智谱 Web Search（与 GLM 共用 Key）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）四条路
- `web_fetch`：抓取已知 URL → readability 提取 → 返回 Markdown 正文
- 内置网络访问策略：屏蔽内网 / loopback / `file://`；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙返回空正文 + 已知边界提示，Agent fallback 到浏览器 MCP 路线

## HITL + 安全策略

- **HITL 审批流**：危险操作静态规则识别（`write_file` / `execute_command` / `create_project` / `revert_turn`），三级危险等级，审批决策支持批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行；默认关闭，`/hitl on|off` 运行时切换
- **路径围栏（PathGuard）**：文件类工具强制限定在项目根之内，绝对路径外逃 / `..` 穿越 / 符号链接逃逸全部拦截
- **命令快速拒绝（CommandGuard）**：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`）
- **资源上限**：`write_file` 5MB；`execute_command` 60 秒超时 + 8KB 输出截断
- **结构化审计（AuditLog）**：危险工具调用按天写 JSONL 到 `~/.chisel/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`，`/audit [N]` 查看
- **微信通道策略**：非交互式默认拒绝——只读工具默认允许，`write_file` / `create_project` 受 workspace PathGuard 限制，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝

安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是沙箱、不提供进程隔离。

## LSP 诊断注入

- 按语言惰性启动 LSP server（Java / Rust / Python / Go），`edit_file` / `apply_patch` / `write_file` 后自动收集 `publishDiagnostics`
- 每轮 LLM 请求前把诊断作为合成消息注入（最多 20 条），模型下一轮推理前就能看到编译错误
- LSP server 启动失败或超时只打日志，不阻塞主流程

## Side-Git 快照与回滚

- `SideGitManager`：在 `~/.chisel/snapshots/<project_hash>/<worktree_hash>/.git` 维护独立 side-git 仓库，与工作区 `.git` 完全隔离
- 每个 turn 前后自动快照（`pre-turn <id>` / `post-turn <id>`），`/restore <N>` 一键回滚，不污染用户 git 历史
- `revert_turn` 工具让 Agent 自己判断"改坏了需要撤销"
- 快照策略可配：`max_snapshots`（默认 50）、`snapshot_excludes`（默认排除 `.git/` / `node_modules/` / `target/`）

## 异步后台任务 + Runtime API

- `DurableTaskManager`：SQLite 持久化任务队列，`/task add <prompt>` 提交后台任务，`/task list` / `/task cancel <id>` / `/task log <id>` 管理；进程重启后未完成任务自动重入队
- `RuntimeApiServer`：嵌入式 HTTP/SSE 服务（`chisel serve --http --port 8080`），仅监听 localhost，强制 `CHISEL_RUNTIME_API_KEY` 校验
- OpenAI Assistants API 兼容端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`（SSE 流式事件）

## 图片输入

- 终端粘贴 base64 图片或 `@image:file://path/to/img.png` 显式引用
- MCP `image` content 保留 base64 + mimeType，作为图片 user message 回灌
- 对齐 Claude Code：不 OCR，统一压缩 / 缩放后以图片块发送，带 alpha 的 PNG 铺白底重编码，注入来源 / 尺寸 / 坐标映射元信息
- 不按模型名拦截图片；provider 不支持图片输入时请求序列化层自动省略图片 payload 并保留文本提示

## 微信 iLink 通道（文本 MVP）

- 独立通道，不是 Skill 也不是 Runtime API：iLink `getupdates` 长轮询收消息、`sendmessage` 分片回消息
- 默认不开启：`/wechat` 扫码绑定并后台启动；`chisel wechat setup` 进程级绑定、`chisel wechat start` 前台启动、`chisel wechat daemon start|stop|restart|status|logs` 后台服务管理
- 只接受绑定用户私聊；普通消息单并发排队，`/help` / `/status` / `/pause` / `/resume` / `/stop` 走队列外控制路径
- 微信侧只接收 assistant 正文，保留 ClawBot 稳定支持的 Markdown 子集，标题转粗体、表格转键值/列表
- 使用非交互式默认拒绝策略（见安全策略）
- 文件推送只走显式 `/send <path>` 或工具产物登记表，不做"路径出现即上传"

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过常见构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先使用 ripgrep，返回文件、行号、可选上下文、partial 状态与 suggested_reads
- `execute_command` - 在当前项目目录执行短时 Shell 命令（默认 60 秒超时，黑名单拦截破坏性命令）
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询，模糊语义或常规搜索无果时的辅助）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，ChiselCLI 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

文件类与代码检索工具（`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝；`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` 等。`revert_turn` 会批量回写工作区，默认触发 HITL 和审计。所有 `mcp__` 前缀工具默认触发 HITL 和审计。详见 `/policy`。

## 命令

进程级入口：

- `chisel wechat setup` - 绑定微信 iLink 通道，选择 workspace 并完成扫码确认
- `chisel wechat start` - 前台启动微信通道
- `chisel wechat status` - 查看绑定状态和 daemon pid
- `chisel wechat daemon start|stop|restart|status|logs` - 管理本机微信通道后台进程

交互式斜杠命令：

- `/wechat` - 扫码绑定并启动微信 iLink 通道；已绑定时直接启动
- `/wechat setup` - 重新扫码绑定并启动微信通道
- `/wechat status` - 查看当前 ChiselCLI 进程内微信通道状态
- `/wechat stop` - 停止当前 ChiselCLI 进程内微信通道
- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/team` - 下一条任务使用 Multi-Agent 协作模式
- `/team <任务>` - 直接用 Multi-Agent 协作模式执行这条任务
- `/cancel` - 运行中请求取消当前任务；空闲时会提示当前没有正在运行的任务
- `/hitl on` - 启用危险操作人工审批（HITL）
- `/hitl off` - 关闭 HITL 审批
- `/hitl` - 查看 HITL 当前状态
- `/mcp` - 查看所有 MCP server 状态
- `/mcp restart <name>` - 重启单个 MCP server
- `/mcp logs <name>` - 查看 MCP server 最近 200 行 stderr 日志
- `/mcp disable <name>` - 运行时禁用 MCP server 并移除其工具
- `/mcp enable <name>` - 运行时启用 MCP server
- `/mcp resources <name>` - 查看 MCP server 暴露的 resources
- `/mcp prompts <name>` - 查看 MCP server 暴露的 prompts（只查看，不注入对话）
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/audit [N]` - 查看今日最近 N 条危险工具审计记录（默认 10）
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory list` - 查看长期记忆列表
- `/memory search <关键词>` - 搜索当前项目可见长期记忆
- `/memory delete <id>` - 删除单条长期记忆
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存项目级关键事实到长期记忆；`/save --global <事实>` 保存跨项目通用偏好
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；默认 `scope=project`
- `/init` - 生成精简项目级记忆 `PAI.md`；已存在时不覆盖，`/init --force` 可重写
- `/export` - 导出当前 ReAct 会话对话记录为 Markdown（包含完整 system prompt），写入 `~/.chisel/exports/session-*.md`
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码（RAG 辅助路径）
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空当前对话历史、短期记忆、待注入 Skill 上下文和上一轮检索记忆注入；长期记忆保留
- `/exit` / `/quit` - 退出程序

## 启动界面

```text
      ██        ChiselCLI ⚒  v0.1.0
      ██        Model <model> (<provider>)
      ██        MCP <ready>/<total> · <n> tools · <skills> skills · ReAct
     ████       ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG
    ██  ██      forge · carve · ship
   ██    ██
    ██  ██
     ████

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## 运行效果

```text
      ██        ChiselCLI ⚒  v0.1.0
      ██        Model step-3.5-flash-2603 (step)
      ██        MCP 4/4 · 61 tools · 2/2 skills · ReAct
     ████       ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG
    ██  ██      forge · carve · ship
   ██    ██
    ██  ██
     ████

* 你好，请列出当前目录的文件

🧠 思考过程:
用户想了解当前目录结构。我先读取目录，再基于结果做归类说明，而不是只回原始文件列表。

🤖 最终结果:
当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

* /exit

👋 再见!
```

### Plan-and-Execute 示例

```text
* /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取... ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无        ║
║     创建 demoapp 项目结构                              ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1    ║
║     读取 demoapp/pom.xml 内容                          ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2  ║
║     验证项目结构与 Maven 配置                          ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求后重新规划
```

## 技术栈

- Java 17
- Maven
- 多模型 API（GLM / DeepSeek / StepFun / Kimi / FreeLLMAPI / Agnes / 讯飞星辰）
- OkHttp
- Jackson
- JLine 4（终端交互、Status、输入 widgets）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/chisel
├── agent/       ReAct / Plan-and-Execute / Multi-Agent 执行路径
├── cli/         Main 入口、命令解析、计划审核输入
├── llm/         LlmClient 接口 + 各 provider 客户端
├── context/     上下文模式与 Token 展示
├── memory/      短期/长期记忆、压缩、预算、检索
├── plan/        Planner / ExecutionPlan / Task
├── rag/         Embedding、向量存储、代码分块、关系分析
├── mcp/         MCP 客户端、transport、resources、OAuth、sampling、recovery
├── hitl/        HITL 审批流
├── policy/      PathGuard / CommandGuard / AuditLog
├── browser/     浏览器会话与敏感页面策略
├── lsp/         LSP 诊断
├── snapshot/    Side-Git 快照与回滚
├── skill/       Skill 系统
├── wechat/      iLink 微信通道
├── runtime/     后台任务 + Runtime API
└── render/      Renderer 接口与 inline / plain 实现
```
