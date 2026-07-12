# 代码审查：WorldTreeCell.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：TreeView 的自定义单元格，用于显示分组名称和世界图标+名称
- **问题总数**：4 个（🔴 0 / 🟠 0 / 🟡 2 / 🟢 2）


---

### ISSUE-TREECELL-001：异常被静默吞掉

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/ui/WorldTreeCell.java`
- **行号**：第 39-41 行
- **状态**：已修复

**问题描述**：
图片加载失败时，异常被 `catch (Exception e)` 捕获后静默丢弃，不记录任何信息。

**当前代码**：
```java
try {
    Path iconPath = info.getFolderPath().resolve("icon.png");
    if (Files.exists(iconPath)) {
        Image icon = new Image(iconPath.toUri().toString(), 32, 32, true, true);
        iconView.setImage(icon);
        setGraphic(iconView);
    } else {
        setGraphic(null);
    }
} catch (Exception e) {
    setGraphic(null);  // 静默失败
}
```

**问题分析**：
- 与 ISSUE-LISTCELL-002 同源，异常被静默吞掉
- 图标加载可能因多种原因失败：文件损坏、权限不足、格式不支持
- 打包后无法追踪失败原因

**建议修改**：
```java
} catch (Exception e) {
    logger.debug("Failed to load icon for {}: {}", info.getLevelName(), e.getMessage());
    setGraphic(null);
}
```

**影响范围**：
- 打包后无法追踪图标加载失败的原因
- 与 ISSUE-APP-001 同源


---

### ISSUE-TREECELL-002：图标路径每次都重新解析，未缓存

- **严重程度**：🟡 中
- **类别**：性能
- **文件**：`src/main/java/com/mcworldexplorer/ui/WorldTreeCell.java`
- **行号**：第 31-33 行
- **状态**：已修复

**问题描述**：
每次 `updateItem()` 被调用时，都重新解析图标路径并加载图片，没有缓存机制。

**当前代码**：
```java
@Override
protected void updateItem(Object item, boolean empty) {
    // ...
    } else if (item instanceof WorldInfo) {
        WorldInfo info = (WorldInfo) item;
        // 每次都重新解析
        Path iconPath = info.getFolderPath().resolve("icon.png");
        if (Files.exists(iconPath)) {
            Image icon = new Image(iconPath.toUri().toString(), 32, 32, true, true);
            // ...
        }
    }
}
```

**问题分析**：
- `updateItem()` 在以下情况会被 JavaFX 框架调用：
  - 用户滚动列表（单元格复用）
  - 列表数据变化
  - 窗口大小变化
- 每次调用都会执行 `Files.exists()` 和 `new Image()`
- 对于大型存档列表（几十个世界），滚动时会频繁触发图片加载
- `Image` 对象没有被缓存，同一个世界的图标会被重复加载

**建议修改**：
```java
// 方案一：使用 ImageCache 工具类
private static final Map<Path, Image> ICON_CACHE = new HashMap<>();

Image icon = ICON_CACHE.computeIfAbsent(iconPath, path ->
    new Image(path.toUri().toString(), 32, 32, true, true)
);

// 方案二：在 WorldInfo 中缓存图标
// WorldInfo 中添加
private Image cachedIcon;
public Image getIcon() { ... }
```

**影响范围**：
- 存档数量多时，滚动列表可能出现卡顿
- 严重程度不高，但优化成本低


---

### ISSUE-TREECELL-003：CSS 样式硬编码在 Java 代码中

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/ui/WorldTreeCell.java`
- **行号**：第 23 行、第 29 行
- **状态**：已修复

**问题描述**：
CSS 样式直接写在 Java 代码中，未使用外部 CSS 文件。

**当前代码**：
```java
// 第 23 行（分组节点）
setStyle("-fx-font-weight: bold; -fx-padding: 5 0 5 0;");

// 第 29 行（世界节点）
setStyle("-fx-font-weight: normal; -fx-padding: 2 0 2 0;");
```

**问题分析**：
- 样式和逻辑混在一起，修改样式需要改 Java 代码
- 如果多个组件需要相同样式，需要重复写
- JavaFX 支持外部 CSS 文件，可以统一管理样式

**建议修改**：
```java
// 1. 创建 styles.css
// .tree-cell-group { -fx-font-weight: bold; -fx-padding: 5 0 5 0; }
// .tree-cell-world { -fx-font-weight: normal; -fx-padding: 2 0 2 0; }

// 2. Java 代码中使用
getStyleClass().add("tree-cell-group");
```

**影响范围**：
- 仅影响代码可维护性，不影响功能
- MVP 阶段问题不大


---

### ISSUE-TREECELL-004：instanceof 类型检查 【连带问题】

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/ui/WorldTreeCell.java` ← → `MainController.java`
- **行号**：第 20 行、第 25 行
- **状态**：已修复
- **连带来源**：ISSUE-CONTROLLER-001

**问题描述**：
与 ISSUE-CONTROLLER-001 同源，`TreeView<Object>` 使用原始类型，导致 `WorldTreeCell` 中必须用 `instanceof` 判断节点类型。

**当前代码**：
```java
if (empty || item == null) {
    // ...
} else if (item instanceof String) {
    // 分组节点
} else if (item instanceof WorldInfo) {
    // 世界节点
}
```

**问题分析**：
- `instanceof` 是运行时类型检查，无法在编译期捕获错误
- 如果后续增加新的节点类型，需要在这里添加新的 `else if` 分支
- 与 ISSUE-CONTROLLER-001 联动，需要同步修改

**建议修改**：
```java
// 如果 ISSUE-CONTROLLER-001 实现了 TreeNodeData 类型：
if (item.isGroup()) {
    setText(item.getGroupName());
} else {
    WorldInfo info = item.getWorldInfo();
    // ...
}
```

**影响范围**：
- 与 ISSUE-CONTROLLER-001 联动，需同步修改
