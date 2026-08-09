# PAI.md

## Commands

- 构建：`mvn clean package` 默认跳过测试，优先产出可手工验收 jar。
- 常规回归：`mvn test -Pquick`；TUI 相关跑 `mvn test -Pphase16-smoke`。
- 针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。

## Dev Environment（2025-08 搭建，换机器/换线程先看这里）

- 仓库目录：`~/Documents/Project/chisel`。
- Java：Temurin JDK 17，位于 `~/tools/jdk-17.0.20+8/Contents/Home`（`~/.zshrc` 已配 `JAVA_HOME`）。
- Maven：3.9.16，位于 `~/tools/apache-maven-3.9.16`（`~/.zshrc` 已配 PATH）；依赖走阿里云镜像（`~/.m2/settings.xml`）。
- 跑测试两个前提，否则会红：
  1. `NO_COLOR=1`（否则 TerminalMarkdownRenderer 断言失败，ANSI 码计入行长）。
  2. 本地 embedding 服务在线（否则 CodeIndex 索引 0 块）：`python3 /tmp/fake_ollama.py &`（监听 11434，返回固定向量）。
- 全量回归：`NO_COLOR=1 mvn test -Pquick`（770 个测试全绿基线）。

## Project Identity（2025-08 确立）

- ChiselCLI 是独立开发的 Java Agent CLI 产品：包 `com.chisel`，banner `v0.1.0`，配置目录 `~/.chisel/`，环境变量 `CHISEL_*`，logo 为凿子（Chisel）主题。
- Git 历史：单 commit，作者 Andy_Muyu，无 remote。
- 定位：面向商业使用的 Agent CLI，对标 Claude Code；能力清单见 AGENTS.md「已交付能力」。

## What This Is

ChiselCLI 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code；当前主路径是 ReAct、Plan-and-Execute、Multi-Agent 三套执行模式。

## Architecture

- 三条执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`，不要为某个模式创建孤立能力。
- 精确代码定位优先 `glob_files` / `grep_code` / `read_file`，`search_code` 只做 RAG 语义辅助。
- system prompt 由 `PromptAssembler` 分层组装，内置 prompt 在 `src/main/resources/prompts/`，支持 `~/.chisel/prompts/` 和 `.chisel/prompts/` 覆盖。

## Things That Will Bite You

- 改行为要同步 `AGENTS.md` / `README.md` / `ROADMAP.md`；路线图只在状态变化时更新。
- 改命令入口要联动 `Main.java`、`CliCommandParser.java`、测试、`README.md`、`AGENTS.md`。
- 改工具集要联动 `ToolRegistry.java`、Agent/Plan/SubAgent 提示词和文档。
- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实。
- `ctx` 表示下一轮仍会带入请求的上下文估算；`in/out/cache` 表示最近任务 LLM 调用统计，不要混用。

## Don't

- 不提交 `.env`、真实 API Key、`target/` 产物。
- 不把 `ROADMAP.md` 的未来计划写成已交付能力。
- 不在交互主路径新增裸 `System.out.println`；优先走 `Renderer.stream()`。
