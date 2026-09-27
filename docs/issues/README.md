# Issue 记录规范

本文件定义 `docs/issues/` 中活动问题、原始审查快照和解决证据的记录方式。Issue 是项目正式文档中唯一完整保留问题发现、分析和解决过程的目录；它不替代当前技术规格、重大决策或里程碑概况。

对应规则：`DEVELOPMENT_RULES.md` Rule 16。

## 目录约定

- `docs/issues/`：仅保存仍需处理或确认的问题。
- `docs/issues/ISSUE_INDEX.md`：保存各文件编号前缀、最后使用编号和下一编号。
- `docs/issues/resolved/Vx.x/`：保存已在对应版本完成代码验算并通过测试的问题。
- 同一审查文件同时包含已解决和未解决问题时，必须按问题编号拆分，避免归档目录混入待办项。

## 内容边界

- 问题创建时保存可复核的原始审查快照，包括当时的代码、行为、判断依据和建议方向。
- 后续调查可以补充证据，但不得回写原始快照，使其看起来像基于后来代码作出的审查。
- 原始快照中的“状态”“当前代码”和阶段性“待验收”只表示其记录时点，不须因日后归档而改写；归档文件末尾的解决日期、最终解决状态及验证证据才表示该 Issue 的归档结论。
- 最终实现允许不同于最初建议；差异及原因写入“实际修改”，不改写原建议。
- 当前有效的长期架构进入 `docs/decisions/`，当前详细技术契约进入 `docs/specs/`，里程碑概况进入 `docs/progress/`。Issue 只引用这些当前结论，并保留解决该问题所必需的历史。
- 只有代码完成、相关自动化测试通过并完成必要人工验收后，Issue 才能标记为“已修复”并归档。

---

## 文件命名规则

审查记录文件名与源文件名一致：

| 源文件 | 审查记录 |
|--------|---------|
| `App.java` | `docs/issues/App.java.md` |
| `WorldInfo.java` | `docs/issues/WorldInfo.java.md` |
| `MainController.java` | `docs/issues/MainController.java.md` |

---

## 审查记录头部格式

每个文件的审查记录必须包含以下头部：

```markdown
# 代码审查：{文件名}

- **审查日期**：YYYY-MM-DD
- **审查工具**：Claude Code / Gemini / 其他
- **审查范围**：{文件的职责简述}
- **问题总数**：{N} 个（🔴 X / 🟠 X / 🟡 X / 🟢 X）
```

---

## 单个问题格式模板

每个问题必须包含以下 10 个字段：

```markdown
### ISSUE-{文件缩写}-{序号}：{问题标题}

- **严重程度**：🔴 严重 / 🟠 高 / 🟡 中 / 🟢 低
- **类别**：错误处理 / 资源管理 / 安全性 / 代码质量 / 模块耦合 / UI 健壮性
- **文件**：{文件路径}
- **行号**：{行号或行号范围}
- **状态**：待修复 / 已确认 / 已忽略 / 已修复

**问题描述**：
{用一两句话说明问题是什么}

**当前代码**：
​```java
{有问题的代码片段}
​```

**问题分析**：
{详细解释为什么这是一个问题，会产生什么后果}

**建议修改**：
​```java
{建议的修改方案}
​```

**影响范围**：
{说明这个问题影响哪些场景或功能}
```

问题解决后，在保留原始审查快照的基础上，还必须补充：

- **解决日期**：实际完成日期。
- **实际修改**：最终采用的方案；如果与原建议不同，需要说明原因。
- **验证证据**：对应测试、运行结果或人工验算结果。

`当前代码` 表示审查发现问题时的代码快照，不应在问题解决后改写成最新源码。阅读历史记录时应以其审查日期为准，并以末尾解决结论判断最终状态，不能把早期“待修复”或“待验收”误读为今天的活动问题。

归档前必须重新统计问题总数和严重程度，确认编号唯一、每项均有符合归档条件的最终解决状态及验证证据，并保证活动目录不再保留已解决问题；不得为了让原始“状态”字段看起来与最终状态一致而改写审查快照。

## 历史决策编号映射

2026-09-13 对重大决策进行当前化整理和连续重编号。Issue 原始快照中的旧编号不改写；阅读历史引用时使用下表，不得按当前同名文件编号直接推断旧含义：

| Issue 快照中的旧编号 | 当前对应 |
|---|---|
| Java DECISION-005（V0.3 一次性发布安排） | 不再作为重大决策；版本结论见 V0.3/V0.3.1 Progress |
| Java DECISION-006（V0.3.1 实施与验收方案） | 不再作为重大决策；问题过程见对应 Issue，当前体验由 Java 基线和规格表达 |
| Java DECISION-007 | Java DECISION-005 |
| Java DECISION-008 | Java DECISION-006 |
| Java DECISION-009 | Java DECISION-007 |
| Java DECISION-010 | Java DECISION-008 |
| Java DECISION-011 | Java DECISION-009 |
| Java DECISION-012 | Java DECISION-010 |
| Tauri DECISION-013 | Tauri DECISION-011 |
| Tauri DECISION-014 | Tauri DECISION-012 |
| Tauri DECISION-015 | Tauri DECISION-013 |
| Tauri DECISION-016 | Tauri DECISION-014 |
| Tauri DECISION-017 | Tauri DECISION-015 |
| Tauri DECISION-018 | Tauri DECISION-016 |
| Tauri DECISION-019（固定二维切片） | 已废止；二维当前方向见 Tauri DECISION-023 |
| Tauri DECISION-020 | Tauri DECISION-017 |
| Tauri DECISION-021 | Tauri DECISION-018 |
| Tauri DECISION-022（全局单 worker） | 已废止；非地图现状见规格与源码，二维当前方向见 Tauri DECISION-023 |
| Tauri DECISION-023 | Tauri DECISION-019 |
| Tauri DECISION-024 | Tauri DECISION-020 |
| Tauri DECISION-025 | Tauri DECISION-021 |
| Tauri DECISION-026（候选验收流程） | 不再作为重大决策；验证要求见对应规格与 Issue |
| Tauri DECISION-027 | Tauri DECISION-022 |
| Tauri DECISION-028（V0.7.2 范围扩展） | 不再作为重大决策；当前范围见路线、规格和 Tauri DECISION-023 |

---

## 严重程度定义

| 等级 | 标记 | 定义 |
|------|------|------|
| 严重 | 🔴 | 会导致程序崩溃、数据丢失或安全漏洞 |
| 高 | 🟠 | 会导致功能异常或用户体验严重受损 |
| 中 | 🟡 | 会影响代码质量、可维护性或部分场景异常 |
| 低 | 🟢 | 代码风格、命名规范或可选优化 |

---

## 审查维度

每个源文件必须从以下 6 个维度审查：

1. **错误处理**：异常是否被捕获？错误信息是否可见？程序是否会崩溃？
2. **资源管理**：文件流、连接等资源是否正确关闭？是否存在内存泄漏？
3. **安全性**：是否存在路径注入、空指针、数据越界等风险？
4. **代码质量**：是否存在魔法数字、重复代码、死代码、命名不规范？
5. **模块耦合**：类之间依赖是否合理？是否违反单一职责？
6. **UI 健壮性**：界面逻辑是否处理了空数据、异常数据、边界情况？

---

## 问题编号规则

编号格式：`ISSUE-{文件缩写}-{三位序号}`

新增问题前必须先查询 `docs/issues/ISSUE_INDEX.md`：

1. 使用对应前缀的“下一编号”。
2. 编号按文件前缀独立递增。
3. 已解决、已忽略、已归档或已删除的编号不得重复使用。
4. 历史编号存在缺口时，不填补缺号，继续使用历史最大编号加一。
5. 新增问题时必须同步更新编号清单。
6. 新文件没有既有前缀时，应创建简短且唯一的英文大写前缀，从 `001` 开始，并加入编号清单。

| 文件 | 缩写 | 示例 |
|------|------|------|
| `App.java` | `APP` | `ISSUE-APP-001` |
| `WorldInfo.java` | `WORLDINFO` | `ISSUE-WORLDINFO-001` |
| `LevelDatReader.java` | `LEVELDAT` | `ISSUE-LEVELDAT-001` |
| `WorldScanner.java` | `SCANNER` | `ISSUE-SCANNER-001` |
| `MainController.java` | `CONTROLLER` | `ISSUE-CONTROLLER-001` |
| `WorldListCell.java` | `LISTCELL` | `ISSUE-LISTCELL-001` |
| `WorldTreeCell.java` | `TREECELL` | `ISSUE-TREECELL-001` |
