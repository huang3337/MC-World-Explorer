# 三维诊断报告

## ISSUE-MAPVIEW-009 纯文本报告的测试通过反射调用闲置控制器方法

2026-11-19 · 低 · 代码质量 · 已修复。

[MapViewerController.java](../../../../src/main/java/com/mcworldexplorer/ui/MapViewerController.java) 原第 1116 行的私有 `resourceDiagnostics(result)` 仅转发到 `ResourceDiagnosticReport.from(result).toText()`。产品的复制入口直接使用报告，唯一调用者是受 JavaFX 开关限制的反射测试。

移除控制器转发，将原来关于选区范围、资源来源、告警和静态几何的断言移入 `ResourceDiagnosticReportTest`。该用例已在默认报告测试中执行通过。
