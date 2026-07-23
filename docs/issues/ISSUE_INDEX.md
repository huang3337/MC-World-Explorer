# Issue 编号对照清单

- **更新日期**：2026-07-24
- **当前问题总数**：60
- **当前编号前缀数**：16

本文件用于快速确定新问题应使用的编号。问题详情仍以 `docs/issues/` 和 `docs/issues/resolved/` 中的逐文件审查记录为准。

## 编号清单

| 源文件 | 编号前缀 | 已使用编号 | 最后使用 | 下一编号 |
|---|---|---:|---:|---:|
| `App.java` | `APP` | `001-005` | `ISSUE-APP-005` | `ISSUE-APP-006` |
| `build.gradle` | `BUILD` | `001` | `ISSUE-BUILD-001` | `ISSUE-BUILD-002` |
| `PreviewCache.java` | `CACHE` | `001-002` | `ISSUE-CACHE-002` | `ISSUE-CACHE-003` |
| `MainController.java` | `CONTROLLER` | `001-014` | `ISSUE-CONTROLLER-014` | `ISSUE-CONTROLLER-015` |
| `WorldDimensionDiscovery.java` | `DISCOVERY` | `001-002` | `ISSUE-DISCOVERY-002` | `ISSUE-DISCOVERY-003` |
| `main.fxml` | `FXML` | `001-004` | `ISSUE-FXML-004` | `ISSUE-FXML-005` |
| `PreviewGenerator.java` | `GENERATOR` | `001-002` | `ISSUE-GENERATOR-002` | `ISSUE-GENERATOR-003` |
| `DimensionHeightResolver.java` | `HEIGHT` | `001-002` | `ISSUE-HEIGHT-002` | `ISSUE-HEIGHT-003` |
| `PreviewLayer.java` | `LAYER` | `001` | `ISSUE-LAYER-001` | `ISSUE-LAYER-002` |
| `LevelDatReader.java` | `LEVELDAT` | `001-006` | `ISSUE-LEVELDAT-006` | `ISSUE-LEVELDAT-007` |
| `WorldListCell.java` | `LISTCELL` | `001-003` | `ISSUE-LISTCELL-003` | `ISSUE-LISTCELL-004` |
| `PortablePaths.java` | `PORTABLE` | `001` | `ISSUE-PORTABLE-001` | `ISSUE-PORTABLE-002` |
| `PreviewRequestResolver.java` | `REQUEST` | `001-002` | `ISSUE-REQUEST-002` | `ISSUE-REQUEST-003` |
| `WorldScanner.java` | `SCANNER` | `001-006` | `ISSUE-SCANNER-006` | `ISSUE-SCANNER-007` |
| `WorldTreeCell.java` | `TREECELL` | `001-004` | `ISSUE-TREECELL-004` | `ISSUE-TREECELL-005` |
| `WorldInfo.java` | `WORLDINFO` | `001-005` | `ISSUE-WORLDINFO-005` | `ISSUE-WORLDINFO-006` |

## 使用规则

1. 新增问题前，先按源文件查找对应前缀，使用“下一编号”。
2. 编号按前缀独立递增，不使用全项目统一流水号。
3. 已归档、已忽略或以后删除的编号均不得回收。
4. 即使历史编号存在缺口，也使用历史最大编号加一，不填补缺号。
5. 新增问题时必须在同一次文档修改中更新本清单的已使用编号、最后使用、下一编号、问题总数和更新日期。
6. 新源文件尚无前缀时，使用简短、唯一、可识别的英文大写前缀，并从 `001` 开始。
7. 如果清单与实际记录不一致，以扫描全部问题记录得到的历史最大编号为准，并立即修正清单。

## 归档说明

问题从 `docs/issues/` 移入 `docs/issues/resolved/Vx.x/` 时，编号和本清单中的下一编号都不改变。归档只改变问题状态和存放位置，不改变编号历史。
