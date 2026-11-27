# MC World Explorer SDK 使用指南

`:mc-world-explorer-sdk` 是 MC World Explorer 拆出的无 UI、纯 Java 21 只读核心库（详见 [DECISION-025](decisions/DECISION-025.md)）。它不依赖 JavaFX、LWJGL、Ikonli 或特定日志实现，适合在命令行工具、存档转换器或服务端批处理程序中直接读取 Minecraft Java 版存档、生成三维体素网格、导出标准 Wavefront `.obj` / `.mtl` / `_atlas.png` 与无头二维俯视 PNG 地图。

## 能力与只读边界

- **严格只读**：仅读取目标存档目录与可选客户端/实例纯数据，不启动 Minecraft、不加载游戏或 Mod 类、不修改存档目录。
- **支持版本**：支持 Minecraft Java 版 `1.2.1 – 26.x` Anvil 存档（`1.6.1 – 1.12.2` 预扁平化存档及 `1.13+` 现代存档，含主世界、下界、末地及常规 Mod 维度）。
- **依赖纯净**：仅传递依赖 `net.kyori:adventure-nbt`、`com.google.code.gson:gson`、`org.joml:joml` 与 `org.slf4j:slf4j-api`。宿主项目可自行选择任意 SLF4J 实现（如 `logback-classic`、`slf4j-simple` 或 `slf4j-nop`）。
- **开源许可**：与主仓库一致采用 GNU GPL v3.0 许可证（详见根目录 [LICENSE](../LICENSE)）。
- **内置安全边界**：
  - 单次三维选区默认上限为每边 `32` 区块、总计 `256` 区块（`ChunkRectangle.target(...)`）。
  - 单次无头二维地图渲染上限为单边 `4096` 像素、总计 `16,777,216` 像素与最多 `16,384` 个采样区块。
  - `WavefrontObjExporter` 严格校验 `baseName` 仅允许 `[A-Za-z0-9._-]` 且禁止 `..` 与路径分隔符，防止路径穿越。

## 引入方式

需要 **JDK 21** 及以上环境。

### 方式一：通过 JitPack 引入（Gradle / Maven）

标签发布后可通过 JitPack 直接拉取 `:mc-world-explorer-sdk`（仓库根目录已提供 `jitpack.yml`）：

**Gradle（Groovy DSL）：**

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.huang3337.MC-World-Explorer:mc-world-explorer-sdk:v0.8.1'
}
```

**Maven：**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.huang3337.MC-World-Explorer</groupId>
        <artifactId>mc-world-explorer-sdk</artifactId>
        <version>v0.8.1</version>
    </dependency>
</dependencies>
```

### 方式二：本地构建并发布到本地 Maven 仓库（`~/.m2`）

在仓库根目录执行：

```powershell
.\gradlew.bat :mc-world-explorer-sdk:publishToMavenLocal
```

随后在目标项目中添加 `mavenLocal()` 与依赖：

```groovy
implementation 'com.mcworldexplorer:mc-world-explorer-sdk:0.8.1'
```

### 方式三：直接使用离线分发 JAR

在仓库根目录执行 `.\gradlew.bat assembleSdkDist` 后，可在 `build/core-0.8.1/sdk-dist/` 获取：

- `mc-world-explorer-sdk-0.8.1.jar`
- `mc-world-explorer-sdk-0.8.1-sources.jar`
- `mc-world-explorer-sdk-0.8.1-javadoc.jar`

## 快速开始

### 1. 无客户端 JAR：读取存档并导出纯色三维 `.obj` + `.mtl`

无需任何原版客户端 JAR 即可直接读取存档（包括转换器刚生成的世界）并导出标准 Wavefront `.obj` + `.mtl`：

```java
import com.mcworldexplorer.sdk.McWorld;
import com.mcworldexplorer.sdk.ObjExportResult;
import com.mcworldexplorer.sdk.SceneBuildRequest;
import com.mcworldexplorer.sdk.WorldExplorerSdk;
import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.nio.file.Path;

public class SolidObjExample {
    public static void main(String[] args) throws Exception {
        McWorld world = WorldExplorerSdk.openWorld(Path.of("path/to/your_world"));
        System.out.println("SDK " + WorldExplorerSdk.version()
                + " | World: " + world.info().getLevelName());

        // 选取 (0,0) 至 (3,3) 共 4x4 区块（或使用 builderForBlockRange）
        SceneBuildRequest request = SceneBuildRequest.of(ChunkRectangle.target(0, 0, 3, 3));
        ObjExportResult result = world.exportWavefrontObj(
                request, Path.of("exports"), "spawn_area");

        System.out.println("OBJ: " + result.objFile());
        System.out.println("MTL: " + result.mtlFile());
        System.out.println("Vertices: " + result.vertexCount() + ", Faces: " + result.faceCount());
    }
}
```

### 2. 带客户端/实例资源：导出含群系着色与双层遮罩烘焙的 `.obj` + `.mtl` + `_atlas.png`

传入对应版本的原版客户端 JAR（以及可选整合包实例目录）后，SDK 会解析标准方块的 JSON/OBJ/`forge:composite`/`neoforge:composite` 模型与群系着色，并将 `0.8.0` 逐顶点群系色与草边双层遮罩按 Porter-Duff `source-over` 烘焙至去重导出图集 `{baseName}_atlas.png`（自动翻转 OBJ `vt` 的 V 轴），可在 Blender、Three.js 或 Windows 3D 查看器中直接打开还原外观：

```java
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.sdk.McWorld;
import com.mcworldexplorer.sdk.ObjExportResult;
import com.mcworldexplorer.sdk.SceneBuildRequest;
import com.mcworldexplorer.sdk.WorldExplorerSdk;

import java.nio.file.Path;

public class TexturedObjExample {
    public static void main(String[] args) throws Exception {
        McWorld world = WorldExplorerSdk.openWorld(Path.of("path/to/your_world"));

        SceneBuildRequest request = SceneBuildRequest
                .builderForBlockRange(-32, -32, 31, 31)
                .dimensionId(WorldDimension.OVERWORLD_ID)
                .clientJar(Path.of("path/to/1.21.1.jar"), "1.21.1")
                // 可选：叠加整合包实例中的 mods 与资源包
                // .instanceDirectory(Path.of("path/to/instance"))
                .build();

        ObjExportResult result = world.exportWavefrontObj(
                request, Path.of("exports"), "textured_spawn");

        System.out.println("OBJ: " + result.objFile());
        System.out.println("MTL: " + result.mtlFile());
        System.out.println("Atlas PNG: " + result.textureFile().orElseThrow());
    }
}
```

### 3. 无头二维地图 PNG 导出

直接从 Region 文件无头采样并渲染带高度阴影的二维俯视图，不写入任何磁盘缓存：

```java
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.sdk.MapRenderRequest;
import com.mcworldexplorer.sdk.McWorld;
import com.mcworldexplorer.sdk.WorldExplorerSdk;

import java.nio.file.Path;

public class MapPngExample {
    public static void main(String[] args) throws Exception {
        McWorld world = WorldExplorerSdk.openWorld(Path.of("path/to/your_world"));

        MapRenderRequest request = MapRenderRequest.builder(-256, -256, 255, 255)
                .layer(PreviewLayer.surfaceOverview())
                .blocksPerPixel(1)
                .build();

        Path png = world.exportMapPng(request, Path.of("exports/overworld_map.png"));
        System.out.println("Map PNG: " + png);
    }
}
```

### 4. 直接访问三维网格数据（`VoxelSceneSnapshot`）

如需自行接入其他渲染引擎或格式转换器，可调用 `world.buildScene(request)` 获取不可变的 `VoxelSceneSnapshot`，按区块遍历 `ChunkMeshSnapshot` 与 `MeshBatch`（含顶点坐标、法线、索引、基础 UV、逐顶点群系 RGB 以及可选双层遮罩 UV 与 RGB）。
