# CLAUDE.md

This file provides guidance for working in this repository.

## Project overview

Krystals is an Android CIF crystal viewer and editor built with Kotlin and Gradle. The project has six modules:

- `crystal-data`: compile-time element, ionic, bond-valence, Shannon-radius, and space-group tables. Package: `com.krystals.crystal.data`.
- `crystal-core`: pure JVM crystallographic primitives, geometry, symmetry parsing, expression parsing, and space-group behavior. Package: `com.krystals.crystal.core`.
- `crystal-analysis`: pure JVM structure models, symmetry expansion, bonding, coordination, editing, structure information, and polyhedron hull analysis. Packages start with `com.krystals.crystal.analysis`.
- `crystal-io`: pure JVM loss-aware CIF parsing and writing. Package: `com.krystals.crystal.io`.
- `renderer`: Android Compose Canvas rendering and bitmap export. Package and namespace: `com.krystals.crystal.renderer`.
- `app`: Android application, UI, file access, tabs, and remote database clients. Package, namespace, and application ID remain `com.krystals.app`.

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
- Keep this module behavior-free and independent of the other project modules.

### `crystal-core`

- `model`: pure `CrystalStructure`, `Site`, `Species`, and `AtomImage` values.
- `lattice`: strongly typed `Lattice` matrices, volume, and coordinate conversion.
- `coordinate`: `FractionalCoordinate` and `CartesianCoordinate` values with explicit `Vec3` conversion.
- `symmetry`: `SpaceGroup`, `SpaceGroupCatalog`, `SymmetryOperation`, and fractional-number parsing.
- `math`: `Vec3`, `Mat3`, measurements, rotations, and safe expression parsing.
- `periodic`: `Int3` cell offsets and periodic-boundary operations.

### `crystal-analysis`

- `model/Model.kt`: expansion and crystal-information values plus chemistry-only periodic-table queries.
- `expansion/SymmetryExpander.kt`: expands asymmetric sites to `AtomImage` values.
- `bonding`: owns `BondRule`, `BondConfiguration`, `Bond`, `BondNetwork`, detection, matching, and bond-valence analysis. Boundary-image and external-shell behavior must remain unchanged.
- `bonding/BondValence.kt`: smart-ionic rules and per-site bond-valence sums.
- `bonding/BondRuleMatching.kt`: efficient rule visibility checks.
- `coordination/CoordinationAnalyzer.kt`: builds bond adjacency while respecting the renderer's `showBonds` and hidden-bond filters.
- `editing/CrystalEditor.kt`: immutable `EditCommand` application and bond-rule regeneration.
- `structure/StructureAnalyzer.kt`: composition, density, and crystal information.
- `polyhedron/PolyhedronHull.kt`: polygonal convex-hull faces for coordination polyhedra.

### `crystal-io`

`CifCodec.kt` parses multi-block CIF 1.1 content into `CifDocument`/`ParsedStructure` and writes structural changes back while preserving unrelated content and comments. `ParsedStructure` separates the core structure, analysis bond configuration, and IO-only display metadata. It currently supports CIF only.

### `renderer`

`CrystalViewport.kt` and `CrystalImageExporter.kt` consume `BondNetwork` plus an explicit `RenderConfiguration`. Renderer owns all appearance models, color resolution, and display-radius queries. Polyhedron vertices come from `CoordinationAnalyzer`; hidden external-shell ligands still complete coordination even when their bond line is not drawn. Boundary images remain visible by default, while external-shell atoms require `extendAcrossCell`.

### `app`

The app owns Android UI and platform I/O only. CIF conversion goes through `crystal-io`; crystallographic operations go through `crystal-analysis`; rendering goes through `renderer`.

## Data flow

1. `app` reads CIF text and passes it to `crystal-io`.
2. `CifCodec.parseStructure` produces a core structure, bond configuration, and CIF display metadata.
3. `SymmetryExpander` creates atom images and `BondDetector` creates a `BondNetwork`.
4. `CoordinationAnalyzer` and `PolyhedronHull` provide renderer-facing analysis.
5. `CrystalViewport` or `CrystalImageExporter` renders the network.
6. Saving passes the edited structure, bond configuration, and converted display metadata back to `CifCodec.write`.

## Contributor notes

- JVM tests use JUnit 5. The sample CIF corpus is under `res/cifs_example` and is exercised by `crystal-io` tests.
- The app packages the root `res/` directory as assets.
- Rendering is Compose Canvas-based; there is no Filament dependency.
- Phase commits and merge commits must use English messages.
- At the end of every phase, run at least `.\gradlew.bat :crystal-core:test` and `.\gradlew.bat :app:assembleDebug` with JDK 17. Run analysis and IO tests when those modules change.

## Workflow

Do not open a pull request. Complete phase work on its worktree branch, commit it with an English message, merge it directly into `master` with an English merge message, and verify `master` after the merge.
