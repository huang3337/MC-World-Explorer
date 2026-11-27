# 代码审查：build.gradle（含 sdk/build.gradle、app/build.gradle）

- **审查日期**：2026-11-27
- **审查工具**：Gemini
- **审查范围**：双模块构建配置、Maven POM 许可证声明、跨模块测试任务依赖与核心隔离门禁
- **问题总数**：2 个（🔴 1 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-BUILD-005：`sdk/build.gradle` 的 Maven 发布元数据将仓库 GNU GPL v3.0 许可证误写为 MIT

- **严重程度**：🔴 严重
- **类别**：安全性 / 许可证合规
- **文件**：`sdk/build.gradle`
- **行号**：63–68（审查时快照）
- **状态**：已修复

**问题描述**：
项目根目录 `LICENSE` 与 `README.md` 明确采用 `GNU General Public License v3.0`（GPL v3.0），而 `sdk/build.gradle` 的 `publishing.publications.mavenJava.pom.licenses` 将许可证名称与链接写成了 `MIT License`。

**当前代码**（审查时快照）：
```groovy
licenses {
    license {
        name = 'MIT License'
        url = 'https://opensource.org/licenses/MIT'
    }
}
```

**问题分析**：
Maven POM 会随 `publishToMavenLocal` 与 JitPack 直接向外部开发者分发；错误的许可证声明违反开源许可证一致性与 `DEVELOPMENT_RULES.md` Rule 20 关于保留真实许可证的强制要求。

**建议修改**：
修正 `sdk/build.gradle` 中的 POM 许可证为 `GNU General Public License v3.0`（`https://www.gnu.org/licenses/gpl-3.0.html`），并在 `SdkPurityContractTest` 中增加许可证契约断言。

**影响范围**：
`:mc-world-explorer-sdk` 的 Maven / JitPack 发布 POM 元数据。

- **解决日期**：2026-11-27
- **实际修改**：将 `sdk/build.gradle` 的 POM 许可证修正为 `GNU General Public License v3.0`，并在 `SdkPurityContractTest.sdkMavenPublicationDeclaresProjectGplV3License` 增加回归断言。
- **验证证据**：`SdkPurityContractTest` 与 `:mc-world-explorer-sdk:publishToMavenLocal` 生成的 `pom-default.xml` 核验通过。

---

### ISSUE-BUILD-006：`app/build.gradle` 引用 `sdk` 测试输出缺少编译任务依赖，且 SDK 纯净度门禁漏检部分桌面端包前缀

- **严重程度**：🟠 高
- **类别**：模块耦合 / 构建确定性
- **文件**：`app/build.gradle`、`build.gradle`
- **行号**：`app/build.gradle` 92、`build.gradle` 102（审查时快照）
- **状态**：已修复

**问题描述**：
1. `app/build.gradle` 通过 `testImplementation project(':mc-world-explorer-sdk').sourceSets.test.output` 复用 `sdk` 测试夹具，但未声明 `:mc-world-explorer-app:compileTestJava` 对 `:mc-world-explorer-sdk:testClasses` 的任务依赖；在清空构建缓存后单独执行 `:mc-world-explorer-app:test` 会因 `sdk` 测试类尚未编译而报错。
2. 根 `build.gradle` 的 `verifyCoreIsolation` 仅拦截了 `ui/` 与 `experimental/`，未将 `update/`、`viewer/lwjgl/`、`voxel/render/lwjgl/`、`App` 与 `Launcher` 纳入 SDK 禁止引用列表。

**当前代码**（审查时快照）：
```groovy
testImplementation project(':mc-world-explorer-sdk').sourceSets.test.output
...
def forbiddenSdkRefs = ['javafx/', 'org/lwjgl/', 'org/kordamp/ikonli/', 'com/mcworldexplorer/ui/', 'com/mcworldexplorer/experimental/']
```

**问题分析**：
直接引用另一子模块的 `sourceSets.test.output` 属于底层文件集合，必须显式绑定 `testClasses` 任务顺序以保证单子模块测试命令的构建确定性；同时将 `app` 所属全部子包纳入字节码门禁可彻底杜绝跨模块反向引用。

**建议修改**：
在 `app/build.gradle` 为 `compileTestJava` 添加 `dependsOn ':mc-world-explorer-sdk:testClasses'`；在根 `build.gradle` 与 `SdkPurityContractTest` 中补全全部桌面端包与入口类前缀检查。

**影响范围**：
独立执行 `:mc-world-explorer-app:test` 的构建顺序与 `verifyCoreIsolation` 纯净度门禁。

- **解决日期**：2026-11-27
- **实际修改**：补齐 `compileTestJava` 对 `:mc-world-explorer-sdk:testClasses` 的依赖，并同步扩充 `verifyCoreIsolation` 与 `SdkPurityContractTest` 的禁止引用前缀列表。
- **验证证据**：清理后独立执行 `:mc-world-explorer-app:test` 与全量 `check verifyCoreIsolation` 通过。
