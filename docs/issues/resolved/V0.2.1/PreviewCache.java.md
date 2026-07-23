# 代码审查：PreviewCache.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：多维度分层缓存路径、元数据、失效判断和原子替换
- **问题总数**：2 个（🔴 0 / 🟠 0 / 🟡 2 / 🟢 0）

## 当前结论

当前缓存写入和复用入口都会验证维度 Region 目录位于当前世界内部；复用前还会验证 PNG 可解码且尺寸正确。两项问题均已解决；问题代码为 V0.2.1 开发或审查阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-CACHE-001：缓存入口未验证维度 Region 路径属于当前世界

- **严重程度**：🟡 中
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewCache.java`
- **行号**：45、109-114、264
- **状态**：已修复

**问题描述**：
生成器已拒绝世界目录外的 Region 路径，但首版缓存 `store/findReusable` 可接受指向其他目录的自定义维度请求。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
Path regionDirectory = request.dimension().regionDirectory();
return regionState(request);
```

**问题分析**：
虽然当前 UI 只使用发现器创建的内部路径，但缓存公共入口缺少同样约束，会扩大读取边界并让测试或未来调用误关联其他世界数据。

**建议修改**：
```java
if (!regionDirectory.toAbsolutePath().normalize()
        .startsWith(worldDirectory.toAbsolutePath().normalize())) {
    throw new IllegalArgumentException(...);
}
```

**影响范围**：
缓存写入、缓存复用和相关 Region 状态计算。

- **解决日期**：2026-07-24
- **实际修改**：在 `store` 和 `findReusable` 入口统一调用世界内部 Region 边界验证。
- **验证证据**：`PreviewCacheTest.rejectsCacheRequestsForRegionDirectoriesOutsideTheWorld` 直接覆盖缓存边界；缓存分维度/分图层测试和真实存档写入边界复验通过。

### ISSUE-CACHE-002：有效元数据会让损坏 PNG 被永久复用

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewCache.java`
- **行号**：审查阶段 258-302；当前实现 245-304
- **状态**：已修复

**问题描述**：
缓存复用曾只检查 PNG 文件存在、元数据字段和源 Region 状态。PNG 被截断或覆盖而元数据仍有效时，后续请求会持续命中同一坏文件。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
if (!Files.isRegularFile(imagePath) || !Files.isRegularFile(metadataPath)) {
    return Optional.empty();
}
// 元数据和源文件状态通过后直接返回缓存。
return Optional.of(new PreviewCacheResult(imagePath, metadataPath));
```

**问题分析**：
元数据只能证明缓存身份和生成时的源文件状态，不能证明 PNG 当前仍可解码。持续命中坏文件会让控制器每次都显示读取失败，用户只能手动删除缓存。

**建议修改**：
```java
BufferedImage image = ImageIO.read(imagePath.toFile());
if (image == null
        || image.getWidth() != PreviewGenerator.OUTPUT_SIZE
        || image.getHeight() != PreviewGenerator.OUTPUT_SIZE) {
    return Optional.empty();
}
```

读取或解码异常同样按缓存未命中处理，复用既有生成和原子替换流程重建文件。

**影响范围**：
所有世界、维度和图层的缓存复用；不影响 Minecraft 存档文件。

- **解决日期**：2026-07-24
- **实际修改**：`findReusable` 在后台使用 `ImageIO.read` 验证图片可解码，并检查尺寸为生成器规定的 `512 x 512`；解码失败、尺寸异常或读取异常均视为缓存未命中，由既有生成和原子替换流程重建缓存。
- **验证证据**：`PreviewCacheTest.rejectsCorruptedPngEvenWhenMetadataStillMatches` 通过；默认环境与真实目录全量测试均通过。
