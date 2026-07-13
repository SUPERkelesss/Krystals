# Krystals

Krystals is a lightweight Android CIF crystal viewer and editor. It supports multi-block CIF 1.1 files, symmetry expansion, supercells, inferred/custom bonds, measurements, responsive editing, themes, and PNG export.

## Build

- Android Studio with Android SDK 36
- JDK 17
- Gradle 8.11.1 (the project includes wrapper configuration)

```powershell
.\scripts\bootstrap-build.ps1
```

The bootstrap script locates or downloads a portable JDK 17, Android SDK 36, and the Gradle Wrapper before running tests and the APK build.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Modules

- `crystal-core`: loss-aware CIF parsing/writing and crystallographic calculations.
- `renderer`: Filament 1.71.5 integration boundary and the orthographic mobile viewport.
- `app`: Compose UI, Storage Access Framework, tabs, editors, settings, and MediaStore export.

The supplied `res/main.png`, `res/icon.png`, and CIF corpus are packaged as application assets without modifying the originals.
