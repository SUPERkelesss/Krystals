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
- **Model** (`Model.kt`): `CrystalStructure`, `AtomSite`, `ExpandedAtom` (with `isShell`, and per v0.3.41 `isBoundaryImage` / derived `isExternalShell` to split neighbour-cell images into "boundary images" sitting on the primary-box faces vs. genuine external shell), `BondRule` (per v0.3.0 `extendAcrossCell` gates cross-cell bond/atom visibility), `Bond`, `Expansion`, `ViewerAppearance`, and `SceneSnapshot`. `PeriodicTable` carries radius/color tables. Per v0.4.1 two radius sources are baked in: **BONDING** (键合半径, CC BY-SA 4.0 / arXiv:2601.02017v1 [cond-mat.mtrl-sci] 05 Jan 2026) and **VDW**; BONDING is the bonding-radius table with the covalent single-bond table (`.todos/atomic_radii.md`, Wikipedia "Atomic radii of the elements" `Covalant(single bond)` column) as its fallback for elements it lacks, then the legacy `covalentRadius`/`radii` map for elements neither covers (D, the `XX` placeholder, Fr, 97+ actinides). VDW comes from the same `atomic_radii.md` `vdW` column with the `covalentRadius` fallback. Per v0.5.0 a third source **SMART_IONIC** (智能离子) is the default: `BondValence.smartIonicRules` estimates each cation site's oxidation state via a bond-valence sum (BVS, IUCr BVPARM2020 R0/B parameters in `.todos/bondvalence/bvparm2020.cif`), reads its coordination number from a bonding-radius neighbour count, then looks up a Shannon crystal radius (R. D. Shannon 1976, `.todos/bondvalence/Radii for All Species.html`, high-spin preferred) for (element, valence, CN) and builds per-site-pair rules; anions carry fixed valences (O −2, F/Cl/Br/I −1, S/Se/Te −2, N/P/As −3; H and C are treated as cations/covalent, not fixed anions, so e.g. C–C and O–H still get rules) and **both anion and cation sites are looked up in the Shannon table** at their (valence, CN) — anions at the fixed valence, cations at the BVS-estimated one — with cation/anion roles split by Pauling electronegativity. Same-site or non-ionic pairs and any site lacking bvparm/Shannon data fall back to the bonding radius. An RGB color table (`.todos/elements.ini` columns 6–8) sets the default element color via `elementArgb`, with `vestaArgb` as the fallback; `resolveArgb`/`resolveSiteArgb` route through it so site/element overrides still take precedence.
- **Engine** (`CrystalEngine.kt`): expands the asymmetric unit via symmetry operations, builds supercells, infers bonds, and computes crystal info such as density and Hill-ordered composition. Per v0.3.4 it materialises a shell of neighbour cells around the primary expansion region; bonds are computed from primary **and boundary-image** atoms to atoms in their 3×3×3 neighbouring cells using real Cartesian distances (no minimum-image offsets). Per v0.3.44 the shell is **two cells thick** (`[-2, ex+2)³`): a boundary-image centre sits at offset ±1 on a primary-box face, so its outward neighbours land at offset ±2 and must be materialised for that centre's coordination polyhedron to be complete (a 1-cell shell only completed the primary `(0,0,0)` site). Atoms in the second layer are external shell (hidden by default; kept when referenced by a bond). Shell atoms that do not participate in any bond are discarded before the `SceneSnapshot` is returned. `MAX_RENDERED_ATOMS` is 100,000. Same-site integer-translation pairs (periodic images of one atom, e.g. Cs–Cs) are suppressed by the auto covalent-radius fallback but allowed when an explicit rule exists for the pair.
- **Editor** (`CrystalEditor.kt`): applies immutable `EditCommand` values to a `CrystalStructure`. Commands cover cell/space-group changes, atom add/update/delete, bond rules, and 3x3 integer transformation matrices. `ensureAutoBondRules` (called on preset open, atom add, and transforms) is the v0.5.0 default: it runs `BondValence.smartIonicRules` (per-site Shannon radii) and falls back to bonding radii when the structure can't be analysed or exceeds `SMART_IONIC_ATOM_LIMIT` (100 expanded atoms, a performance guard for high-symmetry supercells). `rebuildBondRules(structure, source, epsilon)` clears all existing rules **and** `disabledBondPairs`, then regenerates every pair under the chosen radius source (`SMART_IONIC`/`BONDING`/`VDW`) with bond threshold ε (max = rA + rB + ε, default 0.45); it backs the bond editor's "自动应用半径" button. SMART_IONIC is not size-guarded when chosen manually, but if it can't analyse the structure it falls back to bonding radii and sets the `SMART_IONIC_UNAVAILABLE` warning sentinel (surfaced by the UI). Anion–anion site pairs (O–O, O–F, …) are skipped — in an ionic model two anions don't bond, and their wide radius sum would otherwise flag non-bonding O–O distances as bonds.
- **Space groups** (`SpaceGroupCatalog.kt`): catalogs all 230 space-group symbols and derives crystal system/point group. `operations()` returns the symmetry-operation strings for all 230 groups (baked into `operationsTable`); unrecognised names fall back to identity. The table was generated from Hall symbols via `scripts/dev/generate_spacegroups.py` (spglib) — regenerate from there if a group's operations ever need correction.
- **Expression parser** (`ExpressionParser.kt`): small arithmetic evaluator used for fractional coordinates and cell parameters in the UI.

### `renderer`

- `CrystalViewport.kt`: a `@Composable` that draws the scene to a Compose `Canvas`. It owns a `ViewerController` for yaw/pitch/zoom/pan and supports atom picking, measurements (length/angle/dihedral), cell frames, polyhedra, and per-site visibility. Boundary images (shell atoms on the primary-box faces) are visible by default; external-shell atoms are hidden unless their bond rule has `extendAcrossCell = true`. Polyhedra are built from the full coordination environment (every bond's endpoints, including hidden external-shell ligands, via a decoupled `neighbors` table) so boundary/corner coordination is complete; boundary images also act as polyhedron centres. Each polyhedron face is emitted as its own renderable sorted by nearest-vertex depth (painter's order); back faces are drawn outline-only at low alpha (per v0.3.44) so the back silhouette stays faintly visible. Coplanar ligand faces are produced as a single polygon by `convexHullFaces` (2D convex-hull boundary extraction, per v0.3.44 — fixes quad/prism sides that the old edge-count logic dropped); fully planar coordinations fall back to `planarPolygonFaces`, and flat coordinations emit a reversed translucent face so they are visible from both sides. Per v0.5.0 the atom-info window (double-tap) shows the site's bond-valence sum `s = X.XX` after occupancy, fed by a `bondValenceBySite` map from `BondValence.bondValenceSums` (cations at their estimated valence, anions summed over cation bonds).
- `CrystalImageExporter.kt`: produces a high-resolution `Bitmap` using the same projection logic as the viewport (atoms, bonds, polyhedra, measurements, info windows), useful for PNG export. Visibility/polyhedron logic mirrors the viewport.

### `app`

- `MainActivity.kt`: entry point, handles incoming CIF URIs and applies the saved language locale in `attachBaseContext`.
- `KrystalsApp.kt`: root Compose UI (`KrystalsRoot`). Manages the app-level menu, file open/save via the Storage Access Framework, theme/language settings, and PNG export permission flow.
- `DocumentState.kt`: `KrystalsViewModel` holds a list of `DocumentTab`s. Each tab keeps its own `parsed` CIF document, working `structure`, `expansion`, `visibility`, `appearance`, `selectedAtomIds`, and `measurementMode`.
- `EditorPanels.kt`: the structure editor with tabs for basic info, atoms, bonds, and supercell expansion. Edits flow through `CrystalEditor.apply` and update the tab's working structure. The bond tab's "自动应用半径" button (v0.4.1/v0.5.0) drops a dropdown offering smart-ionic/bonding/vdW radius regeneration via `rebuildBondRules`; rebuilds run off the UI thread (`Dispatchers.Default`) behind a "计算中..." `Dialog` (three modes alike). When smart ionic can't analyse the structure it shows a snackbar ("智能离子规则在该晶体下不可用") via the `onMessage` → `SnackbarHostState` channel; choosing smart-ionic on a >100-atom cell first prompts a confirm dialog. A "成键阈值 ε" slider+field (0–0.5, default 0.45, per-tab `DocumentTab.bondEpsilon`) sets the rule max (rA+rB+ε) and re-runs the last-used source live on change. The "新建规则" dialog defaults its max-distance to the bonding-radii sum and re-syncs when the selected sites change.
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
- Rendering is Compose Canvas-based (no Filament); changes to 3D rendering should be done in `CrystalViewport` / `CrystalImageExporter`.

## Workflow

- 代码修改完成后**无需提交 Pull Request**——直接将改动 **merge 到 `master` 分支**并推送即可。这是本仓库的既定流程,适用于所有修改(包括在 worktree 分支上完成的提交),不要停留在特性分支上等待审核。
