# CLAUDE.md

This file provides guidance for working in this repository.

## Project overview

Krystals is an Android CIF crystal viewer and editor built with Kotlin and Gradle. The project has nine modules:

- `crystal-data`: compile-time element, ionic, bond-valence, Shannon-radius, and space-group tables. Package: `com.krystals.crystal.data`.
- `crystal-core`: pure JVM crystallographic primitives, geometry, symmetry parsing, expression parsing, and space-group behavior. Package: `com.krystals.crystal.core`.
- `crystal-analysis`: pure JVM structure models, symmetry expansion, bonding, coordination, editing, structure information, and polyhedron hull analysis. Packages start with `com.krystals.crystal.analysis`.
- `crystal-io`: pure JVM loss-aware CIF parsing and writing. Package: `com.krystals.crystal.io`.
- `renderer-core`: pure JVM renderer abstractions — scene model, camera, projection, materials, primitive instances, render style, and the `SceneRenderer` SPI. Package: `com.krystals.renderer.core`.
- `interaction`: pure JVM viewer interaction model — camera orbit/pan/zoom/align, atom inspection, selection, measurement, and visibility management. Exposes `InteractionReducer` (reduces `InteractionState` via `ViewerCommand`) and the `Picker` SPI. Package: `com.krystals.interaction`.
- `renderer-filament`: Android library rendering via Google Filament 1.71.5. Implements `SceneRenderer` + `Picker` for the 3D GPU backend. Package: `com.krystals.renderer.filament`.
- `renderer-legacy`: **END OF LIFE.** Android library with the Compose Canvas 2D renderer. No longer built (excluded from `settings.gradle.kts`) and not used by `:app` — the app is fixed on Filament. Directory retained for reference only; receives no maintenance. Package: `com.krystals.renderer.legacy`.
- `app`: Android application, UI, file access, tabs, and remote database clients. Renders exclusively with Filament (the `renderer-legacy` backend is end-of-life). Package, namespace, and application ID remain `com.krystals.app`. Version 0.6.1.

Build requirements: JDK 17, Android SDK 36, and Gradle 8.11.1. Java 24 can fail during Gradle test task configuration with `Type T not present`; use JDK 17 for all verification.

## Common commands

Run commands from the repository root.

| Task | Command |
|------|---------|
| Bootstrap environment and build debug APK (Windows) | `.\scripts\bootstrap-build.ps1` |
| Build debug APK | `.\gradlew.bat :app:assembleDebug` |
| Run core tests | `.\gradlew.bat :crystal-core:test` |
| Run analysis tests | `.\gradlew.bat :crystal-analysis:test` |
| Run IO tests | `.\gradlew.bat :crystal-io:test` |
| Run renderer-core tests | `.\gradlew.bat :renderer-core:test` |
| Run interaction tests | `.\gradlew.bat :interaction:test` |
| Run a single core test | `.\gradlew.bat :crystal-core:test --tests "com.krystals.crystal.core.CoreTest"` |
| Run a single analysis test | `.\gradlew.bat :crystal-analysis:test --tests "com.krystals.crystal.analysis.AnalysisTest"` |
| Run a single CIF test | `.\gradlew.bat :crystal-io:test --tests "com.krystals.crystal.io.CifCodecTest.parsesAndExpandsSymmetry"` |
| Run Android instrumented tests | `.\gradlew.bat :app:connectedAndroidTest` |
| Lint | `.\gradlew.bat lint` |

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

### `crystal-data`

- `PeriodicTableData.kt` owns element masses, covalent/bonding/van der Waals radii, colors, IUCr BVPARM parameters, Shannon crystal radii, fixed anion valences, and Pauling electronegativities.
- `SpaceGroupData.kt` owns the 230 space-group symbols and their symmetry-operation strings.
- `BravaisLatticeData.kt` owns the 14 Bravais lattice centering types and primitive↔conventional conversion matrices.
- Keep this module behavior-free and independent of the other project modules.
- No dependencies on other Krystals modules.

### `crystal-core`

Depends on `crystal-data` (`implementation`).

- `model`: pure `CrystalStructure`, `Site`, `Species`, and `AtomImage` values. `CrystalStructure.isConventional` tracks whether the cell is conventional; non-conventional cells are auto-converted on CIF parse.
- `lattice`: strongly typed `Lattice` matrices, volume, and coordinate conversion.
- `coordinate`: `FractionalCoordinate` and `CartesianCoordinate` values with explicit `Vec3` conversion.
- `symmetry`: `SpaceGroup`, `SpaceGroupCatalog`, `SymmetryOperation`, and fractional-number parsing.
- `math`: `Vec3`, `Mat3`, measurements, rotations (via `eulerYX`), and safe expression parsing. `Mat3` has `fromRows(Float)` and `fromRowsDouble(Double)` factory methods for row-major matrix construction (used in Bravais lattice transformations).
- `periodic`: `Int3` cell offsets and periodic-boundary operations.

### `crystal-analysis`

Depends on `crystal-core` and `crystal-data` (both `api`).

- `model/Model.kt`: expansion and crystal-information values plus chemistry-only periodic-table queries.
- `expansion/SymmetryExpander.kt`: expands asymmetric sites to `AtomImage` values.
- `bonding`: owns `BondRule`, `BondConfiguration`, `Bond`, `BondNetwork`, `BondDetector`, `BondModels`, matching, and bond-valence analysis. Boundary-image and external-shell behavior must remain unchanged.
- `bonding/BondValence.kt`: smart-ionic rules and per-site bond-valence sums.
- `bonding/BondRuleMatching.kt`: efficient rule visibility checks.
- `bonding/VoronoiNeighbours.kt`: periodic Voronoi neighbour search for smart-ionic bond-rule generation.
- `coordination/CoordinationAnalyzer.kt`: builds bond adjacency while respecting `showBonds` and hidden-bond filters.
- `editing/CrystalEditor.kt`: immutable `EditCommand` application, bond-rule regeneration, `isConventionalCell()` heuristic, and `convertPrimitiveToConventional()`. Also owns metal/non-metal classification (`isMetal`). **`isMetal` is duplicated in `bonding/BondValence.kt` — changes must be kept in sync.**
- `structure/StructureAnalyzer.kt`: composition, density, and crystal information.
- `polyhedron/PolyhedronHull.kt`: polygonal convex-hull faces for coordination polyhedra.

### `crystal-io`

Depends on `crystal-analysis` (`api`).

`CifCodec.kt` parses multi-block CIF 1.1 content into `CifDocument`/`ParsedStructure` and writes structural changes back while preserving unrelated content and comments. `ParsedStructure` separates the core structure, analysis bond configuration, and IO-only display metadata. On parse, auto-detects non-conventional cells (e.g. rhombohedral :R) via `CrystalEditor.isConventionalCell()` and converts them to conventional via `convertPrimitiveToConventional()`. The `_krystals_is_conventional` custom CIF tag preserves this flag on write. It currently supports CIF only.

### `renderer-core`

Depends on `crystal-analysis` (`api`) and `crystal-data` (`implementation`). Pure JVM — no Android or Compose dependencies.

- `SceneRenderer.kt`: backend-neutral SPI with `submit(RenderScene)`, `updateInteraction(InteractionState)`, and `clear()`. Both `FilamentSceneRenderer` and the EOL `LegacySceneRenderer` implement this interface.
- `scene/RenderScene.kt`: data class holding `CrystalStructure`, `Expansion`, `List<RenderObject>`, `Camera`, `Projection`, and `RenderEnvironment`. Render objects are `AtomInstance`, `BondInstance`, or `MeshInstance`.
- `builder/CrystalSceneBuilder.kt`: constructs a `RenderScene` from a `BondNetwork` and `SceneBuildOptions` (visibility, radii, materials, bond color mode, environment).
- `builder/CrystalRenderSceneFactory.kt`: higher-level factory composing expansion, bonding, and scene building.
- `camera/Camera.kt`: `Camera` data class with position, target, rotation (`Mat3` — not Euler angles), and zoom.
- `camera/Projection.kt`: `Orthographic` and `Perspective` projection models.
- `scene/SceneProjection.kt`: projects scene coordinates to screen space.
- `scene/SceneBounds.kt`: scene bounding-volume computation.
- `scene/CellFrameGeometry.kt`: unit-cell wireframe geometry.
- `scene/DepthRange.kt`: depth-range utilities.
- `primitive/`: `AtomInstance`, `BondInstance`, `MeshInstance` — renderer-agnostic primitive descriptors.
- `material/Material.kt`: material properties (color, opacity, double-sided, etc.).
- `state/InteractionState.kt`: pure data classes for `ViewerSessionState`, `SelectionState`, `InspectionState`, `VisibilityState`, `ViewerDocumentState`, and `InteractionState`. Defined here so all modules share one canonical definition.
- `style/RenderStyle.kt`: `RenderConfiguration`, `ViewerAppearance`, `BondColorMode`.
- `style/BackgroundColor.kt` and `style/SelectionColors.kt`: color definitions.
- `measure/Measurement.kt`: measurement mode and selection types.

### `interaction`

Depends on `renderer-core` (`api`). Pure JVM — no Android or Compose dependencies.

- `state/InteractionReducer.kt`: reduces `(InteractionState, ViewerCommand) → InteractionState`. The single reducer handles all viewer commands — camera movement, alignment, atom selection, inspection, measurement, and visibility changes.
- `state/ViewerCommand.kt`: sealed interface of all user actions (orbit, zoom, pan, align, select, inspect, measure, toggle visibility).
- `state/InteractionState.kt`: type aliases re-exporting the canonical state types from `renderer-core`.
- `camera/`: `OrbitController`, `PanController`, `ZoomController`, `AlignmentController` — pure math implementations of each camera interaction.
- `selection/Picker.kt`: SPI for hit-testing. `PickResult` holds atom/site/bond/mesh picks. Each rendering backend implements `Picker` independently.
- `inspection/InspectionManager.kt`: transient vs. locked inspection logic.
- `measure/Measurement.kt`: measurement mode and selection types specific to interaction.
- `visibility/VisibilityManager.kt`: site/bond/polyhedron visibility toggling.

### `renderer-filament`

Depends on `renderer-core` (`api`) and `interaction` (`api`). Android library using Google Filament 1.71.5 for GPU-accelerated 3D rendering. The Filament AAR is loaded from `.tooling/filament-android-1.71.5.aar` (offline bootstrap) or Maven (online).

- `FilamentSceneRenderer.kt`: Android-specific sub-interface of `SceneRenderer` + `Picker`, adding `attach(Surface)`, `detach()`, and `renderToBitmap()`.
- `FilamentRenderer.kt`: the concrete `FilamentSceneRenderer` implementation. Owns the Filament `Engine`, `SwapChain`, `Renderer`, `Scene`, and `View`.
- `GpuInstanceManager.kt`: GPU buffer management for instanced atom and bond rendering.
- `InstanceManager.kt`: CPU-side instance state tracking and dirty-flag management.
- `PickingRenderer.kt`: Filament-based pick-by-color implementation of `Picker`.
- `MaterialFactory.kt`: creates Filament material instances from `renderer-core` `Material` descriptors.
- `MeshUploader.kt`: uploads polyhedron and cell-frame meshes to GPU buffers.
- `DirtyFrameBudget.kt`: adaptive frame-budget management for incremental scene updates.

### `renderer-legacy`

**END OF LIFE — not built, not used by the app.** Depends on `crystal-analysis` (`api`), `renderer-core` (`api`), and `interaction` (`api`). Android library with Compose Canvas 2D rendering, retained for reference only (excluded from `settings.gradle.kts`).

- `LegacySceneRenderer.kt`: adapter implementing `SceneRenderer` + `Picker` via Compose `MutableState`. Scene/interaction/appearance state held as Compose state drives recomposition of `Content`.
- `LegacyRenderSceneAdapter.kt`: converts `RenderScene` into the legacy `CrystalViewport`-compatible form.
- `CrystalViewport.kt`: Compose Canvas composable that draws the crystal structure in 2D.
- `CrystalImageExporter.kt`: bitmap export from the legacy renderer.
- `LegacyRenderMath.kt`: projection and rendering math shared by viewport and exporter. Camera orientation uses `Mat3`, not Euler angles — changes to rotation logic must keep `CrystalViewport` and `CrystalImageExporter` in sync.
- `InteractionCompatibility.kt` and `RenderStyleCompatibility.kt`: bridges between new `renderer-core` types and legacy renderer types.

### `app`

Depends on `crystal-analysis`, `crystal-io`, `interaction`, and `renderer-filament`. Android application.

- `MainActivity.kt`: single-activity entry point.
- `KrystalsApp.kt`: top-level Compose UI, tab management, file open/save.
- `ViewerBackendHost.kt`: unified Compose host that mounts the Filament backend, handles gesture dispatch, and routes `ViewerCommand` through `InteractionReducer`.
- `DocumentState.kt`: per-document state — structure, expansion, bond network, scene, and interaction state.
- `EditorPanels.kt`: editing UI panels (atom/site property editing, bond rules, etc.).
- `FloatingBallLayout.kt`: floating-ball UI layout component for tool palettes.
- `FileRepository.kt`: local CIF file storage and listing.
- `CrystallographyOpenDatabase.kt`: Crystallography Open Database (COD) client for online structure search and download.
- `MaterialsProject.kt`: Materials Project API client.
- `PresetRepository.kt`: bundled preset structure catalog.
- `ActivationManager.kt`: paid-module activation via 16-char codes with SHA-256 verification.
- `AppearanceStore.kt`: per-user appearance preferences (colors, radii, bond styles) persisted in `SharedPreferences`.
- `History.kt`: undo/redo history for edit operations.
- `Localization.kt`: Chinese/English localization strings.
- `ui/Theme.kt`: Material 3 theme definitions.

## Data flow

1. `app` reads CIF text (local file, COD, or Materials Project) and passes it to `crystal-io`.
2. `CifCodec.parseStructure` produces a core `CrystalStructure`, bond configuration, and CIF display metadata.
3. `SymmetryExpander` creates `AtomImage` values; `BondDetector` uses `VoronoiNeighbours` to create a `BondNetwork`.
4. `CrystalRenderSceneFactory` (renderer-core) composes the `BondNetwork` into a backend-neutral `RenderScene` with `AtomInstance`/`BondInstance`/`MeshInstance` primitives.
5. The Filament backend renders the scene via the `SceneRenderer` SPI.
6. User gestures flow through `InteractionReducer.reduce(state, command)` in the `interaction` module, producing new `InteractionState`. The state update is pushed to the active renderer via `SceneRenderer.updateInteraction()`.
7. Saving passes the edited structure, bond configuration, and converted display metadata back to `CifCodec.write`.

## Contributor notes

- JVM tests use JUnit 5. The sample CIF corpus is under `res/cifs_example` and is exercised by `crystal-io` tests.
- The app packages the root `res/` directory as assets.
- The project renders exclusively through the Filament (GPU 3D) backend. The Canvas-Legacy 2D backend (`renderer-legacy`) is end-of-life: excluded from the build, retained for reference only.
- The `interaction` module is a pure-JVM state machine: camera math, selection, inspection, measurement, and visibility. It has no Android or rendering dependencies — only `renderer-core` types.
- Camera orientation is stored as a `Mat3` rotation matrix, not Euler angles. Changes to rotation logic must keep `CrystalViewport`, `CrystalImageExporter`, and the `interaction` camera controllers in sync.
- Filament AARs are at `.tooling/filament-android-1.71.5.aar` and `.tooling/filament-utils-android-1.71.5.aar`. When absent, the build falls back to Maven.
- Phase commits and merge commits must use English messages.
- At the end of every phase, run at least `.\gradlew.bat :crystal-core:test` and `.\gradlew.bat :app:assembleDebug` with JDK 17. Run analysis, IO, and renderer tests when those modules change.

## Workflow

Do not open a pull request. Complete phase work on its worktree branch, commit it with an English message, merge it directly into `master` with an English merge message, and verify `master` after the merge.
