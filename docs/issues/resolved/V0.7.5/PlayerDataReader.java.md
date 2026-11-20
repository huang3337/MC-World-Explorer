# 玩家存档目录读取

## ISSUE-PLYDATA-001 新布局的玩家标记缺失

2026-11-19 确认 1 项中等严重程度的解析问题，已在 0.7.5 工作区修复并通过测试。

[PlayerDataReader.java](../../../../src/main/java/com/mcworldexplorer/nbt/PlayerDataReader.java) 原第 53 行使用 `normalizedWorld.resolve("playerdata")`。26.3 样本将已有位置与维度的玩家记录保存在 `players/data/<uuid>.dat`。此时二维地图的玩家列表及标记为空。

读取器按目录布局选择当前输入，仅枚举一个目录。现有的 UUID 校验、文件读取上限和排序继续适用。真实世界的可读玩家数量从 0 恢复为 1。自动回归覆盖仅有旧目录，以及迁移后新旧目录同时存在且位置冲突的情况。
