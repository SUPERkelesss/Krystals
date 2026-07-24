# renderer-filament

Android Filament backend pinned to Filament `1.71.5`. A matching local AAR at
`.tooling/filament-android-1.71.5.aar` is used when present; normal builds resolve the same fixed
Maven coordinate.

Material sources live in `src/main/materials`. The generation script writes precompiled payloads
to `src/main/assets/materials`; once generated and committed, ordinary offline builds never invoke
`matc`. If a payload is absent or corrupt, backend initialization fails fast and the app uses the
Canvas-Legacy session fallback instead of displaying a blank surface.

Regenerate and verify payloads with the exact compiler version (merged into `bootstrap-build.ps1`):

```powershell
# Download matc and compile materials, then build the APK
.\scripts\bootstrap-build.ps1 -Install

# Verify existing materials only (no matc required)
.\scripts\bootstrap-build.ps1 -VerifyOnly
```
