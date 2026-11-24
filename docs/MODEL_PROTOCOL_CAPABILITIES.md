# 资源解析范围

本体提供插件安装、启停、卸载和隔离执行，默认零预装插件。通用解析的准入与失败语义见 [DECISION-024](decisions/DECISION-024.md)。本文描述 Java 主线的源码行为，已发布版本与验证范围见[当前状态](progress/CURRENT.md)。

本体解析标准 blockstate/JSON、父链、纹理、普通几何、普通 OBJ，以及 Forge 和 NeoForge 的声明式组合模型。加载器差异按已核实的数据语义处理。未知 loader、运行时生成模型及未知颜色处理器保留原始方块身份与明确原因。空声明与缺资源分别记录。

### 从存档读取到模型着色

| 层次 | 职责 |
|---|---|
| 存档读取 | `SavedSpawn` 识别坐标结构及所属维度，`WorldDimensionDiscovery` 发现命名空间维度与 `DIM<整数>` 传统维度。`BlockPaletteDecoder` 识别 1.13+ 调色板编码，`ChunkNbtDecoder` 同时读取 1.18+ `section.biomes` 调色板与 1.2–1.17.1 `Level.Biomes`（`int[1024]` / `int[256]` / `byte[256]`）数字群系；`LegacyAnvilSectionDecoder` 结合 `SavedLegacyIdMap`（`FML.ItemData` / `FML.Registries`）和原版数字表读取 1.2.1–1.12.2 预扁平化区块，预扁平化存档的二维采样与三维简化颜色底座可用，不烘焙旧版三维纹理模型。 |
| 原版状态 | `VanillaBlockDefaults` 用区块自己的 `DataVersion` 查询随程序分发的默认属性，显式属性优先。表格由本地固定提交的原版注册报告摘要生成，来源记录在表头。补充数据版本可复用同一读取器。[生成器](../tools/import-block-defaults.py) · [默认值表](../src/main/resources/vanilla-protocol/block-defaults.tsv) |
| 模型选择 | `BlockModelResolver` 用有效状态求值 variants 和 multipart。在普通 blockstate 根层附带的 `parent` 不参与分派，模型定义中的 `parent` 才解析父链。缺少注册证据时，仍可使用无条件模型和已写明的属性。条件需要省略值时，诊断包含方块 ID、属性名和区块数据版本。 |
| 原版世界着色 | 模型选择完成后，按每个面的 `tintindex` 与客户端世界颜色注册取得颜色来源。群系采样读取选区及邻区的保存数据，遵循有效数据包和实例色表。未知层保留诊断。[颜色层声明](../src/main/resources/model-protocols/vanilla-tint-layers.json) |
| 共用加载器格式 | `ModelLoaderProtocol` 负责扩展格式的 loader 声明、根变换与 OBJ 的 UV 语义。OBJ 和组合模型分别按声明的加载器 ID 注册，可跨 Mod 复用。只在相应扩展声明出现时核对平台语义。 |
| 第三方扩展 | 插件可用 `ModelRule` 解析自定义模型，按需声明位置、邻域、实体或群系上下文，也可单独用 `tintLayers()` 登记有依据的方块颜色层。用户可自行编写插件，或把选区诊断交给 Agent 构建。[扩展构建提示词与接口说明](PLUGIN_DEVELOPMENT.md#agent-prompt) |

世界模型的多候选选择使用有效资源包中的数组顺序和正整数 `weight`，省略时为 1。`variants` 直接使用方块位置种子，`multipart` 的每个匹配部件从同一派生种子开始抽取。选择算法由基础客户端的资源格式决定，资源包覆盖只改变候选内容。资源格式 4–36 使用旧式抽取，37–97 使用有界抽取。这两种抽取共用条件、几何和着色流程。无位置的静态原型或尚未识别的客户端算法会给出首项近似说明。第三方位置依赖规则调用 `apply` 或 `blockstateParts` 时也可复用该选择器。

`multipart.when` 可以对属性取反。资源格式 4–51 对整个候选组取反，从格式 52 起逐项取反后再求并集。例如保存值为 `y` 时，`!x|y` 在两种语义下分别为假、真。混合写法缺少基础客户端格式证据时给出诊断，单项取反可直接求值。

从资源格式 74 起，元素可绕指定原点依次在 X、Y、Z 轴旋转，状态分派还可在 X、Y 之后绕 Z 轴作直角旋转。两者分别作用于局部几何和整个部件。格式 35 起，元素的 `light_emission` 参与静态模型面的最低亮度计算。[旋转格式说明](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w46a) · [元素亮度说明](https://www.minecraft.net/en-us/article/minecraft-snapshot-24w33a)

高频 Mod 格式按共同的数据结构和消费语义评估。可重复的独立资源样本、可描述的输入及有界读取方式，是纳入共用规则的依据。个别 Mod 的注册回调和专用外观由第三方插件实现。

| 本体内容 | 准入依据与边界 |
|---|---|
| 标准 blockstate 与 JSON 模型 | variants/multipart、父链、纹理引用、elements、UV/变换、材质；未知渲染字段明确拒绝 |
| Forge 与 NeoForge 组合模型 | `forge:composite` 与 `neoforge:composite` 共用命名子项、继承可见性和独立材质的读取器。Forge 1.20.1 的物品绘制顺序引用隐藏子项时烘焙失败，已核实的 NeoForge 1.21.1 可跳过该项。两种声明分别注册。组合根变换与 UV 锁定同时出现时仍报告未支持。[Forge 实现](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/src/main/java/net/minecraftforge/client/model/CompositeModel.java) · [NeoForge 实现](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/model/CompositeModel.java) |
| 普通 OBJ | 同一读取器通过 Create 与 Immersive Engineering 的独立真实资源见证，不保留专用私有回调 |
| 标准颜色 | 已选原版客户端的有限绑定与草/叶/水群系语义；未知 Mod 回调保留诊断 |
| 来源与预算 | 使用所选实例与客户端、资源包顺序、完整 SHA、输入变更检测和有界缓存。唯一明确的 Forge 或 NeoForge 启动参数优先确定实例平台。每个 Mod 的格式归属另从清单识别，支持表头行尾注释并跳过多行描述和依赖段中的示例 ID。 |

1.20.1 和 1.21.1 由已验证的静态注册解析器取得绑定。1.21.5、26.1 与 26.3 共用已核实的植被图层声明，覆盖草色、双层植物、花茎，以及橡树、丛林木、金合欢、深色橡树、红树与藤蔓的群系叶色。普通模型面还支持枯叶、甘蔗、炼药锅水面、红石线、成长和连接的瓜茎、睡莲。枯叶使用自己的群系覆盖色或枯叶色表。红石线和生长期瓜茎读取方块状态。26.3 的水和气泡柱注册仅在地形粒子中取群系水色，流体表面仍由独立几何路径绘制。云杉和白桦按客户端注册的固定色乘以模型面纹理。1.21.5 的樱叶按客户端缺省语义保留纹理本色。版本标识仅用于选择声明，已登记版本的客户端内容指纹必须匹配，类字节码不符时保持拒绝。26.1 与 26.3 的世界颜色注册类指纹相同，两个构建共用图层表。群系草色噪声计算保留已验证实现的返回精度。对尚未建立静态类指纹档案且 `pack_format >= 4` 的原版客户端，回退使用内置通用原版颜色层分组（含 1.13–1.20.2 矮草 `minecraft:grass`，`pack_format < 35` 不启用 `dryFoliage`）；对 1.13–1.19.2 未内置 `dimension_type` 或 `worldgen/biome` 数据注册表的客户端，原版维度高度与 `minecraft:` 群系气候／水色回退至内置标准原版表并继续读取客户端真实颜色表，诊断说明中明确标注近似来源。其他颜色来源及未核实的 Mod 回调仍报告 `UNBOUND_TINT`。Mod 归属来自已选实例的唯一元数据身份，资源来源单独显示，不从材质或外形猜测。

通用不表示全覆盖。当前固定选集、自动回归与原生人工验收分别记录；不承诺整个 Mod、全部资源包或未来版本兼容。

外部插件安装后默认停用；只接受当前 `mcwe-model-api-1-core.1` 接口清单。此前专用协议探索的 preview 清单不兼容，会在执行前拒绝；本期不迁移这些插件。隔离 worker 负责可终止的生命周期，不是操作系统权限沙箱，卸载清理限于宿主管理副本、索引、进程和缓存，不擅自删除原始安装文件或无关用户文件。


### 静态特殊几何与流体

旗帜支架和末地传送表面的贴图路径由基础客户端的资源布局确定，再按实例的资源包优先级读取。旗布的底色与保存的图案层单独合成。末地传送门与末地折跃门使用本地传送纹理的静态近似。

铜箱使用通用箱体规则，氧化程度和单双箱决定实体纹理。铜傀儡雕像读取共用关节表，姿态、方位和氧化程度分别决定几何、旋转与纹理。涂蜡状态沿用对应材质。显式空几何及空气类方块保留空面集；只有粒子纹理且未识别静态几何的资源保留原因。

水、熔岩、气泡柱和含水宿主从保存状态取得流体类型与液量。建网格时邻格液量决定四角高度和流向，同族共享面被剔除。水层独立取得保存群系的水色，熔岩保留自身纹理和不透明材质。纹理使用静态首帧。部分方块的碰撞形状来自运行时代码，离线预览用闭合模型估算固体边界；流向阻挡优先读有效数据包的 `blocks_fluid_flow` 标签。
