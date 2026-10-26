# 代码审查：BlockModelResolver.java

- **审查日期**：2026-10-26
- **审查工具**：Codex
- **审查范围**：V0.7.3 / `src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java` 的增量及相关调用链
- **问题总数**：4 个（严重 0 / 高 0 / 中 4 / 低 0）
- **状态**：V0.7.3最终复查通过，开发者已授权本期完整提交推送；源码0.7.3，未创建公开Release。

## 六维复查

- **错误处理**：语义失败返回 Reason，来源关闭/变化继续传播；loader 冲突只接受已知等价命名空间。
- **资源管理**：解析缓存节点/字符、父链、组合深度、部件与面数有界。
- **安全性**：未知语义字段/loader 明确拒绝，OBJ 路径由资源 ID 约束。
- **代码质量**：继承合并保持父 JSON 不变；按格式分发，不加入方块名称配材质表。
- **模块耦合**：标准、声明式 loader 与内置渲染族保持分工；不执行 Mod。
- **UI 健壮性**：静态近似附说明；空几何不统计为可见模型；OBJ uvlock 未支持明确降级。

### ISSUE-BMODEL-004：未引用的编辑器纹理变量导致实际模型失败

- **严重程度**：中
- **类别**：错误处理
- **文件**：src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java
- **行号**：232（修复后定位；下方为审查时快照）
- **状态**：已修复

**问题描述**：未引用的编辑器纹理变量导致实际模型失败。

**当前代码（审查时快照）**：
```java
for (String key : textures.keySet()) texture("#" + key, textures);
```

**问题分析**：只有被几何/材质引用的纹理才是当前静态模型的必要资源。

**建议修改**：按实际 face/OBJ 材质引用解析，保留被引用变量的缺失/循环检测。

**影响范围**：V0.7.3 实例资源驱动的三维预览。

- **解决日期**：2026-10-26。
- **实际修改**：按实际 face/OBJ 材质引用解析，保留被引用变量的缺失/循环检测。
- **验证证据**：childElementsReplaceParentsAndOnlyReferencedTexturesAreResolved；最终执行结果见 [V0.7.3](../../../progress/V0.7.3.md)，最终393项回归通过（378通过/15条件跳过）。

### ISSUE-BMODEL-005：等价跨平台 loader ID 被误判冲突

- **严重程度**：中
- **类别**：错误处理
- **文件**：src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java
- **行号**：142（修复后定位；下方为审查时快照）
- **状态**：已修复

**问题描述**：等价跨平台 loader ID 被误判冲突。

**当前代码（审查时快照）**：
```java
!string(definition.get("porting_lib:loader")).equals(loader)
```

**问题分析**：真实模型同时给 neoforge:obj 和 porting_lib:obj，语义相同但字符串不同。

**建议修改**：仅对已知 forge/neoforge/porting_lib 同格式 ID 视为等价，其他冲突仍失败。

**影响范围**：V0.7.3 实例资源驱动的三维预览。

- **解决日期**：2026-10-26。
- **实际修改**：仅对已知 forge/neoforge/porting_lib 同格式 ID 视为等价，其他冲突仍失败。
- **验证证据**：equivalentCrossPlatformObjDeclarationsDoNotConflict；最终执行结果见 [V0.7.3](../../../progress/V0.7.3.md)，最终393项回归通过（378通过/15条件跳过）。

### ISSUE-BMODEL-006：multipart 基本值条件被过严字符串检查拒绝

- **严重程度**：中
- **类别**：错误处理
- **文件**：src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java
- **行号**：401（修复后定位；下方为审查时快照）
- **状态**：已修复

**问题描述**：multipart 基本值条件被过严字符串检查拒绝。

**当前代码（审查时快照）**：
```java
alternatives(string(entry.getValue()), properties.get(entry.getKey()))
```

**问题分析**：真实 JSON 的 true/false 等条件可与 palette 存储的字符串属性对应。

**建议修改**：只在属性条件位置接受有界基本值，模型/资源 ID 仍要求字符串。

**影响范围**：V0.7.3 实例资源驱动的三维预览。

- **解决日期**：2026-10-26。
- **实际修改**：只在属性条件位置接受有界基本值，模型/资源 ID 仍要求字符串。
- **验证证据**：multipartBooleanConditionsUseTheStoredPropertyValue；最终执行结果见 [V0.7.3](../../../progress/V0.7.3.md)，最终393项回归通过（378通过/15条件跳过）。

### ISSUE-BMODEL-007：组合模型默认材质未传递给子部件

- **严重程度**：中
- **类别**：错误处理
- **文件**：src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java
- **行号**：227（修复后定位；下方为审查时快照）
- **状态**：已修复

**问题描述**：组合模型默认材质未传递给子部件。

**当前代码（审查时快照）**：
```java
return result; // composite children
```

**问题分析**：外层指定透明材质而子部件未重定义时，应保留外层默认类型。

**建议修改**：仅为没有显式材质的子部件补默认 render_type，保留子级覆盖。

**影响范围**：V0.7.3 实例资源驱动的三维预览。

- **解决日期**：2026-10-26。
- **实际修改**：仅为没有显式材质的子部件补默认 render_type，保留子级覆盖。
- **验证证据**：compositeDefaultMaterialKeepsChildOverrides；最终执行结果见 [V0.7.3](../../../progress/V0.7.3.md)，最终393项回归通过（378通过/15条件跳过）。

## 验证边界

完整结果与限制统一见 [V0.7.3](../../../progress/V0.7.3.md)。来源/格式回归、真实样本与 GL 绘制分别记录；不将隐藏帧缓冲测量视作交互 FPS，不将静态准备统计当作任意运行时模型兼容。


## 统一提交前最终复核（2026-10-26）

当前完整源码109套件/395项：380通过、15条件跳过、零失败/错误。相关输入只读、打包与真实资源/界面/原生绘制检查见[V0.7.3](../../../progress/V0.7.3.md)最终收口记录。既有六维结论复核无新增本期阻塞项；本条替代此前SNAPSHOT未授权交付状态，历史测试快照不反写。
