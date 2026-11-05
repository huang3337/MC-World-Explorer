# Issue 编号登记与记录索引

更新：2026-11-05。按源文件名排序；同一前缀内编号递增，0.7.4新增条目已归入对应位置。

## 对齐结果

- 141个前缀，224个已登记编号；133个有现存问题正文或最终审查条目，91个正文缺失，单列在下方登记区。
- 恢复当前工作区索引漏掉的11项V0.5登记。这11项在Java主线已提交的索引和正文中都存在，并非历史提交没有登记；原问题及历史文档未改写。
- 全部前缀的已登记序列连续，无重复分配；最后使用与下一编号按最大已用编号核对。未使用的前缀仍从001开始。
- **编号连续不等于记录完整。**缺失编号仍占用，不删除、不重排、不回退最大值，也不补写推断性修复结论。

0.7.4现存8项收尾修复见[最终审查](resolved/V0.7.4/REVIEW_SUMMARY.md)。历史复核文档引用同一编号，不作为重复分配；预登记但未使用的前缀保留，避免命名冲突。

## 历史核对与保留意义

2026-11-05只读检查本地所有分支、标签、远端跟踪引用及reflog可达历史：49个提交、685个Markdown/TXT文本对象；仓库不是浅克隆。未执行远端fetch，结论限于本地已有历史。基准为Java主线 `09d2c7271fba8fc5759161c723243979797df8cc`。

- 该基准索引的125个已用编号均有当前正文；现存0.7.4收尾汇总另有8项，合计133项。
- 下方91个编号在该Java提交中都尚未分配，也未在所查历史中找到相符的Java问题正文；Java主线 `docs/issues/` 未发现提交层面的文件删除记录。因此没有证据认定这些正文是被某次已提交的Java开发误删。
- 一些编号仅出现在历史索引的“下一编号”栏，不能当成已用编号或问题记录。`ISSUE-CHUNKDEC-001` 虽有历史正文，但属于Tauri分支提交 `6a82fee` 的 `crates/mcwe-core/src/chunk/decoder.rs`，不是当前Java `ChunkNbtDecoder.java`；不得跨分支错配。此同号关系保留说明，不回滚任一侧编号。
- 当前工作区曾进行审查精简及删除，部分正文可能在未提交阶段丢失；不将91项全部断言为同一次删除、无效探索或登记错误。按用户要求停止恢复原文，本次只核对历史并保留缺失状态。

这些编号的保留价值是防止重用、维持旧引用和分配顺序；它们不能证明修复已完成、功能受支持或测试已通过。不为缺失项创建空白问题文档。以后只有取得可靠原文或重新核实的证据，才更新其状态，并注明证据时点。

本地核对明细：`build/index-history-check-20261105/history-audit.json`（不上传）。

## 编号与正文

| 源文件 | 编号前缀 | 已使用编号 | 最后使用 | 下一编号 | 记录对齐 |
|---|---|---|---|---|---|
| `AboutDialog.java` | `ABOUTDIALOG` | — | — | `ISSUE-ABOUTDIALOG-001` | 尚未登记问题 |
| `App.java` | `APP` | 001,002,003,004,005 | `ISSUE-APP-005` | `ISSUE-APP-006` | [V0.1.1：001–005](resolved/V0.1.1/App.java.md) |
| `BlockClassifier.java` | `V05-CLASS` | 001 | `ISSUE-V05-CLASS-001` | `ISSUE-V05-CLASS-002` | [V0.5：001](resolved/V0.5/BlockClassifier.java.md) |
| `BlockModelResolver.java` | `BMODEL` | 001,002,003,004,005,006,007,008,009,010,011,012,013,014,015,016,017,018,019,020,021,022,023,024,025,026,027,028,029,030,031 | `ISSUE-BMODEL-031` | `ISSUE-BMODEL-032` | [V0.7.2：001–003](resolved/V0.7.2/BlockModelResolver.java.md)；[V0.7.3：004–007](resolved/V0.7.3/BlockModelResolver.java.md)；[正文缺失登记](#缺失正文登记) |
| `BlockModelResolverTest.java` | `BLOCKMODELRESOLVERTEST` | — | — | `ISSUE-BLOCKMODELRESOLVERTEST-001` | 尚未登记问题 |
| `BlockResourceDiagnostic.java` | `BLOCKRESOURCEDIAGNOSTIC` | — | — | `ISSUE-BLOCKRESOURCEDIAGNOSTIC-001` | 尚未登记问题 |
| `BlockResourceDiagnosticTest.java` | `BLOCKRESOURCEDIAGNOSTICTEST` | — | — | `ISSUE-BLOCKRESOURCEDIAGNOSTICTEST-001` | 尚未登记问题 |
| `BlockResourceSource.java` | `BLOCKRESOURCESOURCE` | — | — | `ISSUE-BLOCKRESOURCESOURCE-001` | 尚未登记问题 |
| `BlockTextureSet.java` | `TEXTSET` | 001,002,003,004,005,006,007,008,009 | `ISSUE-TEXTSET-009` | `ISSUE-TEXTSET-010` | [V0.7.2：001–003](resolved/V0.7.2/BlockTextureSet.java.md)；[V0.7.3：004–005](resolved/V0.7.3/BlockTextureSet.java.md)；[正文缺失登记](#缺失正文登记) |
| `BlockTintPreparation.java` | `TINTPREP` | 001 | `ISSUE-TINTPREP-001` | `ISSUE-TINTPREP-002` | [正文缺失登记](#缺失正文登记) |
| `BlockTintResolver.java` | `TINTRES` | 001 | `ISSUE-TINTRES-001` | `ISSUE-TINTRES-002` | [正文缺失登记](#缺失正文登记) |
| `BuiltinBlockModels.java` | `BUILTIN` | 001,002 | `ISSUE-BUILTIN-002` | `ISSUE-BUILTIN-003` | [V0.7.2：001–002](resolved/V0.7.2/BuiltinBlockModels.java.md) |
| `BuiltinModelRules.java` | `BIRULE` | 001,002,003,004,005 | `ISSUE-BIRULE-005` | `ISSUE-BIRULE-006` | [正文缺失登记](#缺失正文登记) |
| `BuiltinResourcePacks.java` | `BIPACK` | 001,002 | `ISSUE-BIPACK-002` | `ISSUE-BIPACK-003` | [正文缺失登记](#缺失正文登记) |
| `BuiltinResourcePacksCacheTest.java` | `BIPACKTEST` | — | — | `ISSUE-BIPACKTEST-001` | 尚未登记问题 |
| `ChunkMeshSnapshot.java` | `CHUNKSNAP` | — | — | `ISSUE-CHUNKSNAP-001` | 尚未登记问题 |
| `ChunkMesher.java` | `V05-MESH` | 001 | `ISSUE-V05-MESH-001` | `ISSUE-V05-MESH-002` | [V0.5：001](resolved/V0.5/ChunkMesher.java.md) |
| `ChunkNbtDecoder.java` | `CHUNKDEC` | 001 | `ISSUE-CHUNKDEC-001` | `ISSUE-CHUNKDEC-002` | [正文缺失登记](#缺失正文登记) |
| `ClientBlockColors.java` | `CLRCOL` | 001 | `ISSUE-CLRCOL-001` | `ISSUE-CLRCOL-002` | [正文缺失登记](#缺失正文登记) |
| `ClientBlockRegistration.java` | `CBLKREG` | 001,002,003 | `ISSUE-CBLKREG-003` | `ISSUE-CBLKREG-004` | [正文缺失登记](#缺失正文登记) |
| `ClientResourceArchive.java` | `CLIENTRES` | 001 | `ISSUE-CLIENTRES-001` | `ISSUE-CLIENTRES-002` | [正文缺失登记](#缺失正文登记) |
| `ClientResourceArchiveTest.java` | `CLIENTRESOURCEARCHIVETEST` | — | — | `ISSUE-CLIENTRESOURCEARCHIVETEST-001` | 尚未登记问题 |
| `CoreIsolationTest.java` | `COREISOLATIONTEST` | — | — | `ISSUE-COREISOLATIONTEST-001` | 尚未登记问题 |
| `DimensionHeightResolver.java` | `HEIGHT` | 001,002 | `ISSUE-HEIGHT-002` | `ISSUE-HEIGHT-003` | [V0.2.1：001–002](resolved/V0.2.1/DimensionHeightResolver.java.md) |
| `ExternalModelPlugin.java` | `EXTERNALMODELPLUGIN` | 001 | `ISSUE-EXTERNALMODELPLUGIN-001` | `ISSUE-EXTERNALMODELPLUGIN-002` | [V0.7.4：001](resolved/V0.7.4/REVIEW_SUMMARY.md) |
| `ExternalModelPluginPackage.java` | `EXTERNALMODELPLUGINPACKAGE` | — | — | `ISSUE-EXTERNALMODELPLUGINPACKAGE-001` | 尚未登记问题 |
| `ExternalModelPluginPackageTest.java` | `EXTERNALMODELPLUGINPACKAGETEST` | — | — | `ISSUE-EXTERNALMODELPLUGINPACKAGETEST-001` | 尚未登记问题 |
| `ExternalModelPluginTest.java` | `EXTERNALMODELPLUGINTEST` | — | — | `ISSUE-EXTERNALMODELPLUGINTEST-001` | 尚未登记问题 |
| `FrameTimeRecorder.java` | `FRAMETIME` | 001 | `ISSUE-FRAMETIME-001` | `ISSUE-FRAMETIME-002` | [V0.4：001](resolved/V0.4/FrameTimeRecorder.java.md) |
| `FxmlContractTest.java` | `FXMLCONTRACTTEST` | — | — | `ISSUE-FXMLCONTRACTTEST-001` | 尚未登记问题 |
| `GameModelDefinitionContractTest.java` | `GAMEMODELDEFINITIONCONTRACTTEST` | — | — | `ISSUE-GAMEMODELDEFINITIONCONTRACTTEST-001` | 尚未登记问题 |
| `GeometryTransform.java` | `GEOMETRYTRANSFORM` | — | — | `ISSUE-GEOMETRYTRANSFORM-001` | 尚未登记问题 |
| `GlfwViewerOrbitController.java` | `GLFWNAV` | — | — | `ISSUE-GLFWNAV-001` | 尚未登记问题 |
| `InstanceResourceLocator.java` | `IRLOC` | 001,002,003,004 | `ISSUE-IRLOC-004` | `ISSUE-IRLOC-005` | [V0.7.3：001](resolved/V0.7.3/InstanceResourceLocator.java.md)；[正文缺失登记](#缺失正文登记) |
| `InstanceResourceSource.java` | `IRSRC` | 001,002,003,004,005,006,007,008,009,010,011,012,013,014,015 | `ISSUE-IRSRC-015` | `ISSUE-IRSRC-016` | [V0.7.3：001](resolved/V0.7.3/InstanceResourceSource.java.md)；[正文缺失登记](#缺失正文登记) |
| `InstanceResourceSourceTest.java` | `IRSRC_TEST` | — | — | `ISSUE-IRSRC_TEST-001` | 尚未登记问题 |
| `JavaFxCameraTransform.java` | `JFXCAM` | 001 | `ISSUE-JFXCAM-001` | `ISSUE-JFXCAM-002` | [V0.4：001](resolved/V0.4/JavaFxCameraTransform.java.md) |
| `JavaFxOrbitController.java` | `JFXORBIT` | 001 | `ISSUE-JFXORBIT-001` | `ISSUE-JFXORBIT-002` | [V0.4：001](resolved/V0.4/JavaFxOrbitController.java.md) |
| `LauncherResourceLibraries.java` | `LAUNCHLIB` | 001,002 | `ISSUE-LAUNCHLIB-002` | `ISSUE-LAUNCHLIB-003` | [正文缺失登记](#缺失正文登记) |
| `LevelDatReader.java` | `LEVELDAT` | 001,002,003,004,005,006 | `ISSUE-LEVELDAT-006` | `ISSUE-LEVELDAT-007` | [V0.1.1：001–006](resolved/V0.1.1/LevelDatReader.java.md) |
| `LwjglV04Renderer.java` | `LWJGLRENDER` | 001 | `ISSUE-LWJGLRENDER-001` | `ISSUE-LWJGLRENDER-002` | [V0.4：001](resolved/V0.4/LwjglV04Renderer.java.md) |
| `LwjglV05Launcher.java` | `V05-MEM` | 001 | `ISSUE-V05-MEM-001` | `ISSUE-V05-MEM-002` | [V0.5：001](resolved/V0.5/LwjglV05Launcher.java.md) |
| `LwjglViewerWindow.java` | `LWJWIN` | — | — | `ISSUE-LWJWIN-001` | 尚未登记问题 |
| `MainController.java` | `CONTROLLER` | 001,002,003,004,005,006,007,008,009,010,011,012,013,014,015,016 | `ISSUE-CONTROLLER-016` | `ISSUE-CONTROLLER-017` | [V0.1.1：001–009](resolved/V0.1.1/MainController.java.md)；[V0.2：010–011](resolved/V0.2/MainController.java.md)；[V0.2.1：012–014](resolved/V0.2.1/MainController.java.md)；[V0.7：015–016](resolved/V0.7/MainController.java.md) |
| `MapTileGenerator.java` | `MAPGEN` | 001 | `ISSUE-MAPGEN-001` | `ISSUE-MAPGEN-002` | [V0.3：001](resolved/V0.3/MapTileGenerator.java.md) |
| `MapTileScheduler.java` | `MAPSCHED` | 001,002 | `ISSUE-MAPSCHED-002` | `ISSUE-MAPSCHED-003` | [V0.3：001–002](resolved/V0.3/MapTileScheduler.java.md) |
| `MapTileService.java` | `MAPSERVICE` | 001 | `ISSUE-MAPSERVICE-001` | `ISSUE-MAPSERVICE-002` | [V0.3：001](resolved/V0.3/MapTileService.java.md) |
| `MapViewerController.java` | `MAPVIEW` | 001,002,003,004,005,006,007,008 | `ISSUE-MAPVIEW-008` | `ISSUE-MAPVIEW-009` | [V0.3：001–002](resolved/V0.3/MapViewerController.java.md)；[V0.3.1：003](resolved/V0.3.1/MapViewerController.java.md)；[V0.7.1：004](resolved/V0.7.1/MapViewerController.java.md)；[V0.7.3：005](resolved/V0.7.3/MapViewerController.java.md)；[V0.7.4：008](resolved/V0.7.4/REVIEW_SUMMARY.md)；[正文缺失登记](#缺失正文登记) |
| `MapViewport.java` | `VIEWPORT` | 001,002,003 | `ISSUE-VIEWPORT-003` | `ISSUE-VIEWPORT-004` | [V0.3：001–002](resolved/V0.3/MapViewport.java.md)；[V0.7.4：003](resolved/V0.7.4/REVIEW_SUMMARY.md) |
| `MeshBatch.java` | `V05-BATCH` | 001 | `ISSUE-V05-BATCH-001` | `ISSUE-V05-BATCH-002` | [V0.5：001](resolved/V0.5/MeshBatch.java.md) |
| `MeshWarning.java` | `MESHWARNING` | — | — | `ISSUE-MESHWARNING-001` | 尚未登记问题 |
| `ModelParts.java` | `MPARTS` | 001 | `ISSUE-MPARTS-001` | `ISSUE-MPARTS-002` | [正文缺失登记](#缺失正文登记) |
| `ModelPlugin.java` | `MODELPLUGIN` | — | — | `ISSUE-MODELPLUGIN-001` | 尚未登记问题 |
| `ModelPluginCatalog.java` | `MODELPLUGINCATALOG` | — | — | `ISSUE-MODELPLUGINCATALOG-001` | 尚未登记问题 |
| `ModelPluginLibraryController.java` | `MODELPLUGINLIBRARYCONTROLLER` | 001 | `ISSUE-MODELPLUGINLIBRARYCONTROLLER-001` | `ISSUE-MODELPLUGINLIBRARYCONTROLLER-002` | [V0.7.4：001](resolved/V0.7.4/REVIEW_SUMMARY.md) |
| `ModelPluginLibraryShutdownTest.java` | `MODELPLUGINLIBRARYSHUTDOWNTEST` | — | — | `ISSUE-MODELPLUGINLIBRARYSHUTDOWNTEST-001` | 尚未登记问题 |
| `ModelPluginManifest.java` | `MODELPLUGINMANIFEST` | — | — | `ISSUE-MODELPLUGINMANIFEST-001` | 尚未登记问题 |
| `ModelPluginManifestTest.java` | `MODELPLUGINMANIFESTTEST` | — | — | `ISSUE-MODELPLUGINMANIFESTTEST-001` | 尚未登记问题 |
| `ModelPluginPurityTest.java` | `MODELPLUGINPURITYTEST` | — | — | `ISSUE-MODELPLUGINPURITYTEST-001` | 尚未登记问题 |
| `ModelPluginRuleInfo.java` | `MODELPLUGINRULEINFO` | — | — | `ISSUE-MODELPLUGINRULEINFO-001` | 尚未登记问题 |
| `ModelPluginStore.java` | `MODELPLUGINSTORE` | — | — | `ISSUE-MODELPLUGINSTORE-001` | 尚未登记问题 |
| `ModelPluginStoreTest.java` | `MODELPLUGINSTORETEST` | — | — | `ISSUE-MODELPLUGINSTORETEST-001` | 尚未登记问题 |
| `ModelPluginWire.java` | `MODELPLUGINWIRE` | — | — | `ISSUE-MODELPLUGINWIRE-001` | 尚未登记问题 |
| `ModelPluginWireTest.java` | `MODELPLUGINWIRETEST` | — | — | `ISSUE-MODELPLUGINWIRETEST-001` | 尚未登记问题 |
| `ModelPluginWorker.java` | `MODELPLUGINWORKER` | — | — | `ISSUE-MODELPLUGINWORKER-001` | 尚未登记问题 |
| `ModelRule.java` | `MODELRULE` | — | — | `ISSUE-MODELRULE-001` | 尚未登记问题 |
| `ModelRuleRegistry.java` | `MRREG` | 001,002 | `ISSUE-MRREG-002` | `ISSUE-MRREG-003` | [正文缺失登记](#缺失正文登记) |
| `NestedResourcePacks.java` | `NESTPACK` | 001,002 | `ISSUE-NESTPACK-002` | `ISSUE-NESTPACK-003` | [正文缺失登记](#缺失正文登记) |
| `ObjModelReader.java` | `OBJREAD` | 001,002 | `ISSUE-OBJREAD-002` | `ISSUE-OBJREAD-003` | [V0.7.4：002](resolved/V0.7.4/REVIEW_SUMMARY.md)；[正文缺失登记](#缺失正文登记) |
| `ObjUvLockProtocolTest.java` | `OBJUVLOCKPROTOCOLTEST` | — | — | `ISSUE-OBJUVLOCKPROTOCOLTEST-001` | 尚未登记问题 |
| `OpenGlMeshResources.java` | `GLMESH` | 001 | `ISSUE-GLMESH-001` | `ISSUE-GLMESH-002` | [V0.4：001](resolved/V0.4/OpenGlMeshResources.java.md) |
| `PluginGuideDialog.java` | `PLUGINGUIDEDIALOG` | — | — | `ISSUE-PLUGINGUIDEDIALOG-001` | 尚未登记问题 |
| `PortablePaths.java` | `PORTABLE` | 001 | `ISSUE-PORTABLE-001` | `ISSUE-PORTABLE-002` | [V0.2.1：001](resolved/V0.2.1/PortablePaths.java.md) |
| `PortableSettings.java` | `PORTABLESETTINGS` | — | — | `ISSUE-PORTABLESETTINGS-001` | 尚未登记问题 |
| `PortableSettingsTest.java` | `PORTABLESETTINGSTEST` | — | — | `ISSUE-PORTABLESETTINGSTEST-001` | 尚未登记问题 |
| `PreviewCache.java` | `CACHE` | 001,002 | `ISSUE-CACHE-002` | `ISSUE-CACHE-003` | [V0.2.1：001–002](resolved/V0.2.1/PreviewCache.java.md) |
| `PreviewGenerator.java` | `GENERATOR` | 001,002 | `ISSUE-GENERATOR-002` | `ISSUE-GENERATOR-003` | [V0.2.1：001–002](resolved/V0.2.1/PreviewGenerator.java.md) |
| `PreviewLayer.java` | `LAYER` | 001 | `ISSUE-LAYER-001` | `ISSUE-LAYER-002` | [V0.2.1：001](resolved/V0.2.1/PreviewLayer.java.md) |
| `PreviewRequestResolver.java` | `REQUEST` | 001,002 | `ISSUE-REQUEST-002` | `ISSUE-REQUEST-003` | [V0.2.1：001–002](resolved/V0.2.1/PreviewRequestResolver.java.md) |
| `RegionFileReader.java` | `REGIONREAD` | 001,002 | `ISSUE-REGIONREAD-002` | `ISSUE-REGIONREAD-003` | [正文缺失登记](#缺失正文登记) |
| `RegistrationClassFile.java` | `REGCLASS` | 001 | `ISSUE-REGCLASS-001` | `ISSUE-REGCLASS-002` | [正文缺失登记](#缺失正文登记) |
| `RenderLayer.java` | `V05-LAYER` | 001 | `ISSUE-V05-LAYER-001` | `ISSUE-V05-LAYER-002` | [V0.5：001](resolved/V0.5/RenderLayer.java.md) |
| `ResourceDiagnosticReport.java` | `RESOURCEDIAGNOSTICREPORT` | — | — | `ISSUE-RESOURCEDIAGNOSTICREPORT-001` | 尚未登记问题 |
| `ResourceDiagnosticReportTest.java` | `RESOURCEDIAGNOSTICREPORTTEST` | — | — | `ISSUE-RESOURCEDIAGNOSTICREPORTTEST-001` | 尚未登记问题 |
| `ResourceDiagnosticsPane.java` | `RESOURCEDIAGNOSTICSPANE` | — | — | `ISSUE-RESOURCEDIAGNOSTICSPANE-001` | 尚未登记问题 |
| `ResourceSettingsPane.java` | `RSET` | 001 | `ISSUE-RSET-001` | `ISSUE-RSET-002` | [正文缺失登记](#缺失正文登记) |
| `RootModelTransform.java` | `ROOTTRANS` | 001 | `ISSUE-ROOTTRANS-001` | `ISSUE-ROOTTRANS-002` | [正文缺失登记](#缺失正文登记) |
| `RootModelTransformTest.java` | `ROOTMODELTRANSFORMTEST` | — | — | `ISSUE-ROOTMODELTRANSFORMTEST-001` | 尚未登记问题 |
| `SavedBiomeColors.java` | `SBCOLOR` | 001 | `ISSUE-SBCOLOR-001` | `ISSUE-SBCOLOR-002` | [正文缺失登记](#缺失正文登记) |
| `SavedTargetIntegrationTest.java` | `SAVTARGETTEST` | 001 | `ISSUE-SAVTARGETTEST-001` | `ISSUE-SAVTARGETTEST-002` | [正文缺失登记](#缺失正文登记) |
| `ShaderProgram.java` | `SHADER` | 001 | `ISSUE-SHADER-001` | `ISSUE-SHADER-002` | [V0.4：001](resolved/V0.4/ShaderProgram.java.md) |
| `StandardBlockTintTest.java` | `TINTSTDTEST` | — | — | `ISSUE-TINTSTDTEST-001` | 尚未登记问题 |
| `StandardTintBoundaryTest.java` | `TINTBNDTEST` | — | — | `ISSUE-TINTBNDTEST-001` | 尚未登记问题 |
| `TextureAtlas.java` | `ATLAS` | 001,002 | `ISSUE-ATLAS-002` | `ISSUE-ATLAS-003` | [V0.7.3：001](resolved/V0.7.3/TextureAtlas.java.md)；[正文缺失登记](#缺失正文登记) |
| `UnboundModelTintTest.java` | `UTINT` | — | — | `ISSUE-UTINT-001` | 尚未登记问题 |
| `UnboundTintResourceIntegrationTest.java` | `UTINTR` | — | — | `ISSUE-UTINTR-001` | 尚未登记问题 |
| `UvTiling.java` | `UVTILE` | 001 | `ISSUE-UVTILE-001` | `ISSUE-UVTILE-002` | [V0.7.3：001](resolved/V0.7.3/UvTiling.java.md) |
| `V04Arguments.java` | `V04ARGS` | 001,002 | `ISSUE-V04ARGS-002` | `ISSUE-V04ARGS-003` | [V0.4：001–002](resolved/V0.4/V04Arguments.java.md) |
| `V05ReportWriter.java` | `V05-REPORT` | 001 | `ISSUE-V05-REPORT-001` | `ISSUE-V05-REPORT-002` | [V0.5：001](resolved/V0.5/V05ReportWriter.java.md) |
| `V05ValidationPipeline.java` | `V05-PIPE` | 001 | `ISSUE-V05-PIPE-001` | `ISSUE-V05-PIPE-002` | [V0.5：001](resolved/V0.5/V05ValidationPipeline.java.md) |
| `ViewerAreaList.java` | `VAREALIST` | 001 | `ISSUE-VAREALIST-001` | `ISSUE-VAREALIST-002` | [V0.7：001](resolved/V0.7/ViewerAreaList.java.md) |
| `ViewerAreaThumbnail.java` | `VTHUMB` | 001 | `ISSUE-VTHUMB-001` | `ISSUE-VTHUMB-002` | [V0.7：001](resolved/V0.7/ViewerAreaThumbnail.java.md) |
| `ViewerCameraMatrices.java` | `VCMAT` | — | — | `ISSUE-VCMAT-001` | 尚未登记问题 |
| `ViewerFreeCameraState.java` | `FRECAM` | — | — | `ISSUE-FRECAM-001` | 尚未登记问题 |
| `ViewerFreeCameraStateTest.java` | `FCTEST` | — | — | `ISSUE-FCTEST-001` | 尚未登记问题 |
| `ViewerLoadedArea.java` | `LOADAREA` | — | — | `ISSUE-LOADAREA-001` | 尚未登记问题 |
| `ViewerLoadedAreaTest.java` | `LATEST` | — | — | `ISSUE-LATEST-001` | 尚未登记问题 |
| `ViewerNavigationOverlay.java` | `NAVHUD` | 001 | `ISSUE-NAVHUD-001` | `ISSUE-NAVHUD-002` | [正文缺失登记](#缺失正文登记) |
| `ViewerNavigationOverlayIntegrationTest.java` | `NAVGLTEST` | — | — | `ISSUE-NAVGLTEST-001` | 尚未登记问题 |
| `ViewerNavigationState.java` | `NAVSTATE` | — | — | `ISSUE-NAVSTATE-001` | 尚未登记问题 |
| `ViewerNavigationStateTest.java` | `NAVTEST` | 001 | `ISSUE-NAVTEST-001` | `ISSUE-NAVTEST-002` | [正文缺失登记](#缺失正文登记) |
| `ViewerSceneCache.java` | `SCENECACHE` | — | — | `ISSUE-SCENECACHE-001` | 尚未登记问题 |
| `ViewerTargetOutline.java` | `TARGETLINE` | 001,002 | `ISSUE-TARGETLINE-002` | `ISSUE-TARGETLINE-003` | [正文缺失登记](#缺失正文登记) |
| `ViewerTargetOutlineIntegrationTest.java` | `TARGETLINEGL` | — | — | `ISSUE-TARGETLINEGL-001` | 尚未登记问题 |
| `ViewerTargetOutlineTest.java` | `TARGETLINETEST` | — | — | `ISSUE-TARGETLINETEST-001` | 尚未登记问题 |
| `ViewerWindow.java` | `VIEWERWINDOW` | — | — | `ISSUE-VIEWERWINDOW-001` | 尚未登记问题 |
| `VoxelAreaLoader.java` | `V05-LOAD` | 001 | `ISSUE-V05-LOAD-001` | `ISSUE-V05-LOAD-002` | [V0.5：001](resolved/V0.5/VoxelAreaLoader.java.md) |
| `VoxelChunk.java` | `VOXCHUNK` | 001 | `ISSUE-VOXCHUNK-001` | `ISSUE-VOXCHUNK-002` | [V0.4：001](resolved/V0.4/VoxelChunk.java.md) |
| `VoxelChunkParser.java` | `VOXPARSE` | 001,002,003 | `ISSUE-VOXPARSE-003` | `ISSUE-VOXPARSE-004` | [V0.4：001–003](resolved/V0.4/VoxelChunkParser.java.md) |
| `VoxelMesher.java` | `VOXMESH` | 001 | `ISSUE-VOXMESH-001` | `ISSUE-VOXMESH-002` | [V0.4：001](resolved/V0.4/VoxelMesher.java.md) |
| `VoxelPreviewCoordinator.java` | `VCOORD` | 001,002,003,004 | `ISSUE-VCOORD-004` | `ISSUE-VCOORD-005` | [V0.7：001](resolved/V0.7/VoxelPreviewCoordinator.java.md)；[V0.7.3：002](resolved/V0.7.3/VoxelPreviewCoordinator.java.md)；[正文缺失登记](#缺失正文登记) |
| `VoxelPreviewCoordinatorTest.java` | `VOXELPREVIEWCOORDINATORTEST` | — | — | `ISSUE-VOXELPREVIEWCOORDINATORTEST-001` | 尚未登记问题 |
| `VoxelSceneBuilder.java` | `V05-SCENE` | 001 | `ISSUE-V05-SCENE-001` | `ISSUE-V05-SCENE-002` | [V0.5：001](resolved/V0.5/VoxelSceneBuilder.java.md) |
| `VoxelSceneSnapshot.java` | `SCENESNAP` | — | — | `ISSUE-SCENESNAP-001` | 尚未登记问题 |
| `VoxelTargetIndex.java` | `TARGETIDX` | 001,002,003,004 | `ISSUE-TARGETIDX-004` | `ISSUE-TARGETIDX-005` | [V0.7.4：004](resolved/V0.7.4/REVIEW_SUMMARY.md)；[正文缺失登记](#缺失正文登记) |
| `VoxelTargetIndexTest.java` | `TARGETTEST` | — | — | `ISSUE-TARGETTEST-001` | 尚未登记问题 |
| `WorkspaceLayoutTest.java` | `WORKSPACETEST` | 001 | `ISSUE-WORKSPACETEST-001` | `ISSUE-WORKSPACETEST-002` | [V0.7.1：001](resolved/V0.7.1/WorkspaceLayoutTest.java.md) |
| `WorldDataPacksTest.java` | `WORLDDATAPACKSTEST` | — | — | `ISSUE-WORLDDATAPACKSTEST-001` | 尚未登记问题 |
| `WorldDimensionDiscovery.java` | `DISCOVERY` | 001,002,003 | `ISSUE-DISCOVERY-003` | `ISSUE-DISCOVERY-004` | [V0.2.1：001–002](resolved/V0.2.1/WorldDimensionDiscovery.java.md)；[正文缺失登记](#缺失正文登记) |
| `WorldInfo.java` | `WORLDINFO` | 001,002,003,004,005 | `ISSUE-WORLDINFO-005` | `ISSUE-WORLDINFO-006` | [V0.1.1：001–005](resolved/V0.1.1/WorldInfo.java.md) |
| `WorldListCell.java` | `LISTCELL` | 001,002,003 | `ISSUE-LISTCELL-003` | `ISSUE-LISTCELL-004` | [V0.1.1：001–003](resolved/V0.1.1/WorldListCell.java.md) |
| `WorldScanner.java` | `SCANNER` | 001,002,003,004,005,006 | `ISSUE-SCANNER-006` | `ISSUE-SCANNER-007` | [V0.1.1：001–006](resolved/V0.1.1/WorldScanner.java.md) |
| `WorldTreeCell.java` | `TREECELL` | 001,002,003,004 | `ISSUE-TREECELL-004` | `ISSUE-TREECELL-005` | [V0.1.1：001–004](resolved/V0.1.1/WorldTreeCell.java.md) |
| `build-v04-trials.ps1` | `V04PACKAGE` | 001 | `ISSUE-V04PACKAGE-001` | `ISSUE-V04PACKAGE-002` | [V0.4：001](resolved/V0.4/build-v04-trials.ps1.md) |
| `build.gradle` | `BUILD` | 001,002,003 | `ISSUE-BUILD-003` | `ISSUE-BUILD-004` | [V0.2.1：001](resolved/V0.2.1/build.gradle.md)；[正文缺失登记](#缺失正文登记) |
| `main.fxml` | `FXML` | 001,002,003,004,005 | `ISSUE-FXML-005` | `ISSUE-FXML-006` | [V0.1.1：001–004](resolved/V0.1.1/main.fxml.md)；[V0.7：005](resolved/V0.7/main.fxml.md) |
| `map-view.fxml` | `MAPFXML` | 001,002 | `ISSUE-MAPFXML-002` | `ISSUE-MAPFXML-003` | [V0.7：001](resolved/V0.7/map-view.fxml.md)；[V0.7.1：002](resolved/V0.7.1/map-view.fxml.md) |
| `measure-v04.ps1` | `V04MEASURE` | 001 | `ISSUE-V04MEASURE-001` | `ISSUE-V04MEASURE-002` | [V0.4：001](resolved/V0.4/measure-v04.ps1.md) |
| `measure-v05.ps1` | `V05-SCRIPT` | 001,002 | `ISSUE-V05-SCRIPT-002` | `ISSUE-V05-SCRIPT-003` | [V0.5：001–002](resolved/V0.5/measure-v05.ps1.md) |
| `package-windows.ps1` | `PACKAGEWINDOWS` | 001,002 | `ISSUE-PACKAGEWINDOWS-002` | `ISSUE-PACKAGEWINDOWS-003` | [V0.7.4：001–002](resolved/V0.7.4/REVIEW_SUMMARY.md) |
| `styles.css` | `CSS` | 001 | `ISSUE-CSS-001` | `ISSUE-CSS-002` | [正文缺失登记](#缺失正文登记) |

## 缺失正文登记

以下91个已登记编号当前没有对应的Java问题正文。统一状态为“正文缺失，具体原因未逐项证实”；均保留原编号和占用关系。范围写法包含首尾，例如008–031表示008至031的每个编号。

| 登记文件 | 编号前缀 | 缺失编号 | 数量 |
|---|---|---|---:|
| `BlockModelResolver.java` | `ISSUE-BMODEL-` | 008–031 | 24 |
| `BlockTextureSet.java` | `ISSUE-TEXTSET-` | 006–009 | 4 |
| `BlockTintPreparation.java` | `ISSUE-TINTPREP-` | 001 | 1 |
| `BlockTintResolver.java` | `ISSUE-TINTRES-` | 001 | 1 |
| `BuiltinModelRules.java` | `ISSUE-BIRULE-` | 001–005 | 5 |
| `BuiltinResourcePacks.java` | `ISSUE-BIPACK-` | 001–002 | 2 |
| `ChunkNbtDecoder.java` | `ISSUE-CHUNKDEC-` | 001 | 1 |
| `ClientBlockColors.java` | `ISSUE-CLRCOL-` | 001 | 1 |
| `ClientBlockRegistration.java` | `ISSUE-CBLKREG-` | 001–003 | 3 |
| `ClientResourceArchive.java` | `ISSUE-CLIENTRES-` | 001 | 1 |
| `InstanceResourceLocator.java` | `ISSUE-IRLOC-` | 002–004 | 3 |
| `InstanceResourceSource.java` | `ISSUE-IRSRC-` | 002–015 | 14 |
| `LauncherResourceLibraries.java` | `ISSUE-LAUNCHLIB-` | 001–002 | 2 |
| `MapViewerController.java` | `ISSUE-MAPVIEW-` | 006–007 | 2 |
| `ModelParts.java` | `ISSUE-MPARTS-` | 001 | 1 |
| `ModelRuleRegistry.java` | `ISSUE-MRREG-` | 001–002 | 2 |
| `NestedResourcePacks.java` | `ISSUE-NESTPACK-` | 001–002 | 2 |
| `ObjModelReader.java` | `ISSUE-OBJREAD-` | 001 | 1 |
| `RegionFileReader.java` | `ISSUE-REGIONREAD-` | 001–002 | 2 |
| `RegistrationClassFile.java` | `ISSUE-REGCLASS-` | 001 | 1 |
| `ResourceSettingsPane.java` | `ISSUE-RSET-` | 001 | 1 |
| `RootModelTransform.java` | `ISSUE-ROOTTRANS-` | 001 | 1 |
| `SavedBiomeColors.java` | `ISSUE-SBCOLOR-` | 001 | 1 |
| `SavedTargetIntegrationTest.java` | `ISSUE-SAVTARGETTEST-` | 001 | 1 |
| `TextureAtlas.java` | `ISSUE-ATLAS-` | 002 | 1 |
| `ViewerNavigationOverlay.java` | `ISSUE-NAVHUD-` | 001 | 1 |
| `ViewerNavigationStateTest.java` | `ISSUE-NAVTEST-` | 001 | 1 |
| `ViewerTargetOutline.java` | `ISSUE-TARGETLINE-` | 001–002 | 2 |
| `VoxelPreviewCoordinator.java` | `ISSUE-VCOORD-` | 003–004 | 2 |
| `VoxelTargetIndex.java` | `ISSUE-TARGETIDX-` | 001–003 | 3 |
| `WorldDimensionDiscovery.java` | `ISSUE-DISCOVERY-` | 003 | 1 |
| `build.gradle` | `ISSUE-BUILD-` | 002–003 | 2 |
| `styles.css` | `ISSUE-CSS-` | 001 | 1 |

合计91项。编号占用以主表为准；本区说明正文缺失，不另分配或重复计算问题编号。
