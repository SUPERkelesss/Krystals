

# Krystals

<img src="img/banner.png" alt="banner" />

<h3 align="center"> Krystals：一款轻量级 Android CIF 晶体编辑器 </h3>
  <p align="center">
    在手机上像 VESTA 一样方便地查看晶体！
    <br />
    <a href="https://github.com/SUPERkelesss/Krystals/releases"> <strong> 下载发行版 </strong> </a> ·
    <a href="https://github.com/SUPERkelesss/Krystals/issues"> 报告 Bug </a> · 
    <a href="README.md"> English README </a> 
  </p>



---

## 如何安装？

前往项目的 [Release 页面](https://github.com/SUPERkelesss/Krystals/releases)，下载最新安装包并安装。

## 快速开始

主界面提供四种导入 CIF 文件的方式：

- **导入本地文件**：从文件管理器中选择 CIF 文件并打开。
- **从预设导入**：App 提供了丰富的预设库，涵盖大部分基础 CIF 测试用例。你也可以将自己的 CIF 文件保存到预设库中。
- **从在线源导入**：提供两种方式——从 [Crystallography Open Database](https://qiserver.ugr.es/cod/index.php) 导入和从 [Materials Project](https://next-gen.materialsproject.org/) 导入。后者需要 API 密钥，是赞助后通过激活码解锁的高级功能。前者无使用限制。
- **新建文件**：从空文件开始创建 CIF 文档。

**主界面**：

![panel](img/panel.jpg)

- ① 菜单选项，包括文件创建和保存操作；
- ② 更改整体外观设置，切换明暗主题；
- ③ 查看器主屏幕
  - 单指拖拽旋转晶体，双指拖拽缩放和平移晶体；
  - 双击原子显示其信息；点击原子信息窗口可将其锁定。
- ④ 图例
- ⑤ 快捷操作悬浮球
  - **对齐**：将晶体视图对齐到指定晶轴；
  - **锁定**：固定晶胞视图使其保持不变；
  - **测量**：进入测量模式，测量长度和角度。点击测量信息窗口可将其锁定；
  - **编辑**：进入编辑器页面，修改晶胞数据、原子坐标和化学键规则；
  - **显示**：进入显示页面，调整原子、化学键和配位多面体的可见性；
  - **信息**：显示晶体信息窗口。

---

## 架构图

```mermaid
flowchart TB
    subgraph app["app · Android 应用程序"]
        UI["KrystalsApp<br/>Compose UI"]
        VM["DocumentState<br/>ViewModel / 多标签页"]
        Edit["EditorPanels<br/>结构编辑器"]
        Repo["FileRepository<br/>CIF I/O / PNG 导出"]
        Act["ActivationManager<br/>付费激活"]
    end
    subgraph interaction["interaction · 后端无关的查看器交互"]
        Commands["ViewerCommand / InteractionReducer"]
        CameraControl["轨道 / 缩放 / 平移 / 对齐"]
        ViewerTools["选择 / 拾取 / 测量 / 检视 / 可见性"]
    end
    subgraph rendererCore["renderer-core · 后端无关场景"]
        Builder["CrystalSceneBuilder"]
        Scene["RenderScene / RenderObject"]
        Primitive["AtomInstance / BondInstance / MeshInstance"]
    end
    subgraph rendererLegacy["renderer-legacy · Canvas 旧版 (已停止维护 — 不再构建,仅供参考)"]
        VP["CrystalViewport<br/>旧版 Canvas 视口"]
        Exp["CrystalImageExporter<br/>高分辨率位图导出"]
    end
    subgraph rendererFilament["renderer-filament · Filament 边界"]
        Filament["FilamentSceneRenderer"]
    end
    subgraph io["crystal-io · CIF 文件 I/O"]
        Codec["CifCodec<br/>无损 CIF 解析/回写"]
    end
    subgraph analysis["crystal-analysis · 结构分析"]
        Symmetry["SymmetryExpander<br/>原子镜像展开"]
        Bonds["BondDetector / BondValence<br/>BondNetwork"]
        Coordination["CoordinationAnalyzer"]
        Hull["PolyhedronHull"]
        Editor["CrystalEditor<br/>不可变 EditCommand 编辑"]
    end
    subgraph core["crystal-core · 晶体学原语"]
        Geometry["Geometry / Symmetry"]
        SG["SpaceGroupCatalog<br/>230 种空间群"]
    end
    subgraph data["crystal-data · 编译期数据表"]
        Elements["元素 / 离子数据"]
        SpaceGroups["空间群符号 / 操作"]
    end

    UI --> Commands
    Commands --> CameraControl
    Commands --> ViewerTools
    Commands --> VP
    UI --> Edit
    UI --> VM
    Repo -->|"parseStructure"| Codec
    Codec -->|"CrystalStructure"| Symmetry
    Edit -->|"EditCommand"| Editor
    Editor --> Symmetry
    Symmetry --> Bonds
    Bonds -->|"BondNetwork"| Builder
    Builder --> Scene
    Scene --> Primitive
    Scene --> VP
    Scene --> Exp
    Bonds --> Coordination
    Coordination --> Hull
    Repo -->|"write back"| Codec
    Symmetry --> Geometry
    SG --> SpaceGroups
    Bonds --> Elements

    app -.->|依赖| interaction
    app -.->|依赖| analysis
    app -.->|依赖| io
    rendererCore -.->|API 依赖| analysis
    rendererLegacy -.->|依赖| rendererCore
    rendererLegacy -.->|输入与拾取适配| interaction
    interaction -.->|相机与场景契约| rendererCore
    rendererFilament -.->|依赖| rendererCore
    io -.->|依赖| analysis
    analysis -.->|依赖| core
    analysis -.->|依赖| data
    core -.->|依赖| data
```

---

### 构建包

所需环境：

- Android Studio，含 Android SDK 36
- JDK 17
- Gradle

运行以下脚本下载所需环境并配置构建：

```pwsh
.\scripts\bootstrap-build.ps1
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 贡献者

暂无……

## 许可证

本项目基于 [MIT 许可证](LICENSE) 发布。

## 鸣谢


- Openai ChatGPT 5.6 sol
- Kimi k3
