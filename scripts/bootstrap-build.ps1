param(
    [string]$Matc,
    [switch]$Install,
    [switch]$VerifyOnly,
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Tooling = Join-Path $Root '.tooling'
$NdkVersion = '27.2.12479018'
New-Item -ItemType Directory -Force -Path $Tooling | Out-Null

# ── Filament material compilation ──────────────────────────────
$FilamentVersion   = '1.71.5'
$MaterialAbiVersion = '71'
$FilamentTooling   = Join-Path $Tooling "filament-$FilamentVersion"
$MatSource         = Join-Path $Root 'renderer-filament\src\main\materials'
$MatOutput         = Join-Path $Root 'renderer-filament\src\main\assets\materials'
$MatManifest       = Join-Path $MatOutput 'sha256.json'

$MatNames = @(
    'atom_solid', 'atom_occupancy', 'atom_pie', 'atom_transparent',
    'bond_normal', 'bond_normal_transparent', 'bond_hydrogen',
    'mesh_polyhedron'
)

# Compile materials when -Install or -Matc is provided (and not -VerifyOnly)
if ($Install -or $Matc) {
    if (-not $Matc) { $Matc = Join-Path $FilamentTooling 'bin\matc.exe' }
    if ($Install -and -not (Test-Path -LiteralPath $Matc)) {
        New-Item -ItemType Directory -Force -Path $FilamentTooling | Out-Null
        $Archive = Join-Path $FilamentTooling 'filament-windows.tgz'
        $Url = "https://github.com/google/filament/releases/download/v$FilamentVersion/filament-v$FilamentVersion-windows.tgz"
        Invoke-WebRequest -Uri $Url -OutFile $Archive
        tar -xf $Archive -C $FilamentTooling
        $Discovered = Get-ChildItem -LiteralPath $FilamentTooling -Filter matc.exe -Recurse | Select-Object -First 1
        if (-not $Discovered) { throw "matc.exe was not found in $Archive" }
        $Matc = $Discovered.FullName
    }

    if (-not (Test-Path -LiteralPath $Matc)) {
        throw "matc $FilamentVersion is required. Pass -Matc <path>, or use -Install."
    }
    $VersionText = (& $Matc --version 2>&1 | Out-String).Trim()
    # Filament's release archive is pinned above to v1.71.5, while matc --version reports the
    # material ABI (71) rather than the full release version.
    if ($VersionText -ne $MaterialAbiVersion) {
        throw "matc ABI mismatch: Filament $FilamentVersion requires ABI $MaterialAbiVersion, got $VersionText"
    }

    if (-not $VerifyOnly) {
        New-Item -ItemType Directory -Force -Path $MatOutput | Out-Null
        foreach ($Name in $MatNames) {
            & $Matc -a all -p mobile -o (Join-Path $MatOutput "$Name.filamat") (Join-Path $MatSource "$Name.mat")
            if ($LASTEXITCODE -ne 0) { throw "matc failed for $Name.mat" }
        }
        $Hashes = [ordered]@{}
        foreach ($Name in $MatNames) {
            $Hashes["$Name.filamat"] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $MatOutput "$Name.filamat")).Hash.ToLowerInvariant()
        }
        $Hashes | ConvertTo-Json | Set-Content -LiteralPath $MatManifest -Encoding ASCII
    }
}

# Verify materials (always, even without matc)
if (-not (Test-Path -LiteralPath $MatManifest)) { throw "Missing material hash manifest: $MatManifest. Run: .\scripts\bootstrap-build.ps1 -Install" }
$Expected = Get-Content -LiteralPath $MatManifest -Raw | ConvertFrom-Json
foreach ($Name in $MatNames) {
    $FileName = "$Name.filamat"
    $Path = Join-Path $MatOutput $FileName
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing precompiled material: $Path" }
    $Actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected.$FileName) { throw "Hash mismatch for $FileName" }
}
Write-Host "Filament $FilamentVersion materials verified." -ForegroundColor Green

# If VerifyOnly or CompileOnly, stop here — no JDK/SDK/Gradle needed
if ($VerifyOnly -or $CompileOnly) { return }

# ── JDK 17 setup ──────────────────────────────────────────────
function Find-Jdk17 {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Microsoft\jdk-17*',
        (Join-Path $Tooling 'jdk-17')
    )
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        foreach ($path in (Get-Item $candidate -ErrorAction SilentlyContinue)) {
            if (Test-Path (Join-Path $path.FullName 'bin\java.exe')) { return $path.FullName }
        }
    }
    return $null
}

$Jdk = Find-Jdk17
if (-not $Jdk) {
    $JdkZip = Join-Path $Tooling 'microsoft-jdk-17.zip'
    Write-Host 'Downloading portable JDK 17...'
    Invoke-WebRequest 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' -OutFile $JdkZip
    $JdkExtract = Join-Path $Tooling 'jdk-extract'
    Expand-Archive $JdkZip $JdkExtract -Force
    $JdkFolder = Get-ChildItem $JdkExtract -Directory | Select-Object -First 1
    Move-Item $JdkFolder.FullName (Join-Path $Tooling 'jdk-17') -Force
    $Jdk = Join-Path $Tooling 'jdk-17'
}
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

# ── Android SDK setup ─────────────────────────────────────────
$Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms\android-36')) } |
    Select-Object -First 1
if (-not $Sdk) {
    $Sdk = Join-Path $Tooling 'android-sdk'
    $Tools = Join-Path $Sdk 'cmdline-tools\latest'
    if (-not (Test-Path (Join-Path $Tools 'bin\sdkmanager.bat'))) {
        $ToolsZip = Join-Path $Tooling 'android-command-line-tools.zip'
        $ToolsExtract = Join-Path $Tooling 'android-command-line-tools'
        Write-Host 'Downloading Android command-line tools...'
        Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip' -OutFile $ToolsZip
        Expand-Archive $ToolsZip $ToolsExtract -Force
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Tools) | Out-Null
        Move-Item (Join-Path $ToolsExtract 'cmdline-tools') $Tools -Force
    }
}
$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$RequiredSdkPaths = @(
    (Join-Path $Sdk 'platforms\android-36'),
    (Join-Path $Sdk 'build-tools\36.0.0'),
    (Join-Path $Sdk "ndk\$NdkVersion")
)
if ($RequiredSdkPaths.Where({ -not (Test-Path -LiteralPath $_) }).Count -gt 0) {
    if (-not (Test-Path -LiteralPath $SdkManager)) {
        $SdkManager = Get-ChildItem -LiteralPath (Join-Path $Sdk 'cmdline-tools') -Filter sdkmanager.bat -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $SdkManager -or -not (Test-Path -LiteralPath $SdkManager)) {
        # An existing Android Studio SDK can lack command-line tools. Bootstrap a temporary
        # sdkmanager instead of requiring a manually configured global tool installation.
        $ToolsZip = Join-Path $Tooling 'android-command-line-tools.zip'
        $ToolsExtract = Join-Path $Tooling 'android-command-line-tools'
        $TemporarySdkManager = Join-Path $ToolsExtract 'cmdline-tools\bin\sdkmanager.bat'
        if (-not (Test-Path -LiteralPath $TemporarySdkManager)) {
            Write-Host 'Downloading Android command-line tools...'
            Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip' -OutFile $ToolsZip
            Expand-Archive $ToolsZip $ToolsExtract -Force
        }
        $SdkManager = $TemporarySdkManager
    }
    if (-not (Test-Path -LiteralPath $SdkManager)) {
        throw "Android SDK packages are missing and sdkmanager.bat could not be prepared for $Sdk"
    }
    1..30 | ForEach-Object { 'y' } | & $SdkManager --sdk_root=$Sdk --licenses | Out-Host
    & $SdkManager --sdk_root=$Sdk 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' "ndk;$NdkVersion"
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
}
$env:ANDROID_HOME = $Sdk
$escapedSdk = $Sdk.Replace('\', '\\')
Set-Content -LiteralPath (Join-Path $Root 'local.properties') -Value "sdk.dir=$escapedSdk" -Encoding ASCII

# ── Gradle wrapper ───────────────────────────────────────────
$WrapperJar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $WrapperJar)) {
    Write-Host 'Downloading Gradle Wrapper 8.11.1...'
    Invoke-WebRequest 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile $WrapperJar
}

# ── Gradle build ──────────────────────────────────────────────
Push-Location $Root
try {
    & .\gradlew.bat --no-daemon :crystal-core:test :app:testDebugUnitTest :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    $Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $Apk)) { throw "Gradle reported success but APK is missing: $Apk" }
    Write-Host "APK: $Apk" -ForegroundColor Green
} finally {
    Pop-Location
}
