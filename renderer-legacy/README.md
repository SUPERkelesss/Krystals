# renderer-legacy (Canvas-Legacy backend) — END OF LIFE

**停止维护 (End of life)**

This module hosted the Compose Canvas 2D rendering backend (`LegacySceneRenderer`,
`CrystalViewport`, `CrystalImageExporter`). As of this change it is **declared
end-of-life**:

- The `:app` module no longer depends on it — the app renders exclusively with the
  Filament backend (`:renderer-filament`).
- The module is **excluded from `settings.gradle.kts`**: it is no longer part of the
  Gradle build, is not compiled, and its tests do not run.
- The "渲染引擎 / Rendering engine" picker in the Appearance dialog and the top-bar
  "3D/2D" toggle have been removed; the app is fixed on Filament.

The directory is **retained in the repository for reference only** (historical code,
export logic that could be revived, camera/rendering math parity notes). It receives
no further maintenance and must not be re-added to the build or depended on by the app.

If it is ever revived, note the invariants from the contributor docs: camera
orientation is a `Mat3` rotation (not Euler angles), and `CrystalViewport` +
`CrystalImageExporter` + `interaction` camera controllers must stay in sync.
