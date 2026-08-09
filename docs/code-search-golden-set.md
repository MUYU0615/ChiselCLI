# Code Search Golden Set

ChiselCLI 的代码理解默认走 `glob_files -> grep_code -> read_file`，`search_code` 只作为语义辅助。这个 golden set 用来固定确定性搜索链路的最低质量线：给定一个真实代码问题，`grep_code` 必须在预算内定位到预期文件和行号，随后 `read_file offset/limit` 必须能读取到目标上下文。

## 运行命令

```bash
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false
```

`mvn test -Pquick` 也会覆盖该测试。

## 当前评测内容

用例文件：`src/test/resources/code-search/golden-set.json`

每个 case 包含：

- `question`：用户可能提出的自然语言问题，用作评测语义说明
- `pattern`：本轮确定性 grep 关键词
- `glob`：限定候选文件范围
- `expectedPath`：应命中的目标文件
- `expectedText`：命中后 `read_file` 应读到的关键代码片段

测试逻辑：

1. 强制 `grep_code` 使用 Java fallback，避免 CI 依赖本机是否安装 `ripgrep`
2. 限制 `max_chars=6000`，模拟单轮工具结果预算
3. 断言 `grep_code` 返回 `expectedPath:line`
4. 断言结果包含 `suggested_reads`
5. 用 `read_file offset/limit` 读取命中附近 80 行并确认包含 `expectedText`

## 扩展规则

新增 case 时优先选择真实用户会问的问题，例如：

- 命令入口在哪里处理？
- 某个安全策略在哪里拦截？
- 某个多模态或 MCP 行为由哪个类负责？
- 某个渲染或 TUI 行为在哪里落地？

`pattern` 应该能代表 Agent 在第一轮会提取出的明确符号、字符串或文案。若问题只能靠模糊语义定位，先不要放进这个 deterministic golden set，应单独进入 `search_code` / RAG fallback 评测。

## 基准指标（已落地）

`CodeSearchGoldenSetTest` 现在同时产出量化报告到 `target/benchmark/code-search-benchmark.md`：

- **命中率**：grep 定位到预期 `文件:行号` 的用例占比（golden set 10 例，硬性要求 100%）
- **输出预算内占比**：grep 结果 ≤ 6000 chars 的用例占比（硬性要求 100%）
- **耗时分布**：单用例 grep+read 总耗时 P50 / P95

当前基准（2025-08 实测）：命中率 10/10 = 100%，P50 ≈ 8ms，P95 ≈ 35ms。

## RAG 语义检索基准（RagBenchmarkTest）

`search_code` 面向「模型不知道确切符号、只能描述意图」的模糊查询。`RagBenchmarkTest`
用 8 个模糊问题对比两条链路，报告写到 `target/benchmark/rag-benchmark.md`：

- `search_code`（RAG 混合检索：Embedding + 关键词 + 加分重排）
- `grep_code`（用查询里的英文 token 当关键词）

当前基准（2025-08 实测，本地弱向量模型）：RAG 命中率 4/8 = 50%，grep 命中率 2/8 = 25%；
RAG 单独命中的用例（JSON-RPC 配对 / 快照回滚 / 微信策略）均为 grep 完全失败、只能靠语义定位的场景。
真实 embedding 模型（如 nomic-embed-text）下命中率会更高，本基准给出的是保守下限。
