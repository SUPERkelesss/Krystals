# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Krystals is an Android CIF crystal viewer and editor. It is a Kotlin/Gradle project with three modules:

- `crystal-core`: pure JVM module for loss-aware CIF parsing/writing, crystallographic math, symmetry expansion, bond inference, and structure editing commands.
- `renderer`: Android library that renders the crystal with Compose Canvas and exports PNG images.
- `app`: Android application that wires the UI, file I/O, tab state, and editors together.

Build requirements: JDK 17, Android SDK 36, Gradle 8.11.1. The repository includes a wrapper and a bootstrap script that can download a portable JDK and SDK on Windows.

## Common commands

Use `gradlew`/`gradlew.bat` from the repository root. The bootstrap script sets `JAVA_HOME` and writes `local.properties` with the SDK path automatically.

| Task | Command |
|------|---------|
| Bootstrap environment and build debug APK (Windows) | `\.\scripts\bootstrap-build.ps1` |
| Build debug APK | `.\gradlew.bat :app:assembleDebug` |
| Run JVM unit tests for `crystal-core` | `.\gradlew.bat :crystal-core:test` |
| Run a single test class | `.\gradlew.bat :crystal-core:test --tests "com.krystals.core.CoreTest"` |
| Run a single test method | `.\gradlew.bat :crystal-core:test --tests "com.krystals.core.CoreTest.parsesAndExpandsSymmetry"` |
| Run Android instrumented tests | `.\gradlew.bat :app:connectedAndroidTest` |
| Lint | `.\gradlew.bat lint` |
| Clean | `.\gradlew.bat clean` |
| Generate paid-module activation codes (1000) | `.\gradlew.bat :app:generateActivationCodes` |

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## High-level architecture

### `crystal-core`

The core module contains no Android dependencies.

- **Geometry** (`Geometry.kt`): `Vec3`, `Int3`, `Mat3` (stored by columns to match lattice-vector notation), and `UnitCell` with conversions between fractional and Cartesian coordinates.
- **CIF codec** (`CifCodec.kt`): parses multi-block CIF 1.1 files into `CifDocument`/`CifBlock`/`CifLoop`/`CifPair`, preserves comments and non-structural items, and writes back by surgically replacing only the structural tags (`_cell_*`, `_atom_site_*`, `_space_group_*`, `_symmetry_equiv_pos_*`, `_krystals_bond_rule_*`). Use `CifCodec.parseStructure` to get a `ParsedStructure`, which carries both the original document and the derived `CrystalStructure`.
- **Model** (`Model.kt`): `CrystalStructure`, `AtomSite`, `ExpandedAtom` (with `isShell`, and per v0.3.41 `isBoundaryImage` / derived `isExternalShell` to split neighbour-cell images into "boundary images" sitting on the primary-box faces vs. genuine external shell), `BondRule` (per v0.3.0 `extendAcrossCell` gates cross-cell bond/atom visibility), `Bond`, `Expansion`, `ViewerAppearance`, and `SceneSnapshot`. `PeriodicTable` carries four radius/color tables. Per v0.4.1 three bond-radius tables are baked in: **bonding** radii (键合半径, 82 entries, CC BY-SA 4.0 / arXiv:2601.02017v1 [cond-mat.mtrl-sci] 05 Jan 2026), **covalent** radii, and **vdW** radii (the latter two from `.todos/elements.ini` columns 2–3), plus an RGB color table (elements.ini columns 6–8). `RadiusSource` (`BONDING`/`COVALENT`/`VDW`) selects which table `radius(symbol, source)` returns, with the legacy `covalentRadius`/`radii` map as the fallback for elements absent from the chosen table (D, the `XX` placeholder, the noble gases H/He/Ne/Ar, several heavy elements without a tabulated bonding value, and 95+ actinides). Default element color is `elementArgb` (the `elements.ini` RGB table) with `vestaArgb` as the fallback; `resolveArgb`/`resolveSiteArgb` route through it so site/element overrides still take precedence.
- **Engine** (`CrystalEngine.kt`): expands the asymmetric unit via symmetry operations, builds supercells, infers bonds, and computes crystal info such as density and Hill-ordered composition. Per v0.3.4 it materialises a shell of neighbour cells around the primary expansion region; bonds are computed from primary **and boundary-image** atoms to atoms in their 3×3×3 neighbouring cells using real Cartesian distances (no minimum-image offsets). Per v0.3.44 the shell is **two cells thick** (`[-2, ex+2)³`): a boundary-image centre sits at offset ±1 on a primary-box face, so its outward neighbours land at offset ±2 and must be materialised for that centre's coordination polyhedron to be complete (a 1-cell shell only completed the primary `(0,0,0)` site). Atoms in the second layer are external shell (hidden by default; kept when referenced by a bond). Shell atoms that do not participate in any bond are discarded before the `SceneSnapshot` is returned. `MAX_RENDERED_ATOMS` is 100,000. Same-site integer-translation pairs (periodic images of one atom, e.g. Cs–Cs) are suppressed by the auto covalent-radius fallback but allowed when an explicit rule exists for the pair.
- **Editor** (`CrystalEditor.kt`): applies immutable `EditCommand` values to a `CrystalStructure`. Commands cover cell/space-group changes, atom add/update/delete, bond rules, and 3x3 integer transformation matrices. `ensureAutoBondRules` (called on preset open, atom add, and transforms) generates a rule per site pair using **bonding** radii (`RadiusSource.BONDING`) with window `0.1 .. rA+rB+0.45` Å — this is the v0.4.1 default. `rebuildBondRules(structure, source)` clears all existing rules **and** `disabledBondPairs`, then regenerates every pair under the chosen radius table; it backs the bond editor's "自动应用半径" button (bonding/covalent/vdW).
- **Space groups** (`SpaceGroupCatalog.kt`): catalogs all 230 space-group symbols and derives crystal system/point group. `operations()` returns the symmetry-operation strings for all 230 groups (baked into `operationsTable`); unrecognised names fall back to identity. The table was generated from Hall symbols via `scripts/dev/generate_spacegroups.py` (spglib) — regenerate from there if a group's operations ever need correction.
- **Expression parser** (`ExpressionParser.kt`): small arithmetic evaluator used for fractional coordinates and cell parameters in the UI.

### `renderer`

- `CrystalViewport.kt`: a `@Composable` that draws the scene to a Compose `Canvas`. It owns a `ViewerController` for yaw/pitch/zoom/pan and supports atom picking, measurements (length/angle/dihedral), cell frames, polyhedra, and per-site visibility. Boundary images (shell atoms on the primary-box faces) are visible by default; external-shell atoms are hidden unless their bond rule has `extendAcrossCell = true`. Polyhedra are built from the full coordination environment (every bond's endpoints, including hidden external-shell ligands, via a decoupled `neighbors` table) so boundary/corner coordination is complete; boundary images also act as polyhedron centres. Each polyhedron face is emitted as its own renderable sorted by nearest-vertex depth (painter's order); back faces are drawn outline-only at low alpha (per v0.3.44) so the back silhouette stays faintly visible. Coplanar ligand faces are produced as a single polygon by `convexHullFaces` (2D convex-hull boundary extraction, per v0.3.44 — fixes quad/prism sides that the old edge-count logic dropped); fully planar coordinations fall back to `planarPolygonFaces`, and flat coordinations emit a reversed translucent face so they are visible from both sides.
- `CrystalImageExporter.kt`: produces a high-resolution `Bitmap` using the same projection logic as the viewport (atoms, bonds, polyhedra, measurements, info windows), useful for PNG export. Visibility/polyhedron logic mirrors the viewport.
- `FilamentRuntime.kt`: a minimal availability check for Filament 1.71.5; the actual rendering is currently Canvas-based.

### `app`

- `MainActivity.kt`: entry point, handles incoming CIF URIs and applies the saved language locale in `attachBaseContext`.
- `KrystalsApp.kt`: root Compose UI (`KrystalsRoot`). Manages the app-level menu, file open/save via the Storage Access Framework, theme/language settings, and PNG export permission flow.
- `DocumentState.kt`: `KrystalsViewModel` holds a list of `DocumentTab`s. Each tab keeps its own `parsed` CIF document, working `structure`, `expansion`, `visibility`, `appearance`, `selectedAtomIds`, and `measurementMode`.
- `EditorPanels.kt`: the structure editor with tabs for basic info, atoms, bonds, and supercell expansion. Edits flow through `CrystalEditor.apply` and update the tab's working structure. The bond tab's "自动应用半径" button (v0.4.1) drops a dropdown offering bonding/covalent/vdW radius regeneration via `rebuildBondRules`; the "新建规则" dialog defaults its max-distance to the bonding-radii sum and re-syncs when the selected sites change.
- `FileRepository.kt`: reads/writes text via `ContentResolver` and exports PNGs to `Pictures/Krystals` using `MediaStore`.
- `Localization.kt`: simple bilingual helper (`localized(zh, en)`) resolved from the current configuration locale.
- `ActivationManager.kt`: paid-module activation. Verifies a 16-char activation code by computing `sha256(BuildConfig.ACTIVATION_SALT + code)` and checking membership in `BuildConfig.ACTIVATION_HASHES` (a comma-joined list baked in at compile time). Both fields are injected from the gitignored `activation-secrets.gradle.kts`; when that file is absent (clean clone from github) both are empty and `isActivated()` always returns false, so the build still succeeds with paid features locked. The activated code itself is persisted in `SharedPreferences("krystals")` under `activation_code` and re-validated on every launch (a single code is reusable indefinitely). Activation is reached from the sponsor dialog's "I've sponsored!" button; once active it suppresses the automatic sponsor prompt and unlocks the Materials Project search gate (`onPickMp` shows `MpPremiumDialog` instead of the API-key flow when not activated).

### Data flow

1. A CIF file is opened and parsed into a `ParsedStructure`.
2. The UI mutates a working `CrystalStructure` through `EditCommand`s.
3. `CrystalEngine.buildScene(structure, expansion)` produces a `SceneSnapshot` of atoms and bonds.
4. `CrystalViewport` renders the snapshot and returns tap events; `CrystalImageExporter` renders the same view to a bitmap.
5. Saving writes the working structure back into the original document via `CifCodec.write`, preserving unrelated content.

## Notes for contributors

- `crystal-core` tests use JUnit 5 (`useJUnitPlatform`). The sample CIF corpus lives under `res/cifs_example` and is exercised by `SampleCifTest`.
- The app module packages the `res/` directory as assets (`sourceSets["main"].assets.srcDir(rootProject.file("res"))`), so bundled images and CIF samples are available at runtime without copying them into `app/src/main/assets`.
- Filament is declared as a dependency but the rendering path is currently Compose Canvas; changes to 3D rendering should verify whether Filament integration is being activated.
