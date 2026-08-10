# ChiselCLI 学习笔记：Skill 系统 —— 专家手册机制

> 目标：完全掌握 Skill 系统的设计——为什么做、怎么加载、怎么注入、
> 和工具/记忆的区别、预算控制。面试被连环追问也能答。
>
> 一句话：**Skill = 把「零散工具 + 决策指引」打包成可复用的专家手册，
> 模型按场景按需加载，不塞满 system prompt。**

---

## 1. 为什么要有 Skill（设计动机，必答）

**痛点**：Agent 的工具越来越多（11 内置 + 60+ MCP），全塞进 system prompt：
- prompt 爆炸（token 贵，且稀释注意力）
- 决策指引写死在代码里（改一次要重编译）
- 无法按场景按需展开（web 抓取经验、CDP 用法、站点陷阱——不是每个任务都需要）

**方案**：Skill 分两级——
- **索引段**（轻）：所有启用 skill 的 `name + description` 进 system prompt（预算内）
- **正文**（重）：完整 SKILL.md 只在模型调用 `load_skill(name)` 时才注入下一轮 user message

**类比**：
```
system prompt 里只有"目录"（skill 索引）
模型看到目录 → 觉得当前任务匹配 → 调 load_skill 翻到那一页
→ 下一页 user message 里带着完整正文 → 模型按手册干活
```

## 2. 整体架构

```
三层目录扫描（后者整体覆盖前者同名 skill）
  ├─ builtin：jar 内置（SkillBuiltinExtractor 解压到 ~/.chisel/skills-cache/）
  ├─ user：   ~/.chisel/skills/<name>/SKILL.md
  └─ project：<projectDir>/.chisel/skills/<name>/SKILL.md

SKILL.md 结构：
  ---                    ← frontmatter（手写 YAML 子集解析，不引 SnakeYAML）
  name: web-access
  description: ...       ← 进索引段（≤500 codepoint）
  version: 1.0.0
  author: ...
  tags: [...]
  ---
  # 正文（markdown）    ← load_skill 时注入

运行链路：
  SkillRegistry.reload()  → 扫描三层 → Map<name, Skill>
  Agent 构造 system prompt → SkillIndexFormatter.format(enabledSkills()) → 索引段
  LLM 调 load_skill(name)  → SkillContextBuffer.push(name, body)
  下一轮 user message      → SkillContextBuffer.drain() → 前置到用户输入前
```

## 3. 关键类详解

### 3.1 `Skill.java`（数据结构）

```java
public record Skill(
        String name,
        String description,   // 索引段元数据（决定模型是否该加载）
        String version,
        String author,
        List<String> tags,
        Source source,        // BUILTIN / USER / PROJECT
        String body,          // SKILL.md 正文
        Path skillMdPath,
        Path referencesDir    // 站点经验文件目录
) {
    public enum Source { BUILTIN, USER, PROJECT }
}
```

### 3.2 `SkillRegistry.java`（加载与维护）

```java
public synchronized void reload() {
    skillsByName.clear();
    loadDirectory(builtinCacheRoot, Skill.Source.BUILTIN);  // 先内置
    loadDirectory(userSkillsDir, Skill.Source.USER);        // 用户级覆盖
    loadDirectory(projectSkillsDir, Skill.Source.PROJECT);  // 项目级再覆盖
}
// 同名 skill：后扫描的（project）整体覆盖先扫描的（builtin）
```

**启用状态**：`SkillStateStore` 提供 disabled 列表；`enabledSkills()` 过滤掉禁用的。

### 3.3 `SkillFrontmatterParser.java`（frontmatter 解析）

```java
// 手写 YAML 子集解析（设计决策：不引 SnakeYAML，避免为 5 个字段加依赖）
// 解析规则：
// 1. 必须以 "---\n" 开头、以 "---" 结尾（否则 warning）
// 2. 逐行 "key: value" 解析，value 支持引号/裸值/数组（tags: [a, b]）
// 3. 解析失败 → 返回 ParseResult + warnings（skill 仍能加载，只是元数据缺失）
```

### 3.4 `SkillContextBuffer.java`（注入缓冲区，核心）

```java
private static final int MAX_SKILLS = 3;   // 同一会话最多 3 个 skill body
private final Map<String, String> entries = new LinkedHashMap<>();  // 保序

public synchronized void push(String skillName, String body) {
    entries.remove(skillName);   // 同一 skill 重复 push → 替换旧 body 并刷新到末尾
    entries.put(skillName, body);
    while (entries.size() > MAX_SKILLS) {
        String oldest = entries.keySet().iterator().next();  // LRU 淘汰最旧
        entries.remove(oldest);
    }
}

public synchronized String drain() {   // 一次性消费（防跨轮重复注入）
    // 组装成 "## 已加载 Skill：<name>\n<body>\n---\n"
    entries.clear();
    return sb.toString();
}
```

**关键约束（面试易问）**：
- `drain()` 是**一次性消费**——取走即清空，防止同一 skill 内容在多轮重复注入
- **最多 3 个**，超出 LRU 淘汰最旧
- 同一 skill 重复 push → 替换 + 刷新到末尾（不重复）
- `/clear` 调 `clear()` 复位

### 3.5 `SkillIndexFormatter.java`（预算控制，必答）

```java
public static final int MAX_DESCRIPTION_CODEPOINTS = 500;  // 单条描述 ≤500
public static final int MAX_ENABLED_SKILLS = 20;           // 启用上限 20
public static final int MAX_INDEX_BYTES = 4096;            // 索引段 ≤4KB

public static String format(List<Skill> enabled) {
    // 超 20 个 → 按 name 字典序保留前 20 + stderr 警告
    // 单条 desc 超 500 codepoint → truncateByCodepoint 截断
    // 总段超 4096 → 硬截断 + 警告
}
```

**注入位置**：每个 Agent / SubAgent 的 system prompt 末尾，独立段（`## 可用 Skills`）。

## 4. load_skill 工具（触发链）

```java
tools.put("load_skill", new Tool(
        "load_skill",
        "Load full SKILL.md instructions for a skill the system has indexed...",
        createParameters(new Param("name", "string", "the exact kebab-case skill name", true)),
        args -> {
            Skill skill = skillRegistry.findSkill(name);   // 找 + 校验未禁用
            String body = skill.body();
            // 正文 > 5KB → 截断 + 提示 /skill show 看完整
            skillContextBuffer.push(name, injected);       // 进 buffer
            return "已加载 Skill: " + name + "，完整指引将在下一轮注入";
        }
));
```

**完整链路**：
```
system prompt 索引段（"可用 Skills"）→ 模型看到 web-access 的描述
  → 判断当前任务匹配（"帮我抓微信公众号文章"）
  → 调 load_skill("web-access")
  → 返回 "已加载 Skill，下一轮注入"
  → 下一轮 user message 开头出现 "## 已加载 Skill：web-access\n<正文>"
  → 模型按手册执行
```

## 5. 与工具 / 记忆的区别（面试高频追问）

| 维度 | Skill | 工具（Tool） | 记忆（Memory） |
|------|-------|-------------|---------------|
| 本质 | 决策指引（怎么用工具） | 能力（能做什么） | 事实（以前知道什么） |
| 存储 | SKILL.md 文件 | 代码注册 | JSON/PAI.md |
| 触发 | 模型调 load_skill | 模型调工具 | 自动检索注入 |
| 注入点 | 下一轮 user message | 工具调用 | system prompt |
| 生命周期 | 会话内（3 个上限） | 常驻 | 跨会话 |
| 类比 | 专家手册 | 工具箱 | 笔记本 |

**面试回答**：Skill 不是工具也不是记忆——工具是"能力"，记忆是"事实"，Skill 是"**怎么用能力的决策手册**"。比如 web-access 这个 Skill 不提供抓取能力（web_fetch 工具提供），它告诉模型"什么时候该用浏览器、什么时候该用 web_fetch、微信文章有哪些已知陷阱"。

## 6. 内置 web-access Skill（案例）

```
~/.chisel/skills-cache/web-access/
  ├── SKILL.md          ← 决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）
  └── references/
      ├── mp.weixin.qq.com.md    ← 站点经验（按域名累积）
      ├── zhuanlan.zhihu.com.md
      ├── x.com.md
      ├── xiaohongshu.com.md
      ├── github.com.md
      └── juejin.cn.md
```

**设计意图**：把「web_fetch 失败 → 浏览器 MCP → 站点特定坑」这类经验沉淀成可复用文件，按域名组织，Agent 遇到对应站点自动加载对应经验。

## 7. 面试 Q&A（拷打版）

**Q1：Skill 和直接把指引写进 system prompt 有什么区别？**
A：两级加载。索引段只放 name+description（预算内 ≤4KB），正文按需注入。如果全塞 prompt，20 个 skill 的正文可能几万 token，且多数与当前任务无关——浪费且稀释注意力。按需加载是 token 与可用性的平衡。

**Q2：怎么防止 skill body 重复注入？**
A：SkillContextBuffer 的 drain() 是一次性消费（取走即清空）；同一 skill 重复 push 会替换旧 body 并刷新到末尾（不重复）；上限 3 个超出 LRU 淘汰。

**Q3：怎么防止 skill 索引段把 system prompt 撑爆？**
A：三层预算——单条 description ≤500 codepoint、启用上限 20 个、索引段总 ≤4096 字符，超限硬截断 + stderr 警告。

**Q4：三层目录的覆盖规则？**
A：builtin → user → project 顺序扫描，后扫描的同名 skill 整体覆盖先扫描的（project 优先级最高）。启用状态由 SkillStateStore 的 disabled 列表过滤。

**Q5：为什么不用 SnakeYAML 解析 frontmatter？**
A：只有 5 个字段（name/description/version/author/tags），为一个字段格式引入一个 YAML 库是过度设计。手写子集解析 + 失败降级（warnings 不阻塞加载）。

**Q6：Skill 内调用危险工具（execute_command / 浏览器 MCP）怎么管？**
A：不走 Skill 的独立审批维度——Skill 内的危险工具调用**仍然走正常 HITL/策略层**（execute_command 工具维度全放行、浏览器 MCP 按 server 维度）。Skill 只提供"指引"，不提升权限。

**Q7：三个 SubAgent（Planner/Worker/Reviewer）会共享 Skill buffer 吗？**
A：不会——各持一个独立 SkillContextBuffer 实例，避免角色间提示词污染（Planner 加载的 skill 不会漏给 Worker）。

**Q8：load_skill 的正文有多大？**
A：注入时截断到 5KB（超出提示 `/skill show <name>` 看完整），防止单个 skill 正文撑爆下一轮输入。

## 8. 动手验证

```bash
java -jar target/chisel-1.0-SNAPSHOT.jar

# 1. 看 skill 列表
/skill list

# 2. 看某个 skill 的完整内容
/skill show web-access

# 3. 启用/禁用
/skill off web-access    # 禁用
/skill on web-access     # 启用

# 4. 触发加载（对 web 相关任务）
> 帮我看下 https://mp.weixin.qq.com/s/xxx 这篇文章
# 观察模型是否调 load_skill("web-access")，下一轮注入 "## 已加载 Skill：web-access"
```

## 9. 学习顺序

1. 第 1 节设计动机（10 分钟，为什么做）
2. 第 2 节架构图（10 分钟）
3. 第 3 节 3.4 SkillContextBuffer（15 分钟，核心）
4. 第 3 节 3.5 预算控制（10 分钟）
5. 第 4 节 load_skill 触发链（10 分钟）
6. 第 5 节与工具/记忆对比（10 分钟，面试重点）
7. 第 7 节 Q&A 逐个过（20 分钟）
8. 第 8 节动手验证（15 分钟）
