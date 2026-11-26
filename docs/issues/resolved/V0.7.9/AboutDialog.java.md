# 代码审查：AboutDialog.java

- **审查日期**：2026-11-26
- **审查范围**：「关于」模态卡片双仓库最新 Release 链接常驻展示与检查更新醒目提示重置行为
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-ABOUTDIALOG-001：双仓库 Release 入口未常驻指向最新发行版且更新提示框在关闭重开后残留

- **严重程度**：🟠 高
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/AboutDialog.java`
- **行号**：79–200（审查时快照）
- **状态**：已修复

**问题描述**：
「关于」卡片原实现仅在点击「检查更新」发现新版本或请求失败后才在动态 `updateBox` 中渲染 Gitee 与 GitHub 按钮，日常打开卡片时缺少国内镜像仓常驻入口，且默认回退地址指向 `/releases` 列表页而非 `/releases/latest`；同时 `MainController.showAboutModal()` 将缓存的 `latestUpdateResult` 直接传入新建卡片，导致关闭「关于」卡片再重新打开时更新框未按预期收起。

**当前代码**（审查时快照）：
```java
var giteeBtn = new Button("前往国内镜像仓库");
giteeBtn.setOnAction(e -> onRequestRepoConfirm.accept("国内镜像仓库（Gitee）", release.giteeUrl()));
...
if (initialUpdateResult != null) {
    renderUpdate.accept(initialUpdateResult);
}
```

**问题分析**：
将仓库入口与检查结果强绑定会使用户在不触发网络请求时无法直接看到 Gitee 镜像入口；缓存结果直接回填卡片也会令临时更新提示常驻不消。

**建议修改**：
在「关于」卡片常驻展示直达最新发行版的 Gitee（`https://gitee.com/huang3337/MC-World-Explorer/releases/latest`）与 GitHub（`https://github.com/huang3337/MC-World-Explorer/releases/latest`）超链接（点击均经保留数据二次确认卡片）；将 `aboutUpdateBox` 设为默认隐藏的预留空位，仅在当次打开界面执行「检查更新」发现新版本时展开醒目高亮提示（`.update-alert-box`）指引前往下方常驻链接自行下载新版 `.zip`，关闭卡片后重置消失、再次检查更新重新出现。

**影响范围**：
主界面「关于」模态卡片的发布页跳转与手动检查更新反馈体验。

- **解决日期**：2026-11-26
- **实际修改**：重构 `AboutDialog.createAboutCard` 常驻 `aboutReleaseLinksBox` 双仓库 `/releases/latest` 超链接，新增 `.update-alert-box` 醒目提示框样式并移除跨次打开的初始结果预填。
- **验证证据**：`ModalOverlayContractTest.aboutCardKeepsLatestReleaseLinksPermanentAndResetsUpdateBannerAcrossReopen` 验证常驻链接、首次检查展开、关闭重开消失与再次检查重现全部通过。
