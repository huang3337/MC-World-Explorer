# 解析插件开发说明

适用：Java 主线 `0.7.4`，插件接口 `mcwe-model-api-1-core.1`。当前应用提供通用插件管理，默认不安装插件；Mod 专用适配不随本体提供。旧 `preview.*` 接口包不能直接安装，也不能只改清单版本来迁移。

本说明中的示例仅解释合成资源格式，不适配任何真实 Mod。不需要启动 Minecraft，也不需要把 Mod 或客户端 JAR 加入 Java 类路径。交付范围见 [DECISION-024](decisions/DECISION-024.md)。

## 从解析诊断确定问题

先选择对应版本的客户端、实际游戏目录与资源包，再生成包含目标方块的小选区。在“资源与插件 → 解析诊断”按 Mod 或命名空间筛选，查看原始方块 ID、状态、原因和资源来源；复制或导出报告用于复现。

报告描述的是**当前生成选区中观察到的问题**。某个 Mod 出现一条失败，不表示整个 Mod 不受支持；列表中没有某个 Mod，也不表示已经验证过它。资源未加载、无最近生成结果和确实没有问题是不同状态。

| 要核对的信息 | 用途 |
|---|---|
| `blockId`、`state` | 固定真实方块身份和属性，避免拿另一种状态代替复现。 |
| `namespace`、`ownerLabel`、`ownerConfirmed` | 命名空间来自原始 ID；只有实例元数据确认的归属才作为 Mod 身份。归属未知时保留未知，不能按纹理或外形猜测。 |
| `severity`、`reasonCode`、`detail` | 区分解析失败、降级与仅有诊断信息，确认具体缺口。 |
| `loaders` | 解析实际遇到的 loader ID；不从 Mod 名称推导。一个 loader 可以被多个 Mod 使用。 |
| `assets` 中的 `kind`、`id`、`origin` | 找到 blockstate、模型或纹理及其实际来源。资源包可以覆盖 Mod 资源，资产来源与方块所属 Mod 必须分开。来源不可用时不作推断。 |
| `occurrences`、`chunks` | 观察次数和出现区块，用于缩小复现范围；不是全实例支持率，也不是逐方块坐标清单。 |

JSON 报告当前为 `schemaVersion: 1`，记录 `hostVersion`、`pluginApiRevision`、`scope`、`generated`、`resourceModelsPrepared`、`summary`、`groups`、`entries` 和 `contextDetails`。`groups` 给出各命名空间的失败、降级、信息计数及问题条目数；`entries` 保留明细。`generated: false` 表示尚无生成结果；`resourceModelsPrepared: false` 表示本次未准备模型纹理资源，不能据此判定某个 Mod 已成功或不受支持。

报告有预算上限，先保留失败，其次降级和说明。当前最多 4096 个聚合条目，每条最多 24 个资产引用、128 个区块，详情正文按 4096 字符截断并附提示。顶层与条目的 `truncated` 表明内容有截断，`omittedWarningCount` / `omittedOccurrences` 给出因条目数量限制省略的记录和次数，`omittedContextDetails` 给出超过 128 条环境说明预算后省略的数量；这些计数不能覆盖资源引用、详情等所有类型的截断。看到截断时缩小选区重新生成，不能把保留计数当全量统计。同一方块也可能产生多项诊断。

分享前检查 `scope`、资产来源和上下文是否包含不希望公开的本机路径。不要把整份 Mod JAR 或存档当作必需的反馈附件。

### 哪些问题适合写插件

| 诊断情况 | 建议处理 |
|---|---|
| `UNSUPPORTED_MODEL`，资产齐全且存在明确的自定义 loader/JSON 表达 | 先核对 loader 消费语义，再实现对应 `ModelRule`；这是当前接口最直接的适用场景。 |
| `MISSING_RESOURCE`、`MISSING_TEXTURE`、选错客户端或资源包覆盖错误 | 先修正资源选择或资源本身。普通规则插件不能凭空替代缺失 blockstate，也不是安装游戏依赖的工具。 |
| `SIMPLE_COLORS` | 当前没有按模型纹理资源解析，先配置正确资源并重新生成，不能直接当作 Mod 或插件失败。 |
| `INVALID_JSON`、`INVALID_MODEL`、引用循环、无匹配状态 | 检查实际资源和状态。只有证据表明这是合法但宿主尚未理解的表达时，才考虑适配，不能吞掉错误伪装成功。 |
| `LIMIT_EXCEEDED`、`PREPARATION_LIMIT`、`STATE_NOT_PREPARED` | 缩小选区或降低资源复杂度，并报告预算触发位置。插件仍受宿主读取、递归、几何与进程预算约束。 |
| `UNBOUND_TINT` | 通用颜色语义无法确定。当前没有任意 Mod 颜色回调注册接口，不能把所有 tint 都涂成草色。 |
| `TEXTURE_PREPARATION_FAILED` | 结合详情判断缺纹理、非法纹理或预算错误；几何 loader 插件不一定能解决。 |
| `MODEL_NOTE`、`INFO` | 可能是静态近似或固定选择等说明，不应直接计作解析失败。 |
| 依赖游戏运行时渲染、注册恢复、生成资源、物品/实体模型或专用容器服务 | 当前 `core.1` 不提供这些宿主服务。明确留缺项，不能靠执行游戏类补齐。 |

## 当前插件契约

入口类实现 `ModelPlugin`，必须是公开类并提供公开无参构造。`descriptor()` 必须与包清单中的 `plugin` 对象完全一致；`rules()` 返回明确注册的规则。

每条 `ModelRule` 声明唯一 `id()`、自身 `version()`、所处理的 `loaders()` 和额外 JSON `fields()`。宿主按资源里的 loader 或明确声明的 blockstate 选择字段派发，不根据 `targetMods` 猜测解析方式。规则 ID 或 loader 与其他已启用规则冲突时，整次启用失败并保留原状态。

`Context` 提供当前模型、原始方块状态、纹理绑定、旋转、渲染类型及有界解析服务。优先使用 `elements()`、`child()`、`independentChild()`、`blockstate()`、`assetJson()`、`dataJson()` 等现有宿主服务，让资源依赖与错误继续可追踪。不能取得的上下文应抛出明确的 `ModelRule.Failure`，不要用空气、空列表或猜测值冒充解析成功。

需要相邻方块、位置、保存实体 NBT 或群系数据时，须分别通过规则的 `neighbors()`、`positionDependent()`、`savedEntityDependent()`、`biomeColorDependent()` 声明真实依赖，并处理准备阶段 `blockContext()` 为空的情况。清单能力当前只接受 `SAVED_DATA`、`NEIGHBORS`、`BIOME_COLOR`；这些是能力说明，不是自动注册规则或操作系统权限。

`targetMods` 同样是目标说明，不是按需启动保证。安装后停用不会启动 worker；启用时会建立独立进程，即使当前选区没有对应方块，仍有启动和常驻成本。没有匹配 loader 时不会因 `targetMods` 而强制执行 `resolve()`。

宿主没有发布独立 SDK 分发包。开发时只把当前应用 JAR 和其依赖当作编译依赖；**不能把这些 JAR 或宿主类重新打进插件**。源码中的 `public` 不等于插件可访问：worker 只开放有限接口，UI、缓存实现和其他宿主内部类不可调用。不存在 Mod/加载器注册读取、生成资源、物品/实体模型、Moonlight 等旧探索服务；不要实现已不消费的 `generatedNamespaces()` 来假装获得资源生成能力。

## 最小示例：读取合成 boxes 格式

在与宿主项目分离的插件工程中准备以下目录。下面的文件内容可以直接复制；示例 namespace 为 `tutorial`，不是任何真实 Mod 的适配。

```text
boxes-plugin/
  src/tutorial/BoxesPlugin.java
  resources/META-INF/mcwe-model-plugin.json
```

`src/tutorial/BoxesPlugin.java`（UTF-8，无 BOM）：

```java
package tutorial;

import com.google.gson.JsonElement;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Part;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Reason;
import com.mcworldexplorer.voxel.resource.ModelPlugin;
import com.mcworldexplorer.voxel.resource.ModelRule;
import com.mcworldexplorer.voxel.resource.ResourceLocation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BoxesPlugin implements ModelPlugin {
    public BoxesPlugin() { }

    @Override public Descriptor descriptor() {
        return new Descriptor(
            ResourceLocation.parse("tutorial:boxes_plugin"),
            "Boxes 格式示例", "1.0.0", ModelPlugin.API_VERSION,
            "仅演示合成 boxes 资源格式", Set.of("tutorial"),
            Map.of(), Set.of());
    }

    @Override public Collection<? extends ModelRule> rules() {
        return List.of(new BoxesRule());
    }

    private static final class BoxesRule implements ModelRule {
        @Override public ResourceLocation id() {
            return ResourceLocation.parse("tutorial:boxes_rule");
        }
        @Override public String version() { return "1.0.0"; }
        @Override public Set<ResourceLocation> loaders() {
            return Set.of(ResourceLocation.parse("tutorial:boxes"));
        }
        @Override public Set<String> fields() { return Set.of("boxes"); }

        @Override public List<Part> resolve(Context context) throws Failure {
            var definition = context.definition();
            if (definition.has("transform")) {
                throw new Failure(Reason.UNSUPPORTED_MODEL,
                    "Boxes 示例尚未实现模型根变换");
            }
            JsonElement boxes = definition.get("boxes");
            if (boxes == null || !boxes.isJsonArray()) {
                throw new Failure(Reason.INVALID_MODEL, "boxes 必须是数组");
            }
            if (boxes.getAsJsonArray().isEmpty()) {
                throw new Failure(Reason.INVALID_MODEL, "Boxes 示例要求至少一个元素");
            }
            return List.of(new Part(
                context.model(), context.xRotation(), context.yRotation(),
                context.uvLock(), context.elements(boxes.getAsJsonArray()),
                context.renderType()));
        }
    }
}
```

`resources/META-INF/mcwe-model-plugin.json`（UTF-8）：

```json
{
  "format": 1,
  "sdkRevision": "mcwe-model-api-1-core.1",
  "compatibleHosts": ["0.7.4"],
  "execution": "isolated-jvm",
  "entrypoint": "tutorial.BoxesPlugin",
  "plugin": {
    "id": "tutorial:boxes_plugin",
    "name": "Boxes 格式示例",
    "version": "1.0.0",
    "apiVersion": 1,
    "description": "仅演示合成 boxes 资源格式",
    "targetMods": ["tutorial"],
    "dependencies": {},
    "capabilities": []
  }
}
```

`compatibleHosts` 是精确宿主版本列表，不是版本区间或通配表达。依赖插件写在 `dependencies`，键为插件 ID，值为精确插件版本或 `*`；依赖必须已安装并启用，不能形成循环。规则版本、插件版本与宿主版本分别管理。

### 编译和打包

使用 JDK 21。应用自带的运行时不保证包含 `javac`；另行准备 JDK。在 `boxes-plugin` 目录打开 PowerShell 执行，按提示填写本机 JDK 与应用目录：

```powershell
$ErrorActionPreference = 'Stop'
$jdkDirectory = Read-Host 'JDK 21 目录（其中应有 bin\javac.exe）'
$applicationDirectory = Read-Host 'MC World Explorer 应用目录（其中应有 app 目录）'
$compileClasspath = Join-Path $applicationDirectory 'app\*'
$classesDirectory = Join-Path (Get-Location).Path ('out\classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null

& (Join-Path $jdkDirectory 'bin\javac.exe') --release 21 -encoding UTF-8 -proc:none `
    -cp $compileClasspath -d $classesDirectory '.\src\tutorial\BoxesPlugin.java'
if ($LASTEXITCODE -ne 0) { throw '插件编译失败' }

& (Join-Path $jdkDirectory 'bin\jar.exe') --create --file '.\out\boxes-plugin-1.0.0.jar' `
    -C $classesDirectory . -C '.\resources' META-INF
if ($LASTEXITCODE -ne 0) { throw '插件打包失败' }

& (Join-Path $jdkDirectory 'bin\jar.exe') --list --file '.\out\boxes-plugin-1.0.0.jar'
Get-FileHash -LiteralPath '.\out\boxes-plugin-1.0.0.jar' -Algorithm SHA256
```

每次用新的编译目录，避免旧 `.class` 混入。产物应只有 `tutorial/` 下的插件类、插件清单及 JAR 工具生成的普通清单；不应出现 `com/mcworldexplorer/`、Gson、JOML、Kyori、Minecraft、加载器或 Mod 类。使用 Gradle/Maven 的作者同样只做 `compileOnly`/`provided` 引用，不使用 fat JAR 或把依赖合并到输出中。

当前包限制：压缩文件不超过 16 MiB，展开总量不超过 64 MiB，单普通条目不超过 4 MiB，最多 4096 条目；插件清单 UTF-8 文件读取不超过 32 KiB。拒绝嵌套 JAR、本机库、服务发现条目、多版本目录，以及 JAR 主清单中的 `Class-Path`、`Main-Class`、`Multi-Release`。这些限制不可通过重复文件名或相对路径绕过。

通过正常安装库启动时，每个 worker 的最大 Java 堆为 256 MiB、最大元空间为 128 MiB，启动与单次调用预算为 30 秒。通信单帧不超过 16 MiB，单次调用至多发起 4096 次宿主服务请求。这些是当前实现上限，不是建议把每次解析都用满预算；避免在 `resolve()` 中重复全目录扫描、创建额外进程或保留无界缓存。

### 准备能触发规则的样本

安装插件不会自动添加方块或往存档写入数据。示例只在已有模型资源声明 `"loader": "tutorial:boxes"` 时运行。可在独立的测试资源与读取夹具中使用：

```json
{
  "loader": "tutorial:boxes",
  "textures": {"all": "minecraft:block/stone"},
  "boxes": [{
    "from": [0, 0, 0],
    "to": [16, 8, 16],
    "faces": {
      "up": {"texture": "#all"},
      "down": {"texture": "#all"},
      "north": {"texture": "#all"},
      "south": {"texture": "#all"},
      "west": {"texture": "#all"},
      "east": {"texture": "#all"}
    }
  }]
}
```

对应测试 blockstate 可以指向这个模型。为真实插件建立测试时，另取已授权的保存状态与资源副本；不要直接修改正在浏览的实例或存档。应至少验证一种合法状态、一种非法输入、一种缺资源情况，以及启用前后的诊断差异。真实适配还要核对旋转、材质、tint、邻域、父链和多个状态；本示例能编译不等于完整支持任何 Mod。

## 安装、启停与更新验证

1. 打开“资源与插件 → 插件”，选择刚构建的 JAR，核对身份、版本、目标、SHA 和信任提示。安装成功后应显示停用状态；安装不执行入口类。
2. 勾选插件并应用。入口描述与清单不一致、类链接失败、依赖缺失或规则冲突必须明确报错，原启用集合保持不变。
3. 重新生成固定小选区，检查目标规则实际命中；对比诊断里的原始状态、资源来源、原因与几何结果，不能只看“已启用”。
4. 停用并应用，应退出 worker、清除旧解析/场景缓存和旧画面。再次生成应恢复本体结果，而不是继续使用旧插件模型。停用仍保留再次启用所需的安装副本与索引。
5. 更新时选择同一插件 ID 的新 JAR。即使显示版本相同，内容 SHA 改变也会更换执行实例并使相关缓存失效；建议仍正常递增插件和规则版本，方便用户识别。
6. 卸载应删除受管理安装副本和记录，最后一个插件卸载后不保留空管理目录。用户最初选择的 JAR 保留。Windows 占用导致的待清理必须可见，解除占用后通过“重新检查”重试。
7. 关闭再打开应用，分别核对启用、停用和卸载状态。另测启动失败、损坏清单、更新失败、超时与崩溃，确认没有残留进程或错误沿用旧结果。

宿主管理目录是应用配置下的 `model-plugins/`，安装副本以内容 SHA 命名；不要手工覆盖其中的 JAR 或编辑索引。宿主可强制结束 worker，插件不能依赖 `close()` 或 shutdown hook 一定执行才能保存数据。尽量让解析保持纯函数，不创建外部持久文件。隔离进程提供可释放的生命周期，并不是限制文件或网络权限的操作系统沙箱。

## 提交可复现的问题

反馈包含：应用版本、插件版本与 SHA、客户端/加载器/目标 Mod 版本、选区大小、导出的相关诊断条目、启停前后差异，以及最小合法资源和状态。对素材再分发权限不明时，只给资源 ID、来源名称与哈希，不附完整受限资源。

注明验证层次：清单检查、编译、worker 解析、真实保存样本、原生界面和性能测试分别记录。未测与跳过不能计作通过；两个资源样本也不能推出整个 Mod 或多数 Mod 已支持。
