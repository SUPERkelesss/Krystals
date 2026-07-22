

# Krystals

<img src="img/banner.png" alt="banner" />  

<h3 align="center"> Krystals: Android 平台轻量化 CIF 晶体编辑器 </h3>
  <p align="center">
    像 vesta 一样在移动平台便捷地查看晶体！
    <br />
    <a href="https://github.com/SUPERkelesss/Krystals/releases"> <strong> 下载发行包 </strong> </a> ·
    <a href="https://github.com/SUPERkelesss/Krystals/issues"> 报告 Bug </a>
  </p>


---

## 如何安装？

进入该项目的 [Release 页面](https://github.com/SUPERkelesss/Krystals/releases) ，下载最新安装包并安装即可。

## 快速上手

主界面内提供了四种方式导入 CIF 文件：

- **导入本地文件**：从文件管理器选择 CIF 文件并打开。
- **从预设中导入**：软件提供了丰富的预设资源包，涵盖大部分基础 CIF 测试。您也可以将自己的 CIF 文件保存在预设中。
- **从在线源导入**：提供了从 [Crystallography Open Database](https://qiserver.ugr.es/cod/index.php) 中导入和从 [Materials Project](https://next-gen.materialsproject.org/) 中导入两种选择，其中后者需要 apikey，属于高级功能，需要赞助后使用激活码解锁。前者使用不受限制。
- **创建新文件**：从空文件开始创建 CIF 文档。

**软件主界面**：

![panel](img/panel.jpg)

- ① 菜单选项，包含文件新建和保存操作；
- ② 更改整体外观配置于调节明暗主题；
- ③ viewer 主界面
  - 单指拖动可旋转晶体，双指拖动可缩放和平移晶体；
  - 双击原子将显示原子信息，点按原子信息窗即可锁定该信息窗。
- ④ 图例
- ⑤ 常用操作悬浮球
  - **对齐**：将晶体视图对齐到某一轴向；
  - **锁定**：固定晶胞视图不变；
  - **测量**：进入测量模式，选择测量长度和角度数据。单击测量信息窗可锁定；
  - **编辑**：进入编辑页面，修改晶胞数据，原子坐标和化学键规则；
  - **显示**：进入显示页面，调节原子，化学键和配位多面体可见性；
  - **信息**：显示晶体信息窗。

---

## 架构图

```mermaid
flowchart TB
    subgraph app["app · Android 应用"]
        UI["KrystalsApp<br/>Compose UI"]
        VM["DocumentState<br/>ViewModel / 多标签"]
        Edit["EditorPanels<br/>结构编辑器"]
        Repo["FileRepository<br/>CIF 读写 / PNG 导出"]
        Act["ActivationManager<br/>付费激活"]
    end
    subgraph rendererCore["renderer-core · 后端无关场景"]
        Builder["CrystalSceneBuilder"]
        Scene["RenderScene / RenderObject"]
        Primitive["AtomInstance / BondInstance / MeshInstance"]
    end
    subgraph rendererLegacy["renderer-legacy · Canvas-Legacy"]
        VP["CrystalViewport<br/>旧 Canvas 视口"]
        Exp["CrystalImageExporter<br/>高清位图导出"]
    end
    subgraph rendererFilament["renderer-filament · Filament 边界"]
        Filament["FilamentSceneRenderer"]
    end
    subgraph io["crystal-io · CIF 文件 IO"]
        Codec["CifCodec<br/>无损 CIF 解析/回写"]
    end
    subgraph analysis["crystal-analysis · 晶体分析"]
        Symmetry["SymmetryExpander<br/>AtomImage 展开"]
        Bonds["BondDetector / BondValence<br/>BondNetwork"]
        Coordination["CoordinationAnalyzer"]
        Hull["PolyhedronHull"]
        Editor["CrystalEditor<br/>EditCommand 不可变编辑"]
    end
    subgraph core["crystal-core · 晶体学基础类型"]
        Geometry["Geometry / Symmetry"]
        SG["SpaceGroupCatalog<br/>230 空间群"]
    end
    subgraph data["crystal-data · 编译期数据表"]
        Elements["元素 / 离子数据"]
        SpaceGroups["空间群符号 / 操作"]
    end

    UI --> VP
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
    Repo -->|"write 回写"| Codec
    Symmetry --> Geometry
    SG --> SpaceGroups
    Bonds --> Elements

    app -.->|依赖| rendererLegacy
    app -.->|依赖| analysis
    app -.->|依赖| io
    rendererCore -.->|api 依赖| analysis
    rendererLegacy -.->|依赖| rendererCore
    rendererFilament -.->|依赖| rendererCore
    io -.->|依赖| analysis
    analysis -.->|依赖| core
    analysis -.->|依赖| data
    core -.->|依赖| data
```

---

### 构建安装包

所需环境：

- Android Studio with Android SDK 36
- JDK 17
- Gradle

只需运行该脚本文件，即可下载所需环境并配置安装包：

```pwsh
.\scripts\bootstrap-build.ps1
```

debug APK 文件位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 贡献者

目前还没有呢……成为其中的一员吧！

## 版权说明

本项目遵循 [MIT 协议许可](LICENSE)。

## 鸣谢


- Openai ChatGPT 5.6 sol
- Kimi k3
